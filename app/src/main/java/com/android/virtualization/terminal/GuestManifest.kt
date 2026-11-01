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

import com.google.gson.Gson

/**
 * Metadata for a guest descriptor (`guest.json`).
 *
 * Boot configuration stays in the guest's own `vm_config.json`, so this file never has to be kept
 * in sync with AVF's schema. Only presentation metadata and the install location live here.
 *
 * The same shape is used for both kinds of descriptor:
 *
 *  - app-owned (`filesDir/guests/<dir>/guest.json`): [installDirName] selects the subdirectory
 *    under `filesDir/guests`; [installDir] is normally absent.
 *  - external (`Download/AVFMux/guests/<name>.json`): [installDir] is the absolute install
 *    directory, because an external descriptor cannot be resolved relative to app storage.
 *
 * [id] and [displayName] are required and must be non-blank; [parse] throws
 * [IllegalArgumentException] when they are missing so a malformed descriptor is rejected instead
 * of producing a phantom guest.
 */
data class GuestManifest(
    val id: String? = null,
    val displayName: String? = null,
    val distribution: String? = null,
    val installDirName: String? = null,
    val installDir: String? = null,
    val note: String? = null,
) {
    /** Distribution label, defaulting to "Unknown" when the descriptor omits it. */
    fun distributionOrDefault(): String =
        if (distribution.isNullOrBlank()) DEFAULT_DISTRIBUTION else distribution

    companion object {
        private val gson = Gson()

        /** Distribution shown when a descriptor does not name one. */
        const val DEFAULT_DISTRIBUTION: String = "Unknown"

        /**
         * Parses a `guest.json` descriptor with Gson.
         *
         * @throws IllegalArgumentException when the text is blank, not valid JSON, or lacks a
         *   non-blank `id`/`displayName`.
         */
        fun parse(json: String): GuestManifest {
            require(json.isNotBlank()) { "guest descriptor is empty" }
            val parsed =
                try {
                    gson.fromJson(json, GuestManifest::class.java)
                } catch (e: Exception) {
                    throw IllegalArgumentException("guest descriptor is not valid JSON", e)
                }
            requireNotNull(parsed) { "guest descriptor is empty" }
            require(!parsed.id.isNullOrBlank()) { "guest descriptor has no id" }
            require(!parsed.displayName.isNullOrBlank()) { "guest descriptor has no displayName" }
            return parsed
        }
    }
}
