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
 * Optional metadata for a guest definition (`guest.json`).
 *
 * Deliberately minimal: only presentation metadata lives here. Boot configuration stays in the
 * guest's own `vm_config.json`, so this file never has to be kept in sync with AVF's schema.
 *
 * Both fields are nullable: a manifest may name the guest, describe its distribution, or just be
 * an empty object. Callers ([GuestRegistry.list]) substitute a fallback (the directory id and
 * "Unknown") when a field is absent.
 */
data class GuestManifest(
    val displayName: String? = null,
    val distribution: String? = null,
) {
    companion object {
        private val gson = Gson()

        /**
         * Parses a `guest.json` manifest with Gson.
         *
         * Returns null when the text is empty or not valid JSON. Malformed content is reported by
         * the caller ([GuestRegistry.readManifest]) rather than throwing out of here, so a broken
         * manifest can never hide a guest directory.
         */
        fun parse(text: String): GuestManifest? {
            if (text.isBlank()) return null
            return try {
                val parsed = gson.fromJson(text, GuestManifest::class.java)
                if (parsed == null) null else parsed
            } catch (e: Exception) {
                null
            }
        }
    }
}
