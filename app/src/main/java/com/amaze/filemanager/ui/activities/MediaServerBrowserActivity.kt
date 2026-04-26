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

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.amaze.filemanager.R
import com.amaze.filemanager.filesystem.mediaserver.MediaItem
import com.amaze.filemanager.filesystem.mediaserver.MediaServer
import com.amaze.filemanager.filesystem.mediaserver.MediaServerClient
import com.amaze.filemanager.filesystem.mediaserver.MediaServerStorage
import java.util.concurrent.Executors

/**
 * Browses libraries / folders / videos on a single media server. Launched
 * with [EXTRA_SERVER_ID] (mandatory) and optionally [EXTRA_PARENT_ID] +
 * [EXTRA_TITLE] when navigating into a sub-folder; otherwise lists root
 * libraries.
 *
 * Tapping a folder pushes a new instance of this activity. Tapping a video
 * launches [VrVideoPlayerActivity] with the server-issued stream URL.
 */
class MediaServerBrowserActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var adapter: ItemAdapter
    private lateinit var loading: ProgressBar
    private lateinit var emptyView: TextView
    private lateinit var server: MediaServer
    private var parentId: String? = null

    companion object {
        const val EXTRA_SERVER_ID = "server_id"
        const val EXTRA_PARENT_ID = "parent_id"
        const val EXTRA_TITLE = "title"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_media_server_browser)
        setSupportActionBar(findViewById(R.id.media_server_browser_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val serverId = intent.getStringExtra(EXTRA_SERVER_ID)
        val matched = serverId?.let { id -> MediaServerStorage.list(this).firstOrNull { it.id == id } }
        if (matched == null) {
            Toast.makeText(this, R.string.media_servers_unknown, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        server = matched
        parentId = intent.getStringExtra(EXTRA_PARENT_ID)
        supportActionBar?.title = intent.getStringExtra(EXTRA_TITLE) ?: server.name

        loading = findViewById(R.id.media_server_browser_loading)
        emptyView = findViewById(R.id.media_server_browser_empty)
        adapter = ItemAdapter(::onItemClick)
        findViewById<RecyclerView>(R.id.media_server_browser_list).also {
            it.layoutManager = LinearLayoutManager(this)
            it.adapter = adapter
        }
        load()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun load() {
        loading.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        executor.execute {
            val result =
                runCatching {
                    if (parentId == null) {
                        MediaServerClient.listLibraries(server)
                    } else {
                        MediaServerClient.listChildren(server, parentId!!)
                    }
                }
            runOnUiThread {
                loading.visibility = View.GONE
                result.onSuccess { items ->
                    adapter.submit(items)
                    emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
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

    private fun onItemClick(item: MediaItem) {
        when {
            item.isFolder -> {
                startActivity(
                    Intent(this, MediaServerBrowserActivity::class.java)
                        .putExtra(EXTRA_SERVER_ID, server.id)
                        .putExtra(EXTRA_PARENT_ID, item.id)
                        .putExtra(EXTRA_TITLE, item.name),
                )
            }
            item.isVideo && item.mediaUrl != null -> {
                startActivity(
                    Intent(this, VrVideoPlayerActivity::class.java)
                        .setData(Uri.parse(item.mediaUrl)),
                )
            }
            else -> {
                Toast.makeText(this, R.string.media_servers_unsupported, Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    private class ItemAdapter(val onClick: (MediaItem) -> Unit) :
        RecyclerView.Adapter<ItemVH>() {
        private val items = mutableListOf<MediaItem>()

        fun submit(list: List<MediaItem>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): ItemVH {
            val v =
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_media_server_entry, parent, false)
            return ItemVH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(
            holder: ItemVH,
            position: Int,
        ) {
            val item = items[position]
            holder.bind(item)
            holder.itemView.setOnClickListener { onClick(item) }
        }
    }

    private class ItemVH(view: View) : RecyclerView.ViewHolder(view) {
        private val icon = view.findViewById<android.widget.ImageView>(R.id.media_item_icon)
        private val title = view.findViewById<TextView>(R.id.media_item_title)
        private val sub = view.findViewById<TextView>(R.id.media_item_sub)

        fun bind(item: MediaItem) {
            title.text = item.name
            icon.setImageResource(
                when {
                    item.isFolder -> R.drawable.ic_folder_white_24dp
                    item.isVideo -> R.drawable.ic_video_library_white_24dp
                    else -> R.drawable.ic_settings_remote_white_24dp
                },
            )
            sub.text = item.durationMillis?.let { ms ->
                val totalSeconds = ms / 1000
                "%d:%02d:%02d".format(totalSeconds / 3600, (totalSeconds / 60) % 60, totalSeconds % 60)
            } ?: ""
            sub.visibility = if (sub.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
    }
}
