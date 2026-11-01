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

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.use
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the guest registry's pure logic.
 *
 * These run on the JVM (no device), so they exercise discovery rules, manifest parsing, id
 * resolution and the unavailable-guest barrier without touching real VM storage. The phantom-dir
 * rule is tested against a temporary directory that the JVM owns, not app storage.
 */
class GuestRegistryTest {

    @Test
    fun debianIsAlwaysListed() {
        val guests = GuestRegistry.listOf(Guest.DEBIAN)
        assertEquals(Guest.DEBIAN_ID, guests.first().id)
        assertEquals("Debian", guests.first().distribution)
    }

    @Test
    fun debianInstallDirMatchesUpstream() {
        assertEquals(InstalledImage.INSTALL_DIRNAME, Guest.DEBIAN_INSTALL_DIRNAME)
        assertEquals(Guest.DEBIAN_INSTALL_DIRNAME, Guest.DEBIAN.installDirName)
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
                unavailableReason = "outside app storage",
            )
        assertTrue(external.external)
        assertFalse(external.launchableByStorage)
        assertFalse(GuestRegistry.isLaunchable(external, installed = true))
        assertFalse(GuestRegistry.isLaunchable(external, installed = false))
    }

    @Test
    fun installedLocalGuestIsLaunchable() {
        assertTrue(GuestRegistry.isLaunchable(Guest.DEBIAN, installed = true))
        assertFalse(GuestRegistry.isLaunchable(Guest.DEBIAN, installed = false))
    }

    @Test
    fun manifestParsingReadsFields() {
        val m = GuestManifest.parse("""{"displayName":"Arch Linux","distribution":"Arch"}""")
        assertNotNull(m)
        assertEquals("Arch Linux", m!!.displayName)
        assertEquals("Arch", m.distribution)
    }

    @Test
    fun manifestMissingFieldsIsAllowed() {
        val onlyName = GuestManifest.parse("""{"displayName":"X"}""")
        assertNotNull(onlyName)
        assertEquals("X", onlyName!!.displayName)
        assertNull(onlyName.distribution)

        val empty = GuestManifest.parse("{}")
        assertNotNull(empty)
        assertNull(empty!!.displayName)
        assertNull(empty.distribution)
    }

    @Test
    fun malformedManifestYieldsNull() {
        assertNull(GuestManifest.parse("not json"))
        assertNull(GuestManifest.parse(""))
        assertNull(GuestManifest.parse("""{"displayName":"X""""))
    }

    @Test
    fun phantomGuestDirectoryHasNoConfig() {
        val root: Path = Files.createTempDirectory("avfmux-registry-test")
        try {
            val phantom = Files.createDirectory(root.resolve("phantom"))
            // A stray directory with no vm_config.json is not a guest.
            assertFalse(GuestRegistry.isGuestDir(InstalledImage.instanceFor(phantom)))

            // A directory holding the config filename is a guest.
            val real = Files.createDirectory(root.resolve("real"))
            Files.createFile(real.resolve(InstalledImage.CONFIG_FILENAME))
            assertTrue(GuestRegistry.isGuestDir(InstalledImage.instanceFor(real)))
        } finally {
            Files.walk(root)
                .sorted(Comparator.reverseOrder())
                .use { stream -> stream.forEach { Files.deleteIfExists(it) } }
        }
    }

    @Test
    fun declaredExternalCandidateNeverDropped() {
        val candidates = GuestRegistry.EXTERNAL_CANDIDATES
        assertTrue(candidates.any { it.id == "arch" })
        val arch = candidates.first { it.id == "arch" }
        val reason = arch.unavailableReason
        assertTrue(reason.contains(arch.path.path))
        assertTrue(reason.contains("no API"))
        assertTrue(reason.contains("privileged install"))
    }
}
