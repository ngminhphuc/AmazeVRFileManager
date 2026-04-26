/*
 * Copyright (C) 2014-2026 Arpit Khurana <arpitkh96@gmail.com>, Vishal Nehra <vishalmeham2@gmail.com>,
 * Emmanuel Messulam<emmanuelbendavid@gmail.com>, Raymond Lai <airwave209gt at gmail.com> and Contributors.
 *
 * This file is part of Amaze File Manager.
 *
 * Amaze File Manager is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.amaze.filemanager.ui.activities

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.amaze.filemanager.R
import com.amaze.filemanager.filesystem.mediaserver.MediaServer
import com.amaze.filemanager.filesystem.mediaserver.MediaServerClient
import com.amaze.filemanager.filesystem.mediaserver.MediaServerStorage
import com.amaze.filemanager.filesystem.mediaserver.MediaServerType
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.util.concurrent.Executors

/**
 * List of configured Jellyfin / Emby / Plex servers. FAB launches the add
 * dialog; tapping a row opens the server's libraries in
 * [MediaServerBrowserActivity]; long-press deletes.
 */
class MediaServersActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var adapter: ServerAdapter
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_media_servers)
        setSupportActionBar(findViewById(R.id.media_servers_toolbar))
        supportActionBar?.title = getString(R.string.media_servers)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        emptyView = findViewById(R.id.media_servers_empty)
        adapter = ServerAdapter(::onServerClick, ::onServerLongClick)
        findViewById<RecyclerView>(R.id.media_servers_list).also {
            it.layoutManager = LinearLayoutManager(this)
            it.adapter = adapter
        }
        findViewById<FloatingActionButton>(R.id.media_servers_add).setOnClickListener {
            showAddDialog()
        }
        refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun refresh() {
        val list = MediaServerStorage.list(this)
        adapter.submit(list)
        emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onServerClick(server: MediaServer) {
        startActivity(
            Intent(this, MediaServerBrowserActivity::class.java)
                .putExtra(MediaServerBrowserActivity.EXTRA_SERVER_ID, server.id),
        )
    }

    private fun onServerLongClick(server: MediaServer) {
        AlertDialog.Builder(this)
            .setTitle(server.name)
            .setMessage(getString(R.string.media_servers_remove_prompt, server.name))
            .setPositiveButton(R.string.remove) { _, _ ->
                MediaServerStorage.remove(this, server.id)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAddDialog() {
        val view =
            LayoutInflater.from(this)
                .inflate(R.layout.dialog_add_media_server, null, false)
        val nameInput = view.findViewById<EditText>(R.id.add_media_server_name)
        val urlInput = view.findViewById<EditText>(R.id.add_media_server_url)
        val userInput = view.findViewById<EditText>(R.id.add_media_server_user)
        val passInput = view.findViewById<EditText>(R.id.add_media_server_pass)
        val typeSpinner = view.findViewById<Spinner>(R.id.add_media_server_type)
        typeSpinner.adapter =
            ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Jellyfin", "Emby", "Plex"),
            )
        passInput.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

        val dialog =
            AlertDialog.Builder(this)
                .setTitle(R.string.media_servers_add)
                .setView(view)
                .setPositiveButton(R.string.media_servers_connect, null)
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = nameInput.text.toString().trim()
            val url = urlInput.text.toString().trim()
            val user = userInput.text.toString().trim()
            val pass = passInput.text.toString()
            val type = MediaServerType.values()[typeSpinner.selectedItemPosition]
            if (name.isEmpty() || url.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, R.string.media_servers_fill_all, Toast.LENGTH_SHORT)
                    .show()
                return@setOnClickListener
            }
            authenticate(type, url, user, pass, name)
            dialog.dismiss()
        }
    }

    private fun authenticate(
        type: MediaServerType,
        url: String,
        user: String,
        pass: String,
        name: String,
    ) {
        Toast.makeText(this, R.string.media_servers_connecting, Toast.LENGTH_SHORT).show()
        executor.execute {
            val result =
                runCatching {
                    MediaServerClient.authenticate(type, url, user, pass, name)
                }
            runOnUiThread {
                result.onSuccess {
                    MediaServerStorage.upsert(this, it)
                    refresh()
                    Toast.makeText(
                        this,
                        getString(R.string.media_servers_connected, it.name),
                        Toast.LENGTH_SHORT,
                    ).show()
                }.onFailure {
                    Toast.makeText(
                        this,
                        getString(R.string.media_servers_failed, it.message),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private class ServerAdapter(
        val onClick: (MediaServer) -> Unit,
        val onLongClick: (MediaServer) -> Unit,
    ) : RecyclerView.Adapter<ServerVH>() {
        private val items = mutableListOf<MediaServer>()

        fun submit(list: List<MediaServer>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): ServerVH {
            val v =
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_media_server, parent, false)
            return ServerVH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(
            holder: ServerVH,
            position: Int,
        ) {
            val item = items[position]
            holder.bind(item)
            holder.itemView.setOnClickListener { onClick(item) }
            holder.itemView.setOnLongClickListener {
                onLongClick(item)
                true
            }
        }
    }

    private class ServerVH(view: View) : RecyclerView.ViewHolder(view) {
        private val title = view.findViewById<TextView>(R.id.media_server_item_title)
        private val sub = view.findViewById<TextView>(R.id.media_server_item_sub)

        fun bind(s: MediaServer) {
            title.text = s.name
            sub.text = "${s.type.name} • ${s.url}"
        }
    }
}
