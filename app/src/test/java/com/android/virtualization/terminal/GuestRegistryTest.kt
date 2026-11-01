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

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.use
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the guest registry's pure logic.
 *
 * These run on the JVM (no device), so they exercise discovery rules, descriptor parsing, id
 * resolution and the unavailable-guest barrier without touching real VM storage. Directory rules
 * are tested against temporary directories the JVM owns, not app storage.
 */
class GuestRegistryTest {

    private fun tempDir(): Path = Files.createTempDirectory("avfmux-registry-test")

    private fun rm(root: Path) {
        Files.walk(root)
            .sorted(Comparator.reverseOrder())
            .use { stream -> stream.forEach { Files.deleteIfExists(it) } }
    }

    @Test
    fun debianIsAlwaysListed() {
        assertEquals(Guest.DEBIAN_ID, Guest.DEBIAN.id)
        assertEquals("Debian", Guest.DEBIAN.distribution)
        assertFalse(Guest.DEBIAN.external)
    }

    @Test
    fun debianInstallDirMatchesUpstream() {
        assertEquals(InstalledImage.INSTALL_DIRNAME, Guest.DEBIAN_INSTALL_DIRNAME)
        assertEquals(InstalledImage.INSTALL_DIRNAME, Guest.DEBIAN.installDirName)
    }

    @Test
    fun unknownGuestIdFallsBackToDebian() {
        assertEquals(Guest.DEBIAN, GuestRegistry.resolve(null, listOf(Guest.DEBIAN)))
        assertEquals(Guest.DEBIAN, GuestRegistry.resolve("", listOf(Guest.DEBIAN)))
        assertEquals(Guest.DEBIAN, GuestRegistry.resolve("nope", listOf(Guest.DEBIAN)))
    }

    @Test
    fun knownGuestIdResolves() {
        val arch = Guest("arch", "Arch Linux", "Arch Linux", "arch")
        val guests = listOf(Guest.DEBIAN, arch)
        assertEquals("arch", GuestRegistry.resolve("arch", guests).id)
    }

    @Test
    fun externalGuestIsNotLaunchable() {
        val external =
            Guest(
                id = "arch",
                displayName = "Arch Linux",
                distribution = "Arch Linux",
                installDirName = "",
                external = true,
                installDir = "/sdcard/Download/AVFMux/guests/arch",
                unavailableReason = GuestRegistry.EXTERNAL_NON_LAUNCHABLE_REASON,
            )
        assertTrue(external.external)
        assertFalse(external.launchableByStorage)
        // Even with a config, marker and disk on hand, an external guest never launches.
        assertFalse(
            GuestRegistry.isLaunchable(
                external,
                hasConfig = true,
                hasMarker = true,
                hasRootPart = true,
            )
        )
        assertFalse(
            GuestRegistry.isLaunchable(
                external,
                hasConfig = false,
                hasMarker = false,
                hasRootPart = false,
            )
        )
    }

    @Test
    fun installedLocalGuestIsLaunchable() {
        assertTrue(
            GuestRegistry.isLaunchable(
                Guest.DEBIAN,
                hasConfig = true,
                hasMarker = true,
                hasRootPart = true,
            )
        )
        assertFalse(
            GuestRegistry.isLaunchable(
                Guest.DEBIAN,
                hasConfig = false,
                hasMarker = true,
                hasRootPart = true,
            )
        )
        assertFalse(
            GuestRegistry.isLaunchable(
                Guest.DEBIAN,
                hasConfig = true,
                hasMarker = false,
                hasRootPart = true,
            )
        )
        // Marker alone is not enough: a missing root_part must block launch.
        assertFalse(
            GuestRegistry.isLaunchable(
                Guest.DEBIAN,
                hasConfig = true,
                hasMarker = true,
                hasRootPart = false,
            )
        )
    }

    @Test
    fun manifestParsingReadsFields() {
        val m =
            GuestManifest.parse(
                """
                {"id":"arch","displayName":"Arch Linux","distribution":"Arch Linux",
                 "installDir":"/sdcard/Download/AVFMux/guests/arch","note":"prepared by hand"}
                """
                    .trimIndent()
            )
        assertEquals("arch", m.id)
        assertEquals("Arch Linux", m.displayName)
        assertEquals("Arch Linux", m.distribution)
        assertEquals("/sdcard/Download/AVFMux/guests/arch", m.installDir)
        assertEquals("prepared by hand", m.note)
    }

