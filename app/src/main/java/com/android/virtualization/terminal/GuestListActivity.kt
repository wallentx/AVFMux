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

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar

/**
 * Lists the guests this app can see.
 *
 * Launchable guests start a terminal session through the existing [VmLauncherService] lifecycle.
 * Guests that were detected but cannot be launched are still listed, with the reason shown, so
 * the limitation is visible rather than silently hidden.
 */
class GuestListActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.guest_list)

        val registry = GuestRegistry(this)
        val rows = registry.list().map { guest ->
            val installed = registry.isInstalled(guest)
            GuestRow(
                guest = guest,
                launchable = GuestRegistry.isLaunchable(guest, installed = installed),
                detail =
                    when {
                        guest.unavailableReason != null -> guest.unavailableReason
                        !installed -> getString(R.string.guest_not_installed_sub_title)
                        else -> getString(R.string.guest_installed_sub_title)
                    },
            )
        }

        val recyclerView: RecyclerView = findViewById(R.id.guest_list_recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = GuestListAdapter(this, rows)

        val toolbar: MaterialToolbar = findViewById(R.id.guest_list_toolbar)
        toolbar.setNavigationOnClickListener { finish() }
    }
}

/** One row of the guest list: a guest definition plus its launchability. */
data class GuestRow(
    val guest: Guest,
    /** True when selecting this guest can start a VM. */
    val launchable: Boolean,
    /** Human-readable status shown under the guest name. */
    val detail: String,
)
