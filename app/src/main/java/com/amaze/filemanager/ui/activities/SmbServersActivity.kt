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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.amaze.filemanager.R
import com.amaze.filemanager.application.AppConfig
import com.amaze.filemanager.database.UtilsHandler
import com.amaze.filemanager.database.models.OperationData
import com.amaze.filemanager.ui.activities.superclasses.ThemedActivity
import com.amaze.filemanager.ui.dialogs.SmbConnectDialog
import com.amaze.filemanager.utils.BookSorter
import com.amaze.filemanager.utils.ComputerParcelable
import com.amaze.filemanager.utils.DataUtils
import com.amaze.filemanager.utils.smb.SmbDeviceScannerObservable
import com.amaze.filemanager.utils.smb.SmbUtil
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.reactivex.Observer
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import jcifs.smb.SmbFile
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.concurrent.Executors

/**
 * VR-friendly fullscreen SMB server manager. Provides:
 *  * Scrollable list of saved SMB connections (read from [UtilsHandler.getSmbList]).
 *  * Inline LAN host discovery via [SmbDeviceScannerObservable] (NSD + WSDD + subnet).
 *  * FAB launches the existing [SmbConnectDialog] for manual entry.
 *  * Long-press on a saved server exposes Browse / Edit / Test connection /
 *    Copy URL / Delete actions.
 *  * Tapping a discovered host pre-fills the connect dialog.
 *
 * The activity is a [SmbConnectDialog.SmbConnectionListener] so adds and edits
 * persist directly via [UtilsHandler] without round-tripping through
 * [MainActivity]. The drawer in any open MainActivity instance is refreshed by
 * starting MainActivity with `path = encryptedSmbPath` when the user taps
 * "Browse", which routes through MainActivity's onNewIntent SMB shortcut.
 */