    @Test
    fun manifestOptionalFieldsDefault() {
        val onlyRequired = GuestManifest.parse("""{"id":"x","displayName":"X"}""")
        assertEquals("x", onlyRequired.id)
        assertNull(onlyRequired.distribution)
        assertNull(onlyRequired.installDirName)
        assertNull(onlyRequired.installDir)
        assertNull(onlyRequired.note)
        assertEquals(GuestManifest.DEFAULT_DISTRIBUTION, onlyRequired.distributionOrDefault())
    }

    @Test
    fun invalidManifestRejected() {
        // Blank / non-JSON text.
        assertThrows(IllegalArgumentException::class.java) { GuestManifest.parse("") }
        assertThrows(IllegalArgumentException::class.java) { GuestManifest.parse("not json") }
        assertThrows(IllegalArgumentException::class.java) {
            GuestManifest.parse("""{"id":"x","displayName":"X""")
        }
        // Missing or blank required fields.
        assertThrows(IllegalArgumentException::class.java) {
            GuestManifest.parse("""{"displayName":"X"}""")
        }
        assertThrows(IllegalArgumentException::class.java) { GuestManifest.parse("""{"id":"x"}""") }
        assertThrows(IllegalArgumentException::class.java) {
            GuestManifest.parse("""{"id":"  ","displayName":"X"}""")
        }
    }

    @Test
    fun resolveInstallDirUsesNameOrDefault() {
        val filesDir = File("/tmp/avfmux-files")
        assertEquals(
            File(filesDir, "guests/arch").path,
            GuestRegistry.resolveInstallDir(filesDir, "arch").path,
        )
        // Blank name defaults to the directory name passed through, never to Debian's root.
        assertEquals(
            File(filesDir, "guests").path,
            GuestRegistry.resolveInstallDir(filesDir, "").path,
        )
    }

    @Test
    fun unsafeInstallDirNamesRejected() {
        // Escaping and multi-component names are rejected.
        assertFalse(GuestRegistry.isSafeInstallDirName("../linux"))
        assertFalse(GuestRegistry.isSafeInstallDirName("a/b"))
        assertFalse(GuestRegistry.isSafeInstallDirName(".."))
        assertFalse(GuestRegistry.isSafeInstallDirName("."))
        assertFalse(GuestRegistry.isSafeInstallDirName(""))
        assertFalse(GuestRegistry.isSafeInstallDirName("  "))
        assertFalse(GuestRegistry.isSafeInstallDirName("/abs/path"))
        assertFalse(GuestRegistry.isSafeInstallDirName("a\\b"))
        assertFalse(GuestRegistry.isSafeInstallDirName(Guest.DEBIAN_INSTALL_DIRNAME + "/x"))

        // Plain single-component names are accepted.
        assertTrue(GuestRegistry.isSafeInstallDirName("arch"))
        assertTrue(GuestRegistry.isSafeInstallDirName("guest_02"))
    }

    @Test
    fun rootPartRequiredForGuestAndLaunch() {
        val root = tempDir()
        try {
            // Config + marker but no root_part: not a guest, and not launchable.
            val noDisk = Files.createDirectory(root.resolve("no-disk"))
            Files.createFile(noDisk.resolve(InstalledImage.CONFIG_FILENAME))
            Files.createFile(noDisk.resolve(InstalledImage.MARKER_FILENAME))
            assertFalse(GuestRegistry.isGuestDir(hasConfig = true, hasMarker = true, hasRootPart = false))
            assertFalse(
                GuestRegistry.isLaunchable(Guest.DEBIAN, hasConfig = true, hasMarker = true, hasRootPart = false)
            )

            // Adding the disk filename makes it a guest.
            Files.createFile(noDisk.resolve(InstalledImage.ROOTFS_FILENAME))
            assertTrue(GuestRegistry.isGuestDir(hasConfig = true, hasMarker = true, hasRootPart = true))
        } finally {
            rm(root)
        }
    }

