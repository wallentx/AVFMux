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
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

/**
 * Adapter for [GuestListActivity].
 *
 * Selecting a launchable guest reuses the existing VM lifecycle: it starts [MainActivity] with
 * the guest id, and that activity starts [VmLauncherService] exactly as it did before.
 *
 * Rows that are external or not installed are still tappable, but they never start
 * [MainActivity]. Tapping one explains why it cannot launch and returns, so a guest whose
 * artifacts live outside this app cannot leak into the launch path.
 */
class GuestListAdapter(private val context: Context, private val rows: List<GuestRow>) :
    RecyclerView.Adapter<GuestListAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.guest_list_item_card)
        val title: TextView = view.findViewById(R.id.guest_list_item_title)
        val subTitle: TextView = view.findViewById(R.id.guest_list_item_sub_title)
    }

    override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder {
        val view =
            LayoutInflater.from(viewGroup.context)
                .inflate(R.layout.guest_list_item, viewGroup, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        val row = rows[position]
        viewHolder.title.text = row.guest.displayName
        viewHolder.subTitle.text = row.detail
        // Unavailable guests stay visible and remain tappable only to explain why they cannot
        // launch; the launch path below is gated on row.launchable.
        viewHolder.card.isClickable = true
        viewHolder.card.isEnabled = true
        viewHolder.card.setOnClickListener {
            if (!row.launchable) {
                val reason = row.guest.unavailableReason ?: row.detail
                Toast.makeText(context, reason, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val intent = Intent(context, MainActivity::class.java)
            intent.putExtra(MainActivity.EXTRA_GUEST_ID, row.guest.id)
            context.startActivity(intent)
        }
    }

    override fun getItemCount() = rows.size
}
