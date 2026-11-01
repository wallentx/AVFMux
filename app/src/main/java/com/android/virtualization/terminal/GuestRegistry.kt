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
 * only when its own config and disk are present in this app's private storage.
 *
 * A directory under `filesDir/guests` counts as a guest only when it holds a real config file
 * (`InstalledImage.CONFIG_FILENAME`). Directories without one are skipped: they are leftovers,
 * not guests, and listing them would offer the user a row that can never boot.
 *
 * Guests whose artifacts exist outside this app's storage are reported with an
 * [Guest.unavailableReason] rather than silently ignored, so the UI can explain why they cannot
 * be launched.
 */
class GuestRegistry(private val context: Context) {

    /** Directory under the app's filesDir that holds per-guest install dirs. */
    private val guestsRoot: File
        get() = File(context.getFilesDir(), GUESTS_DIRNAME)

    /**
     * All guests known to this app, always including the stock Debian guest.
     *
     * Debian is always listed even when not yet installed, because it is the guest upstream
     * provisions; the UI decides whether to offer install or launch based on [isInstalled].
     */
    fun list(): List<Guest> {
        val guests = mutableListOf(Guest.DEBIAN)
        val seenIds = mutableSetOf(Guest.DEBIAN_ID)

        // Discover guest definitions stored under the app's private guests directory.
        val dirs = guestsRoot.listFiles()?.filter { it.isDirectory } ?: emptyList()
        for (dir in dirs) {
            val id = dir.name
            if (!seenIds.add(id)) continue
            if (!hasConfig(dir)) {
                Log.d(TAG, "Skipping $dir: no ${InstalledImage.CONFIG_FILENAME}")
                seenIds.remove(id)
                continue
            }
            val manifest = readManifest(dir)
            guests.add(
                Guest(
                    id = id,
                    displayName = manifest?.displayName ?: id,
                    distribution = manifest?.distribution ?: "Unknown",
                    installDirName = id,
                )
            )
        }

        // External guests are reported, never launched. They are appended last so the normal,
        // launchable guests stay prominent in the UI.
        guests.addAll(externalCandidates())

        return guests
    }

