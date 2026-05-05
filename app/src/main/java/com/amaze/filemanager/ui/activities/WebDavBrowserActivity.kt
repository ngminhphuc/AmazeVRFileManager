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
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.amaze.filemanager.R
import com.amaze.filemanager.filesystem.webdav.WebDavClient
import com.amaze.filemanager.filesystem.webdav.WebDavEntry
import com.amaze.filemanager.filesystem.webdav.WebDavServer
import com.amaze.filemanager.filesystem.webdav.WebDavStorage
import com.amaze.filemanager.ui.icons.MimeTypes
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Browses one collection on a configured WebDAV server. A new instance of
 * this activity is pushed for every sub-folder so the system back stack
 * acts as the directory navigator.
 *
 * Tap behaviour:
 *   * folder → push a new instance pointed at the sub-collection
 *   * video file → stream via [VrVideoPlayerActivity], passing the
 *     `Authorization` header through the new
 *     [VrVideoPlayerActivity.EXTRA_HTTP_AUTH_HEADER] extra so the user's
 *     credentials never appear in the URL
 *   * other file → toast "download then open" (out-of-scope for Sprint 12)
 */
class WebDavBrowserActivity : AppCompatActivity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var adapter: EntryAdapter
    private lateinit var loading: ProgressBar
    private lateinit var emptyView: TextView
    private lateinit var server: WebDavServer
    private lateinit var collectionUrl: String

    companion object {
        const val EXTRA_SERVER_ID: String = "webdav_server_id"
        const val EXTRA_COLLECTION_URL: String = "webdav_collection_url"
        const val EXTRA_TITLE: String = "webdav_title"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_webdav_browser)
        setSupportActionBar(findViewById(R.id.webdav_browser_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val serverId = intent.getStringExtra(EXTRA_SERVER_ID)
        val matched = serverId?.let { id -> WebDavStorage.list(this).firstOrNull { it.id == id } }
        if (matched == null) {
            Toast.makeText(this, R.string.webdav_unknown_server, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        server = matched
        collectionUrl = intent.getStringExtra(EXTRA_COLLECTION_URL) ?: server.baseUrl
        supportActionBar?.title = intent.getStringExtra(EXTRA_TITLE) ?: server.name

        loading = findViewById(R.id.webdav_browser_loading)
        emptyView = findViewById(R.id.webdav_browser_empty)
        adapter = EntryAdapter(::onEntryClick)
        findViewById<RecyclerView>(R.id.webdav_browser_list).also {
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
            val result = runCatching { WebDavClient.listChildren(server, collectionUrl) }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                loading.visibility = View.GONE
                result.onSuccess { entries ->
                    adapter.submit(entries)
                    emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
                }.onFailure {
                    Toast.makeText(
                        this,
                        getString(R.string.webdav_load_failed, it.message ?: "?"),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun onEntryClick(entry: WebDavEntry) {
        when {
            entry.isDirectory -> {
                startActivity(
                    Intent(this, WebDavBrowserActivity::class.java)
                        .putExtra(EXTRA_SERVER_ID, server.id)
                        .putExtra(EXTRA_COLLECTION_URL, entry.href)
                        .putExtra(EXTRA_TITLE, entry.displayName),
                )
            }
            isVideo(entry) -> {
                val intent =
                    Intent(this, VrVideoPlayerActivity::class.java)
                        .setData(Uri.parse(entry.href))
                WebDavClient.basicAuthHeader(server)?.let {
                    intent.putExtra(VrVideoPlayerActivity.EXTRA_HTTP_AUTH_HEADER, it)
                }
                startActivity(intent)
            }
            else -> {
                Toast.makeText(this, R.string.webdav_unsupported_item, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isVideo(entry: WebDavEntry): Boolean {
        // Server-supplied content type wins when present and informative.
        // Generic `application/octet-stream` (very common on misconfigured
        // Apache mod_dav and Synology DSM) is treated as "unknown" and we
        // fall through to the filename heuristic. Any other concrete MIME
        // type (`application/pdf`, `image/jpeg`, …) is trusted as-is.
        entry.contentType?.lowercase()?.takeIf { it.isNotBlank() }?.let { ct ->
            if (ct.startsWith("video/")) return true
            if (!ct.startsWith("application/octet-stream")) return false
            // octet-stream → fall through to filename heuristic below
        }
        val mime = MimeTypes.getMimeType(entry.displayName, false)
        return mime != null && mime.startsWith("video/")
    }

    private class EntryAdapter(val onClick: (WebDavEntry) -> Unit) :
        RecyclerView.Adapter<EntryVH>() {
        private val items = mutableListOf<WebDavEntry>()

        fun submit(list: List<WebDavEntry>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): EntryVH {
            val v =
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_webdav_entry, parent, false)
            return EntryVH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(
            holder: EntryVH,
            position: Int,
        ) {
            val item = items[position]
            holder.bind(item)
            holder.itemView.setOnClickListener { onClick(item) }
        }
    }

    private class EntryVH(view: View) : RecyclerView.ViewHolder(view) {
        private val icon = view.findViewById<ImageView>(R.id.webdav_entry_icon)
        private val title = view.findViewById<TextView>(R.id.webdav_entry_title)
        private val sub = view.findViewById<TextView>(R.id.webdav_entry_sub)

        fun bind(entry: WebDavEntry) {
            title.text = entry.displayName
            icon.setImageResource(
                when {
                    entry.isDirectory -> R.drawable.ic_folder_white_24dp
                    isVideoMime(entry) -> R.drawable.ic_video_library_white_24dp
                    else -> R.drawable.ic_settings_remote_white_24dp
                },
            )
            sub.text =
                if (entry.isDirectory) {
                    ""
                } else {
                    val sizeText =
                        if (entry.contentLength >= 0) {
                            humaniseBytes(entry.contentLength)
                        } else {
                            ""
                        }
                    val typeText = entry.contentType?.takeIf { it.isNotBlank() } ?: ""
                    listOf(sizeText, typeText).filter { it.isNotEmpty() }.joinToString(" • ")
                }
            sub.visibility = if (sub.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        private fun isVideoMime(entry: WebDavEntry): Boolean {
            val ct = entry.contentType?.lowercase() ?: return false
            return ct.startsWith("video/")
        }

        private fun humaniseBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val units = arrayOf("KB", "MB", "GB", "TB")
            var v = bytes.toDouble() / 1024
            var i = 0
            while (v >= 1024 && i < units.size - 1) {
                v /= 1024
                i++
            }
            return "%.1f %s".format(v, units[i])
        }
    }
}