class SmbServersActivity :
    ThemedActivity(),
    SmbConnectDialog.SmbConnectionListener {
    private val log = LoggerFactory.getLogger(SmbServersActivity::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private val dataUtils: DataUtils = DataUtils.getInstance()
    private lateinit var savedAdapter: SavedAdapter
    private lateinit var savedEmpty: TextView
    private lateinit var scanAdapter: ScanAdapter
    private lateinit var scanEmpty: TextView
    private lateinit var scanButton: Button
    private lateinit var scanProgress: ProgressBar
    private var scanDisposable: Disposable? = null
    private val discovered = LinkedHashSet<ComputerParcelable>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_smb_servers)
        setSupportActionBar(findViewById<Toolbar>(R.id.smb_servers_toolbar))
        supportActionBar?.title = getString(R.string.smb_servers)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        savedEmpty = findViewById(R.id.smb_servers_saved_empty)
        savedAdapter = SavedAdapter(::onSavedClick, ::onSavedLongClick)
        findViewById<RecyclerView>(R.id.smb_servers_saved_list).apply {
            layoutManager = LinearLayoutManager(this@SmbServersActivity)
            adapter = savedAdapter
        }

        scanEmpty = findViewById(R.id.smb_servers_scan_empty)
        scanProgress = findViewById(R.id.smb_servers_scan_progress)
        scanButton = findViewById(R.id.smb_servers_scan_button)
        scanAdapter = ScanAdapter(::onDiscoveredClick)
        findViewById<RecyclerView>(R.id.smb_servers_scan_list).apply {
            layoutManager = LinearLayoutManager(this@SmbServersActivity)
            adapter = scanAdapter
        }
        scanButton.setOnClickListener { toggleScan() }

        findViewById<FloatingActionButton>(R.id.smb_servers_add).setOnClickListener {
            showConnectDialog(name = "", path = "", edit = false)
        }
        refreshSavedList()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        scanDisposable?.dispose()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun refreshSavedList() {
        val handler = AppConfig.getInstance().utilsHandler
        val list = handler.smbList
        savedAdapter.submit(list)
        savedEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------
    // Saved server interactions
    // ------------------------------------------------------------------

    private fun onSavedClick(entry: Array<String>) {
        // Browse: fire the encrypted SMB path back into MainActivity, which
        // already knows how to navigate via mainFragment.loadlist().
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra("path", entry[1])
                addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
            },
        )
    }

    private fun onSavedLongClick(entry: Array<String>) {
        val items =
            arrayOf(
                getString(R.string.smb_servers_action_browse),
                getString(R.string.smb_servers_action_edit),
                getString(R.string.smb_servers_action_test),
                getString(R.string.smb_servers_action_copy_url),
                getString(R.string.smb_servers_action_delete),
            )
        AlertDialog.Builder(this)
            .setTitle(entry[0])
            .setItems(items) { _, which ->
                when (which) {
                    0 -> onSavedClick(entry)
                    1 -> showConnectDialog(name = entry[0], path = entry[1], edit = true)
                    2 -> testConnection(entry[1])
                    3 -> copyUrlToClipboard(entry[0], entry[1])
                    4 -> confirmDelete(entry)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(entry: Array<String>) {
        AlertDialog.Builder(this)
            .setTitle(entry[0])
            .setMessage(getString(R.string.smb_servers_delete_prompt, entry[0]))
            .setPositiveButton(R.string.delete) { _, _ ->
                deleteConnection(entry[0], entry[1])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun copyUrlToClipboard(
        name: String,
        encryptedPath: String,
    ) {
        // Strip credentials from URL before writing to clipboard — the
        // encrypted password isn't useful to the user and could be pasted
        // somewhere unsafe.
        val sanitized = stripUserInfo(encryptedPath)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(name, sanitized))
        Toast.makeText(this, R.string.smb_servers_url_copied, Toast.LENGTH_SHORT).show()
    }

    private fun testConnection(encryptedPath: String) {
        Toast.makeText(this, R.string.smb_servers_test_connecting, Toast.LENGTH_SHORT).show()
        executor.execute {
            val result =
                runCatching {
                    val smbFile: SmbFile = SmbUtil.create(encryptedPath)
                    // exists() walks the SMB session/auth path; its boolean
                    // return is irrelevant — we only care that it doesn't
                    // throw. A successful exists() proves credentials are
                    // valid even for hosts whose root share denies LIST.
                    smbFile.exists()
                }
            runOnUiThread {
                result
                    .onSuccess {
                        Toast.makeText(
                            this,
                            R.string.smb_servers_test_ok,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    .onFailure {
                        log.warn("SMB test connection failed", it)
                        Toast.makeText(
                            this,
                            getString(
                                R.string.smb_servers_test_failed,
                                it.message ?: it.javaClass.simpleName,
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
            }
        }
    }

    // ------------------------------------------------------------------
    // LAN scan
    // ------------------------------------------------------------------

    private fun toggleScan() {
        if (scanDisposable?.isDisposed == false) {
            stopScan()
        } else {
            startScan()
        }
    }

    private fun startScan() {
        discovered.clear()
        scanAdapter.submit(emptyList())
        scanEmpty.setText(R.string.smb_servers_scan_in_progress)
        scanEmpty.visibility = View.VISIBLE
        scanProgress.visibility = View.VISIBLE
        scanButton.setText(R.string.smb_servers_scan_stop)

        SmbDeviceScannerObservable()
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                object : Observer<ComputerParcelable> {
                    override fun onSubscribe(d: Disposable) {
                        scanDisposable = d
                    }

                    override fun onNext(t: ComputerParcelable) {
                        if (discovered.add(t)) {
                            scanAdapter.submit(discovered.toList())
                            scanEmpty.visibility = View.GONE
                        }
                    }

                    override fun onError(e: Throwable) {
                        log.warn("SMB scan failed", e)
                        finishScan(R.string.smb_servers_scan_error)
                    }

                    override fun onComplete() {
                        finishScan(
                            if (discovered.isEmpty()) {
                                R.string.smb_servers_scan_empty
                            } else {
                                null
                            },
                        )
                    }
                },
            )
    }

    private fun stopScan() {
        scanDisposable?.dispose()
        finishScan(
            if (discovered.isEmpty()) {
                R.string.smb_servers_scan_empty
            } else {
                null
            },
        )
    }

    private fun finishScan(emptyMsg: Int?) {
        scanDisposable = null
        scanProgress.visibility = View.GONE
        scanButton.setText(R.string.smb_servers_scan_start)
        if (emptyMsg != null) {
            scanEmpty.setText(emptyMsg)
            scanEmpty.visibility = View.VISIBLE
        } else {
            scanEmpty.visibility = View.GONE
        }
    }

    private fun onDiscoveredClick(host: ComputerParcelable) {
        // Pre-fill the dialog with the discovered IP. The dialog interprets
        // an `ARG_PATH` without a username as "host only" and seeds the IP
        // field, leaving the user to type credentials.
        val seedIp = host.addr.removePrefix("/")
        showConnectDialog(name = host.name.ifBlank { seedIp }, path = seedIp, edit = false)
    }

    // ------------------------------------------------------------------
    // SmbConnectionListener — persists adds/edits/deletes
    // ------------------------------------------------------------------

    override fun addConnection(
        edit: Boolean,
        name: String,
        encryptedPath: String,
        oldname: String?,
        oldPath: String?,
    ) {
        val handler = AppConfig.getInstance().utilsHandler
        val newEntry = arrayOf(name, encryptedPath)
        if (!edit) {
            if (dataUtils.containsServer(encryptedPath) != -1) {
                Toast.makeText(this, R.string.connection_exists, Toast.LENGTH_SHORT).show()
                return
            }
            handler
                .saveToDatabase(OperationData(UtilsHandler.Operation.SMB, name, encryptedPath))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    dataUtils.addServer(newEntry)
                    Collections.sort(dataUtils.servers, BookSorter())
                    refreshSavedList()
                }
        } else {
            // Edit path: dialog always supplies oldname/oldPath when edit=true,
            // but the listener interface declares them nullable so we guard.
            val safeOldName = oldname ?: name
            val safeOldPath = oldPath ?: encryptedPath
            val idx = dataUtils.containsServer(arrayOf(safeOldName, safeOldPath))
            if (idx != -1) dataUtils.removeServer(idx)
            executor.execute {
                handler.renameSMB(safeOldName, safeOldPath, name, encryptedPath)
                runOnUiThread {
                    dataUtils.addServer(newEntry)
                    Collections.sort(dataUtils.servers, BookSorter())
                    refreshSavedList()
                }
            }
        }
    }

    override fun deleteConnection(
        name: String,
        path: String,
    ) {
        val handler = AppConfig.getInstance().utilsHandler
        val idx = dataUtils.containsServer(arrayOf(name, path))
        if (idx != -1) dataUtils.removeServer(idx)
        executor.execute {
            handler.removeFromDatabase(OperationData(UtilsHandler.Operation.SMB, name, path))
            runOnUiThread { refreshSavedList() }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun showConnectDialog(
        name: String,
        path: String,
        edit: Boolean,
    ) {
        val dialog = SmbConnectDialog()
        val args = Bundle()
        args.putString(SmbConnectDialog.ARG_NAME, name)
        args.putString(SmbConnectDialog.ARG_PATH, path)
        args.putBoolean(SmbConnectDialog.ARG_EDIT, edit)
        dialog.arguments = args
        dialog.setSmbConnectionListener(this)
        dialog.show(supportFragmentManager, SmbConnectDialog.TAG)
    }

    private fun stripUserInfo(url: String): String {
        return runCatching {
            val u = Uri.parse(url)
            val authority =
                buildString {
                    if (u.host != null) append(u.host)
                    if (u.port > 0) append(':').append(u.port)
                }
            val sb = StringBuilder()
            if (u.scheme != null) sb.append(u.scheme).append("://")
            sb.append(authority)
            if (u.path != null) sb.append(u.path)
            sb.toString()
        }.getOrDefault(url)
    }

    // ------------------------------------------------------------------
    // Adapters
    // ------------------------------------------------------------------

    private class SavedAdapter(
        val onClick: (Array<String>) -> Unit,
        val onLongClick: (Array<String>) -> Unit,
    ) : RecyclerView.Adapter<ServerVH>() {
        private val items = mutableListOf<Array<String>>()

        fun submit(list: List<Array<String>>) {
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
                    .inflate(R.layout.item_smb_server, parent, false)
            return ServerVH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(
            holder: ServerVH,
            position: Int,
        ) {
            val item = items[position]
            holder.title.text = item[0]
            // Show a sanitized version of the URL — host + share, no creds.
            holder.sub.text =
                runCatching {
                    val u = Uri.parse(item[1])
                    val host = u.host ?: item[1]
                    if (u.path.isNullOrEmpty() || u.path == "/") {
                        host
                    } else {
                        host + u.path
                    }
                }.getOrDefault(item[1])
            holder.itemView.setOnClickListener { onClick(item) }
            holder.itemView.setOnLongClickListener {
                onLongClick(item)
                true
            }
        }
    }

    private class ScanAdapter(val onClick: (ComputerParcelable) -> Unit) :
        RecyclerView.Adapter<ServerVH>() {
        private val items = mutableListOf<ComputerParcelable>()

        fun submit(list: List<ComputerParcelable>) {
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
                    .inflate(R.layout.item_smb_server, parent, false)
            return ServerVH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(
            holder: ServerVH,
            position: Int,
        ) {
            val item = items[position]
            holder.title.text = item.name.ifBlank { item.addr.removePrefix("/") }
            holder.sub.text = item.addr.removePrefix("/")
            holder.itemView.setOnClickListener { onClick(item) }
            holder.itemView.setOnLongClickListener(null)
        }
    }

    private class ServerVH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.smb_server_item_title)
        val sub: TextView = view.findViewById(R.id.smb_server_item_sub)
    }
}
