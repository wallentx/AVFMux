/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.virtualization.terminal

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.file.Path

/**
 * Enumerates the guests this app can see, and resolves the ones it can launch.
 *
 * Discovery is deliberately conservative. AVF exposes no API for enumerating a VM registry
 * (`VirtualMachineManager` only offers name-keyed create/get/getOrCreate/delete), and neither
 * shell nor an untrusted app can read another app's config store. So a guest is "discovered"
 * only when its own descriptor, config and disk are present in this app's private storage.
 *
 * Two sources are scanned:
 *
 *  1. App-owned guests: directories under `filesDir/guests`. A directory counts as a guest only
 *     when it holds a valid `guest.json`, the upstream config (`InstalledImage.CONFIG_FILENAME`)
 *     and the installed marker (`InstalledImage.MARKER_FILENAME`). Anything else is skipped —
 *     leftover directories are not guests, and listing them would offer a row that can never boot.
 *  2. External descriptors: `*.json` files inside [descriptorRoot], supplied by the caller. These
 *     are reported with an exact [Guest.unavailableReason]; they are never launchable.
 *
 * [descriptorRoot] is a constructor parameter rather than resolved from `Environment` here, so the
 * registry stays compilable and testable on a plain JVM. The activity that shows the list decides
 * which directory to pass (the app-visible Downloads location).
 *
 * Everything here is read-only: nothing is opened for writing, moved, deleted or overwritten.
 */
