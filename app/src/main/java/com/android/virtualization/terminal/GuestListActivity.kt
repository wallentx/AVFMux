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
import android.os.Environment
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import java.io.File

/**
 * Lists the guests this app can see.
 *
 * Launchable guests start a terminal session through the existing [VmLauncherService] lifecycle.
 * Guests whose descriptor lives outside this app's storage are still listed with the exact reason
 * they cannot be launched, so the limitation is visible rather than silently hidden.
 */
class GuestListActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.guest_list)

        val registry = GuestRegistry(this, externalDescriptorRoot())
        val rows = registry.list().map { guest ->
            val installed = registry.isInstalled(guest)
            // Debian stays selectable even when uninstalled: selecting it runs upstream
            // installIfNecessary() -> InstallerActivity. Other guests need full launchability.
            val launchable = registry.isLaunchable(guest) || guest.id == Guest.DEBIAN_ID
            GuestRow(
                guest = guest,
                launchable = launchable,
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

    /**
     * App-visible location holding external `*.json` descriptors.
     *
     * This is the only place the Downloads path is resolved, keeping it out of the registry so the
     * registry logic stays JVM-testable. If external storage is unavailable, the registry returns
     * an empty external list and logs it.
     */
    private fun externalDescriptorRoot(): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            GuestRegistry.EXTERNAL_DESCRIPTOR_SUBDIR,
        )
}

/** One row of the guest list: a guest definition plus its launchability. */
data class GuestRow(
    val guest: Guest,
    /** True when selecting this guest can start a VM. */
    val launchable: Boolean,
    /** Human-readable status shown under the guest name. */
    val detail: String,
)
