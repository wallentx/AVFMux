/*
 * Test-only stub. NOT part of the Android app.
 *
 * Upstream `InstalledImage` (packages/apps/TerminalApp) is the full installer/disk-resize type:
 * it uses Os.fstat/posix_fallocate/ftruncate, FileUtils.deleteContentsAndDir and shell commands,
 * none of which exist on a plain JVM. The registry logic uses only three of its members, so this
 * stub exposes exactly those, with the same semantics as upstream:
 *
 *  - `configPath` is `installDir/vm_config.json`
 *  - `isInstalled()` is true when the upstream `completed` marker exists
 *  - `INSTALL_DIRNAME` / `CONFIG_FILENAME` match upstream's constants.
 *
 * Do not copy this file into the app sources; the real InstalledImage.kt is compiled by Soong.
 */
package com.android.virtualization.terminal

import java.nio.file.Files
import java.nio.file.Path

/** Test-only stand-in for upstream `InstalledImage`, limited to what the registry needs. */
class InstalledImage private constructor(val installDir: Path) {

    /** The path to the VM config file, exactly as upstream resolves it. */
    val configPath: Path = installDir.resolve(CONFIG_FILENAME)

    /** Upstream install marker: `completed`. */
    private val marker: Path = installDir.resolve(MARKER_FILENAME)

    /** Tests whether this image is actually installed. */
    fun isInstalled(): Boolean = Files.exists(marker)

    companion object {
        const val INSTALL_DIRNAME: String = "linux"
        const val CONFIG_FILENAME: String = "vm_config.json"
        private const val MARKER_FILENAME: String = "completed"

        /** Returns an InstalledImage for an explicit install directory. */
        fun instanceFor(installDir: Path): InstalledImage = InstalledImage(installDir)
    }
}