class GuestRegistry(
    private val context: Context,
    /** Directory of external `*.json` descriptors, or null to skip external discovery. */
    private val descriptorRoot: File? = null,
) {

    /** Directory under the app's filesDir that holds per-guest install dirs. */
    private val guestsRoot: File
        get() = File(context.getFilesDir(), GUESTS_DIRNAME)

    /**
     * All guests known to this app, always including the stock Debian guest.
     *
     * Debian is always listed even when not yet installed, because it is the guest upstream
     * provisions; the UI decides whether to offer install or launch based on [isInstalled].
     * Order is Debian, then app-owned guests, then external descriptors.
     */
    fun list(): List<Guest> {
        val guests = mutableListOf(Guest.DEBIAN)
        val seenIds = mutableSetOf(Guest.DEBIAN_ID)

        // Discover guest definitions stored under the app's private guests directory.
        val dirs =
            try {
                guestsRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot list $guestsRoot", e)
                emptyList()
            }
        for (dir in dirs.sortedBy { it.name }) {
            val guest = appOwnedGuest(dir)
            if (guest == null) {
                // appOwnedGuest already logged why this directory is not a guest.
                continue
            }
            if (!seenIds.add(guest.id)) {
                Log.w(TAG, "Skipping ${dir.name}: duplicate guest id ${guest.id}")
                continue
            }
            guests.add(guest)
        }

        guests.addAll(externalGuests(seenIds))

        return guests
    }

    /**
     * Builds an app-owned guest from [dir], or null when [dir] is not a real guest.
     *
     * Read-only probe: the descriptor must parse, the config must exist, and the marker must
     * exist. Any failure is logged and the directory is dropped.
     */
    private fun appOwnedGuest(dir: File): Guest? {
        val manifestFile = File(dir, MANIFEST_FILENAME)
        if (!manifestFile.isFile) {
            Log.w(TAG, "Skipping $dir: no $MANIFEST_FILENAME")
            return null
        }
        val manifest =
            try {
                GuestManifest.parse(manifestFile.readText())
            } catch (e: Exception) {
                Log.w(TAG, "Skipping $dir: unreadable $MANIFEST_FILENAME", e)
                return null
            }

        val dirName =
            manifest.installDirName.takeUnless { it.isNullOrBlank() } ?: dir.name
        if (!isSafeInstallDirName(dirName)) {
            Log.w(TAG, "Skipping $dir: unsafe installDirName $dirName")
            return null
        }
        val installDir = resolveInstallDir(context.getFilesDir(), dirName)
        val image = InstalledImage.instanceFor(installDir.toPath())
        if (!image.configPath.toFile().isFile) {
            Log.w(TAG, "Skipping $dir: ${installDir.name} has no ${InstalledImage.CONFIG_FILENAME}")
            return null
        }
        if (!image.isInstalled()) {
            Log.w(TAG, "Skipping $dir: ${installDir.name} has no ${InstalledImage.MARKER_FILENAME}")
            return null
        }
        if (!image.rootPartition.toFile().isFile) {
            Log.w(TAG, "Skipping $dir: ${installDir.name} has no ${InstalledImage.ROOTFS_FILENAME}")
            return null
        }
        return Guest(
            id = manifest.id!!,
            displayName = manifest.displayName!!,
            distribution = manifest.distributionOrDefault(),
            installDirName = dirName,
        )
    }

    /**
     * External descriptors found in [descriptorRoot].
     *
     * An unmounted or inaccessible root yields an empty list rather than an exception: external
     * storage can legitimately be absent. Each descriptor becomes an external [Guest] with an
     * exact reason explaining why it cannot be launched here. Nothing is launched, imported or
     * mutated.
     */
    private fun externalGuests(seenIds: MutableSet<String>): List<Guest> {
        val root = descriptorRoot ?: return emptyList()
        if (!root.isDirectory) {
            Log.w(TAG, "External descriptor root $root is not accessible")
            return emptyList()
        }
        val files =
            try {
                root.listFiles { f -> f.isFile && f.name.endsWith(DESCRIPTOR_SUFFIX) }.orEmpty()
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot list external descriptor root $root", e)
                return emptyList()
            }
        return files.sortedBy { it.name }.mapNotNull { descriptorGuest(it, seenIds) }
    }

    /** Parses one external descriptor file into a non-launchable [Guest]. */
    private fun descriptorGuest(file: File, seenIds: MutableSet<String>): Guest? {
        val manifest =
            try {
                GuestManifest.parse(file.readText())
            } catch (e: Exception) {
                Log.w(TAG, "Ignoring unreadable external descriptor $file", e)
                return null
            }
        if (!seenIds.add(manifest.id!!)) {
            // A descriptor may not shadow Debian or an app-owned guest with the same id.
            Log.w(TAG, "Skipping ${file.name}: duplicate guest id ${manifest.id}")
            return null
        }
        val reason = externalUnavailableReason(manifest.installDir)
        Log.d(TAG, "External descriptor ${file.name}: ${manifest.displayName} -> $reason")
        return Guest(
            id = manifest.id!!,
            displayName = manifest.displayName!!,
            distribution = manifest.distributionOrDefault(),
            installDirName = manifest.installDirName.orEmpty(),
            external = true,
            installDir = manifest.installDir,
            unavailableReason = reason,
        )
    }

    /** Whether the guest's config and disk are present and the guest is marked installed. */
    fun isInstalled(guest: Guest): Boolean {
        if (guest.external) return false
        return installedImage(guest).isInstalled()
    }

    /**
     * The config path for [guest], or null when the guest has no usable config.
     *
     * Only guests whose artifacts are inside this app's private storage resolve. External guests
     * intentionally do not resolve: launching them would require access this app does not have.
     */
    fun configPath(guest: Guest): Path? {
        if (guest.external) return null
        val path = installedImage(guest).configPath
        return if (path.toFile().isFile) path else null
    }

    /** Whether [guest] may be launched right now: local, config, marker and root_part present. */
    fun isLaunchable(guest: Guest): Boolean {
        if (guest.external) return false
        val image = installedImage(guest)
        return isLaunchable(
            guest,
            hasConfig = image.configPath.toFile().isFile,
            hasMarker = image.isInstalled(),
            hasRootPart = image.rootPartition.toFile().isFile,
        )
    }

    /**
     * Resolve the [InstalledImage] backing [guest].
     *
     * The stock Debian guest lives at `<filesDir>/linux`, exactly where upstream put it. Any
     * additional guest lives at `<filesDir>/guests/<installDirName>` so it never collides with
     * Debian's directory and never mutates Debian's existing disk or config.
     */
    fun installedImage(guest: Guest): InstalledImage {
        require(!guest.external) { "External guest ${guest.id} has no image in this app's storage" }
        val dir =
            if (guest.id == Guest.DEBIAN_ID) {
                File(context.getFilesDir(), InstalledImage.INSTALL_DIRNAME)
            } else {
                resolveInstallDir(context.getFilesDir(), guest.installDirName)
            }
        return InstalledImage.instanceFor(dir.toPath())
    }

    /** Look up a guest by [id], falling back to Debian for unknown ids. */
    fun find(id: String?): Guest {
        if (id.isNullOrEmpty()) return Guest.DEBIAN
        return list().firstOrNull { it.id == id } ?: Guest.DEBIAN
    }

    companion object {
        private const val TAG = "GuestRegistry"

        /** Existing upstream install dir root (`InstalledImage.INSTALL_DIRNAME`). */
        const val GUEST_ROOT_DIRNAME: String = InstalledImage.INSTALL_DIRNAME

        /** Directory listing additional guest definitions. */
        const val GUESTS_DIRNAME: String = "guests"

        const val MANIFEST_FILENAME: String = "guest.json"
        const val DESCRIPTOR_SUFFIX: String = ".json"

        /**
         * Relative subdirectory for external descriptors inside app-visible Downloads.
         *
         * Only the *relative* location is declared here; the absolute root is chosen by the caller
         * so this class never encodes a device-specific path.
         */
        const val EXTERNAL_DESCRIPTOR_SUBDIR: String = "AVFMux/guests"

        /** Pure helper: resolve a guest id, falling back to Debian for unknown ids. */
        fun resolve(id: String?, guests: List<Guest>): Guest {
            if (id.isNullOrEmpty()) return Guest.DEBIAN
            return guests.firstOrNull { it.id == id } ?: Guest.DEBIAN
        }

        /**
         * Pure helper: a guest can launch only when it is local, has a config, is installed, and
         * its root filesystem disk exists.
         *
         * Mirrors discovery exactly, so tests can prove the UI gate and discovery agree without a
         * Context or real storage.
         */
        fun isLaunchable(
            guest: Guest,
            hasConfig: Boolean,
            hasMarker: Boolean,
            hasRootPart: Boolean,
        ): Boolean =
            !guest.external && guest.launchableByStorage && hasConfig && hasMarker && hasRootPart

        /** Pure helper: a directory is a guest only when config, marker and disk all exist. */
        fun isGuestDir(hasConfig: Boolean, hasMarker: Boolean, hasRootPart: Boolean): Boolean =
            hasConfig && hasMarker && hasRootPart

        /**
         * Pure helper: whether [name] is usable as a directory name under `filesDir/guests`.
         *
         * Rejects blank names, `.`/`..`, any path separator, and anything that is not a single
         * plain filename component, so a descriptor cannot escape the guests root.
         */
        fun isSafeInstallDirName(name: String): Boolean {
            if (name.isBlank() || name == "." || name == "..") return false
            if (name.contains('/') || name.contains('\\')) return false
            return File(name).name == name && File(name).parent == null
        }

        /** Pure helper: `<filesDir>/guests/<installDirName>`, or `<filesDir>/guests` if blank. */
        fun resolveInstallDir(filesDir: File, installDirName: String): File {
            val guestRoot = File(filesDir, GUESTS_DIRNAME)
            val name = installDirName.takeUnless { it.isNullOrBlank() }
            return if (name == null) guestRoot else File(guestRoot, name)
        }

        /**
         * Pure helper mirroring [descriptorGuest]: why an external descriptor cannot launch here.
         *
         * [installDir] is the descriptor's declared absolute install directory, or null when the
         * descriptor omits it. The result never claims the bundle is launchable.
         */
        fun externalUnavailableReason(installDir: String?): String {
            if (installDir.isNullOrBlank()) {
                return "descriptor declares no installDir"
            }
            val dirFile = File(installDir)
            if (!dirFile.isDirectory) {
                return "install dir not accessible to this app (app sandbox)"
            }
            if (!File(dirFile, InstalledImage.CONFIG_FILENAME).isFile) {
                return "no ${InstalledImage.CONFIG_FILENAME}"
            }
            return EXTERNAL_NON_LAUNCHABLE_REASON
        }

        /** Fixed reason for a bundle that is outside this app's private storage. */
        const val EXTERNAL_NON_LAUNCHABLE_REASON: String =
            "external bundle is outside app-private storage and is not imported or launched by " +
                "this build"

        /** Reason used when a descriptor file cannot even be read. */
        const val DESCRIPTOR_UNREADABLE_REASON: String = "descriptor not readable"
    }
}
