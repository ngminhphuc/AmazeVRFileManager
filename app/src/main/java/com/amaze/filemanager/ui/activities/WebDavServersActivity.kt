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
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.amaze.filemanager.R
import com.amaze.filemanager.filesystem.webdav.WebDavClient
import com.amaze.filemanager.filesystem.webdav.WebDavServer
import com.amaze.filemanager.filesystem.webdav.WebDavStorage
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * VR-friendly fullscreen list of configured WebDAV servers. Mirrors the
 * Sprint 3 [MediaServersActivity] / Sprint 7 [SmbServersActivity]
 * affordance: FAB to add, tap to browse, long-press to remove.
 */
class WebDavServersActivity : AppCompatActivity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var adapter: ServerAdapter
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_webdav_servers)
        setSupportActionBar(findViewById(R.id.webdav_servers_toolbar))
        supportActionBar?.title = getString(R.string.webdav_servers)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        emptyView = findViewById(R.id.webdav_servers_empty)
        adapter = ServerAdapter(::onServerClick, ::onServerLongClick)
        findViewById<RecyclerView>(R.id.webdav_servers_list).also {
            it.layoutManager = LinearLayoutManager(this)
            it.adapter = adapter
        }
        findViewById<FloatingActionButton>(R.id.webdav_servers_add).setOnClickListener {
            showAddDialog(null)
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
        val list = WebDavStorage.list(this)
        adapter.submit(list)
        emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onServerClick(server: WebDavServer) {
        startActivity(
            Intent(this, WebDavBrowserActivity::class.java)
                .putExtra(WebDavBrowserActivity.EXTRA_SERVER_ID, server.id),
        )
    }

    private fun onServerLongClick(server: WebDavServer) {
        AlertDialog.Builder(this)
            .setTitle(server.name)
            .setMessage(getString(R.string.webdav_servers_remove_prompt, server.name))
            .setPositiveButton(R.string.remove) { _, _ ->
                WebDavStorage.remove(this, server.id)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAddDialog(existing: WebDavServer?) {
        val view =
            LayoutInflater.from(this)
                .inflate(R.layout.dialog_add_webdav_server, null, false)
        val nameInput = view.findViewById<EditText>(R.id.add_webdav_name)
        val urlInput = view.findViewById<EditText>(R.id.add_webdav_url)
        val userInput = view.findViewById<EditText>(R.id.add_webdav_user)
        val passInput = view.findViewById<EditText>(R.id.add_webdav_pass)
        val anonymous = view.findViewById<CheckBox>(R.id.add_webdav_anonymous)
        val testButton = view.findViewById<Button>(R.id.add_webdav_test)

        existing?.also { e ->
            nameInput.setText(e.name)
            urlInput.setText(e.baseUrl)
            userInput.setText(e.username)
            passInput.setText(e.password)
            anonymous.isChecked = e.username.isBlank() && e.password.isBlank()
        }
        anonymous.setOnCheckedChangeListener { _, checked ->
            userInput.isEnabled = !checked
            passInput.isEnabled = !checked
            if (checked) {
                userInput.setText("")
                passInput.setText("")
            }
        }
        // Sync field-enabled state with initial checkbox state
        userInput.isEnabled = !anonymous.isChecked
        passInput.isEnabled = !anonymous.isChecked

        val dialog =
            AlertDialog.Builder(this)
                .setTitle(R.string.webdav_servers_add)
                .setView(view)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .show()

        testButton.setOnClickListener {
            val candidate =
                buildCandidate(existing?.id, nameInput, urlInput, userInput, passInput, anonymous)
                    ?: return@setOnClickListener
            Toast.makeText(this, R.string.webdav_testing, Toast.LENGTH_SHORT).show()
            executor.execute {
                val result = runCatching { WebDavClient.probe(candidate) }
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    result.onSuccess {
                        Toast.makeText(this, R.string.webdav_test_ok, Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(
                            this,
                            getString(R.string.webdav_test_failed, it.message ?: "?"),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val candidate =
                buildCandidate(existing?.id, nameInput, urlInput, userInput, passInput, anonymous)
                    ?: return@setOnClickListener
            WebDavStorage.upsert(this, candidate)
            refresh()
            dialog.dismiss()
        }
    }

    private fun buildCandidate(
        existingId: String?,
        name: EditText,
        url: EditText,
        user: EditText,
        pass: EditText,
        anonymous: CheckBox,
    ): WebDavServer? {
        val n = name.text.toString().trim()
        val u = url.text.toString().trim()
        if (n.isEmpty() || u.isEmpty()) {
            Toast.makeText(this, R.string.webdav_fill_required, Toast.LENGTH_SHORT).show()
            return null
        }
        if (!u.startsWith("https://") && !u.startsWith("http://")) {
            Toast.makeText(this, R.string.webdav_invalid_url, Toast.LENGTH_SHORT).show()
            return null
        }
        val isAnon = anonymous.isChecked
        return WebDavServer(
            id = existingId ?: ("webdav-" + UUID.randomUUID().toString()),
            name = n,
            baseUrl = WebDavStorage.normaliseBaseUrl(u),
            username = if (isAnon) "" else user.text.toString().trim(),
            password = if (isAnon) "" else pass.text.toString(),
        )
    }

    private class ServerAdapter(
        val onClick: (WebDavServer) -> Unit,
        val onLongClick: (WebDavServer) -> Unit,
    ) : RecyclerView.Adapter<ServerVH>() {
        private val items = mutableListOf<WebDavServer>()

        fun submit(list: List<WebDavServer>) {
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
                    .inflate(R.layout.item_webdav_server, parent, false)
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
        private val title = view.findViewById<TextView>(R.id.webdav_item_title)
        private val sub = view.findViewById<TextView>(R.id.webdav_item_sub)

        fun bind(s: WebDavServer) {
            title.text = s.name
            // Strip credentials from displayed URL — never echo basic-auth
            // userinfo back into the UI, even though we do not currently
            // store userinfo URLs (defence in depth).
            sub.text = s.baseUrl.replace(Regex("://[^@/]+@"), "://")
        }
    }
}