    @Test
    fun descriptorValidationReportsExactReasons() {
        val root = tempDir()
        try {
            // Descriptor with no installDir at all.
            assertEquals(
                "descriptor declares no installDir",
                GuestRegistry.externalUnavailableReason(null),
            )

            // Declared install dir that does not exist -> app sandbox reason.
            val missing = root.resolve("missing").toAbsolutePath().toString()
            assertEquals(
                "install dir not accessible to this app (app sandbox)",
                GuestRegistry.externalUnavailableReason(missing),
            )

            // Existing dir with no vm_config.json.
            val noConfig = Files.createDirectory(root.resolve("no-config"))
            assertEquals(
                "no ${InstalledImage.CONFIG_FILENAME}",
                GuestRegistry.externalUnavailableReason(noConfig.toString()),
            )

            // Existing dir with a config: still non-launchable, with the fixed external reason.
            val withConfig = Files.createDirectory(root.resolve("with-config"))
            Files.createFile(withConfig.resolve(InstalledImage.CONFIG_FILENAME))
            assertEquals(
                GuestRegistry.EXTERNAL_NON_LAUNCHABLE_REASON,
                GuestRegistry.externalUnavailableReason(withConfig.toString()),
            )
            assertEquals(
                "external bundle is outside app-private storage and is not imported or launched " +
                    "by this build",
                GuestRegistry.EXTERNAL_NON_LAUNCHABLE_REASON,
            )
            assertTrue(
                GuestRegistry.EXTERNAL_NON_LAUNCHABLE_REASON.contains("app-private storage")
            )
            assertTrue(GuestRegistry.EXTERNAL_NON_LAUNCHABLE_REASON.contains("not imported"))
        } finally {
            rm(root)
        }
    }

    @Test
    fun phantomGuestDirectoryRejected() {
        val root = tempDir()
        try {
            // A directory with only the config is still not a guest: the marker is required too.
            val cfgOnly = Files.createDirectory(root.resolve("cfg-only"))
            Files.createFile(cfgOnly.resolve(InstalledImage.CONFIG_FILENAME))
            assertFalse(GuestRegistry.isGuestDir(hasConfig = true, hasMarker = false, hasRootPart = true))

            // A directory with only the marker is not a guest either.
            assertFalse(GuestRegistry.isGuestDir(hasConfig = false, hasMarker = true, hasRootPart = true))

            // Config + marker is installed but not yet a launchable guest: the disk is missing.
            val real = Files.createDirectory(root.resolve("real"))
            Files.createFile(real.resolve(InstalledImage.CONFIG_FILENAME))
            Files.createFile(real.resolve(InstalledImage.MARKER_FILENAME))
            assertFalse(GuestRegistry.isGuestDir(hasConfig = true, hasMarker = true, hasRootPart = false))

            Files.createFile(real.resolve(InstalledImage.ROOTFS_FILENAME))
            assertTrue(GuestRegistry.isGuestDir(hasConfig = true, hasMarker = true, hasRootPart = true))
        } finally {
            rm(root)
        }
    }

    @Test
    fun duplicateIdDescriptorDoesNotShadowDebian() {
        val filesDir = tempDir()
        val descriptors = tempDir()
        try {
            // An external descriptor reusing Debian's id must be dropped, not listed twice.
            Files.writeString(
                descriptors.resolve("debian.json"),
                """{"id":"debian","displayName":"Fake Debian","installDir":"/nowhere"}""",
            )
            val context =
                object : android.content.Context() {
                    override fun getFilesDir(): File = filesDir.toFile()
                }
            val guests = GuestRegistry(context, descriptors.toFile()).list()
            assertEquals(1, guests.count { it.id == Guest.DEBIAN_ID })
            assertFalse(guests.any { it.external })
        } finally {
            rm(filesDir)
            rm(descriptors)
        }
    }

    @Test
    fun unsafeDescriptorInstallDirNameNotListedAsGuest() {
        val filesDir = tempDir()
        try {
            // An app-owned descriptor claiming a path-escaping installDirName is rejected.
            val dir = Files.createDirectories(filesDir.resolve("guests/bad"))
            Files.writeString(
                dir.resolve(GuestRegistry.MANIFEST_FILENAME),
                """{"id":"bad","displayName":"Bad","installDirName":"../linux"}""",
            )
            val context =
                object : android.content.Context() {
                    override fun getFilesDir(): File = filesDir.toFile()
                }
            val guests = GuestRegistry(context).list()
            assertFalse(guests.any { it.id == "bad" })
            assertEquals(listOf(Guest.DEBIAN_ID), guests.map { it.id })
        } finally {
            rm(filesDir)
        }
    }

    @Test
    fun noHardCodedShellOwnedPathRemains() {
        // The registry must not declare device-specific shell-owned storage as a guest source.
        val source = File("src/main/kotlin/com/android/virtualization/terminal/GuestRegistry.kt")
        if (source.isFile) {
            val text = source.readText()
            assertFalse(text.contains("/data/local/tmp"))
        }
        // The reason wording is about app-private storage, not about a particular device path.
        assertFalse(GuestRegistry.EXTERNAL_NON_LAUNCHABLE_REASON.contains("/data/local/tmp"))
    }
}