    /** Whether [dir] holds an actual guest config, i.e. it is a guest and not a stray directory. */
    private fun hasConfig(dir: File): Boolean =
        InstalledImage.instanceFor(dir.toPath()).configPath.toFile().isFile

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
        return if (path.toFile().exists()) path else null
    }

    /**
     * Resolve the [InstalledImage] backing [guest].
     *
     * The stock Debian guest lives at `<filesDir>/linux`, exactly where upstream put it. Any
     * additional guest lives at `<filesDir>/guests/<id>` so it never collides with Debian's
     * directory and never mutates Debian's existing disk or config.
     */
    fun installedImage(guest: Guest): InstalledImage {
        require(!guest.external) { "External guest ${guest.id} has no image in this app's storage" }
        val dir =
            if (guest.id == Guest.DEBIAN_ID) {
                File(context.getFilesDir(), InstalledImage.INSTALL_DIRNAME)
            } else {
                File(guestsRoot, guest.installDirName)
            }
        return InstalledImage.instanceFor(dir.toPath())
    }

    /** Look up a guest by [id], falling back to Debian for unknown ids. */
    fun find(id: String?): Guest {
        if (id.isNullOrEmpty()) return Guest.DEBIAN
        return list().firstOrNull { it.id == id } ?: Guest.DEBIAN
    }

    /**
     * Declared guests that live outside this app's storage, reported but not launchable.
     *
     * These are never filtered out. The app cannot stat `/data/local/tmp` (it is owned by
     * `shell`, `shell_data_file`), so an existence check there answers "no" for everyone and
     * would silently hide an environment the user knows exists. Instead each candidate is
     * always listed with an explicit [ExternalCandidate.unavailableReason], and the read-only
     * probe result is recorded as a hint only. Nothing here opens, moves or deletes any path.
     */
    fun externalCandidates(): List<Guest> {
        return EXTERNAL_CANDIDATES.map { candidate ->
            val visible = candidate.path.exists()
            Log.d(TAG, "External ${candidate.id} at ${candidate.path}: app-visible=$visible")
            Guest(
                id = candidate.id,
                displayName = candidate.displayName,
                distribution = candidate.distribution,
                installDirName = "",
                external = true,
                unavailableReason = candidate.unavailableReason,
            )
        }
    }

    /**
     * Reads an optional `guest.json` manifest from a guest directory.
     *
     * Metadata is intentionally minimal and optional; a guest with no manifest is still usable
     * and is labelled by its directory name. Malformed manifests are ignored rather than fatal.
     */
    private fun readManifest(dir: File): GuestManifest? {
        val file = File(dir, MANIFEST_FILENAME)
        if (!file.isFile) return null
        return try {
            GuestManifest.parse(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring malformed guest manifest at $file", e)
            null
        }
    }

    companion object {
        private const val TAG = "GuestRegistry"

        /** Existing upstream install dir root (`InstalledImage.INSTALL_DIRNAME`). */
        const val GUEST_ROOT_DIRNAME: String = InstalledImage.INSTALL_DIRNAME

        /** Directory listing additional guest definitions. */
        const val GUESTS_DIRNAME: String = "guests"

        const val MANIFEST_FILENAME: String = "guest.json"

        /** Pure helper: at least the stock Debian guest, always first. */
        fun listOf(debian: Guest): List<Guest> = kotlin.collections.listOf(debian)

        /** Pure helper: resolve a guest id, falling back to Debian for unknown ids. */
        fun resolve(id: String?, guests: List<Guest>): Guest {
            if (id.isNullOrEmpty()) return Guest.DEBIAN
            return guests.firstOrNull { it.id == id } ?: Guest.DEBIAN
        }

        /** Pure helper: a guest can launch only when it is local and installed. */
        fun isLaunchable(guest: Guest, installed: Boolean): Boolean =
            guest.launchableByStorage && !guest.external && installed

        /**
         * Pure helper mirroring discovery: a directory is a guest only when it holds a config.
         *
         * Kept as a function over [InstalledImage] so the rule is testable on the JVM without a
         * Context or real app storage.
         */
        fun isGuestDir(image: InstalledImage): Boolean = image.configPath.toFile().isFile

        /**
         * Declared guest artifacts that live outside this app's storage.
         *
         * This is a *declaration* table, not a launch path: the entries were device-verified, but
         * a normal app cannot read them, so they are listed read-only with an explanation instead
         * of being dropped by an existence check that can only ever fail.
         */
        val EXTERNAL_CANDIDATES: List<ExternalCandidate> =
            listOf(
                ExternalCandidate(
                    id = "arch",
                    displayName = "Arch Linux",
                    distribution = "Arch Linux",
                    path = File("/data/local/tmp/termux-arch-v2/arch-rootfs.img"),
                    owner = "shell",
                )
            )
    }

    /** A guest whose artifacts are owned outside this app's private storage. */
    data class ExternalCandidate(
        val id: String,
        val displayName: String,
        val distribution: String,
        /** Disk image location. Declared and device-verified; the app must not open it. */
        val path: File,
        /** Storage owner, reported so the unavailability reason is concrete. */
        val owner: String,
    ) {
        /**
         * Why this guest cannot be launched by this app, regardless of whether the path happens
         * to be visible. Wording deliberately avoids claiming detection: the app only knows the
         * location was declared.
         */
        val unavailableReason: String
            get() =
                "Declared, device-verified location ${path.path} is owned by $owner " +
                    "(`/data/local/tmp`, shell_data_file). This app cannot read or open it, " +
                    "AVF exposes no API to attach to another owner's VM, and launching requires " +
                    "a privileged install or importing the image into app storage."
    }
}
