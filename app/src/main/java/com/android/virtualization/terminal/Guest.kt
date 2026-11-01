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

/**
 * A guest definition: what to boot, not whether it is currently booted.
 *
 * Guest definitions are kept separate from VM runtime state. A definition is durable (name,
 * distribution, where its config and disk live); runtime state (running/stopped) is owned by
 * AVF and is never stored here.
 *
 * [id] must be stable and filesystem-safe. [installDirName] is the directory under the app's
 * filesDir that holds this guest's config and disk.
 */
data class Guest(
    val id: String,
    val displayName: String,
    val distribution: String,
    val installDirName: String,
    /**
     * True when this guest's config and disk live outside the app's private storage. Such guests
     * cannot be launched by the app itself; they are reported but not bootable.
     */
    val external: Boolean = false,
    /** Human-readable reason this guest cannot be launched, or null when it can. */
    val unavailableReason: String? = null,
) {
    /** True when this guest's config and disk can be opened by this app. */
    val launchableByStorage: Boolean
        get() = !external

    /** Stable identity of the stock Debian guest installed by upstream TerminalApp. */
    companion object {
        const val DEBIAN_ID: String = "debian"
        const val DEBIAN_INSTALL_DIRNAME: String = InstalledImage.INSTALL_DIRNAME

        val DEBIAN: Guest =
            Guest(
                id = DEBIAN_ID,
                displayName = "Debian",
                distribution = "Debian",
                installDirName = DEBIAN_INSTALL_DIRNAME,
            )
    }
}
