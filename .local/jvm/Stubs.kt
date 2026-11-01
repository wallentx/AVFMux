package com.android.virtualization.terminal
import java.nio.file.Path
// Test-only stub: lets pure registry logic compile without Android internals.
class InstalledImage private constructor(val installDir: Path) {
    val configPath: Path get() = installDir.resolve("vm_config.json")
    fun isInstalled(): Boolean = installDir.resolve("completed").toFile().exists()
    companion object {
        const val INSTALL_DIRNAME = "linux"
        fun instanceFor(dir: Path) = InstalledImage(dir)
    }
}
