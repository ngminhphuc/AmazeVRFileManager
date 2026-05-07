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

import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.WindowCompat
import com.amaze.filemanager.R
import com.amaze.filemanager.filesystem.dropzone.DropZoneHttpServer
import com.amaze.filemanager.filesystem.dropzone.QrCodeRenderer
import com.amaze.filemanager.utils.NetworkUtil
import org.slf4j.LoggerFactory
import java.io.File
import java.security.SecureRandom

/**
 * Sprint 14: cross-device drop-zone UI (Phase 2.9).
 *
 * Shows the device's Wi-Fi URL + a QR code containing it. The phone
 * scans the QR (or types the URL) and uses the rendered HTML form on
 * the landing page to upload files; the server writes them to
 * `/sdcard/Download/AmazeDrop/`.
 *
 * The HTTP server is bound to this Activity's lifecycle: it starts in
 * [onResume] and stops in [onPause]. We deliberately don't use a
 * foreground service for Sprint 14 — drop-zone is a "while you watch"
 * tool (user opens it, copies the URL to phone, drops files, closes).
 * Background-running drop-zone with a notification is a follow-up.
 */
class DropZoneActivity : AppCompatActivity() {
    companion object {
        private val LOG = LoggerFactory.getLogger(DropZoneActivity::class.java)
        private const val PIN_DIGITS = 6
        private const val QR_SIZE_PX = 768
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var server: DropZoneHttpServer? = null
    private var pin: String = ""
    private val downloadDir: File by lazy {
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AmazeDrop")
    }

    private lateinit var urlText: TextView
    private lateinit var pinText: TextView
    private lateinit var statusText: TextView
    private lateinit var countText: TextView
    private lateinit var qrImage: ImageView
    private lateinit var startStopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_drop_zone)
        setSupportActionBar(findViewById<Toolbar>(R.id.drop_zone_toolbar))
        supportActionBar?.title = getString(R.string.drop_zone_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        urlText = findViewById(R.id.drop_zone_url)
        pinText = findViewById(R.id.drop_zone_pin)
        statusText = findViewById(R.id.drop_zone_status)
        countText = findViewById(R.id.drop_zone_count)
        qrImage = findViewById(R.id.drop_zone_qr)
        startStopButton = findViewById(R.id.drop_zone_toggle)
        urlText.movementMethod = LinkMovementMethod.getInstance()
        startStopButton.setOnClickListener { toggle() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        if (server == null) startServer()
    }

    override fun onPause() {
        super.onPause()
        stopServer()
    }

    private fun toggle() {
        if (server != null) {
            stopServer()
        } else {
            startServer()
        }
    }

    private fun startServer() {
        val ip = NetworkUtil.getLocalInetAddress(this)?.hostAddress
        if (ip == null) {
            statusText.text = getString(R.string.drop_zone_status_no_wifi)
            urlText.text = ""
            qrImage.setImageDrawable(null)
            startStopButton.text = getString(R.string.drop_zone_start)
            return
        }
        pin = newPin()
        val s = DropZoneHttpServer(downloadDir, pin)
        s.listener =
            object : DropZoneHttpServer.Listener {
                override fun onUploadComplete(
                    file: File,
                    sizeBytes: Long,
                ) {
                    mainHandler.post {
                        countText.text =
                            getString(
                                R.string.drop_zone_count_format,
                                server?.uploadCount() ?: 0L,
                                downloadDir.absolutePath,
                            )
                        Toast.makeText(
                            this@DropZoneActivity,
                            getString(R.string.drop_zone_received, file.name),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }

                override fun onUploadFailed(
                    filename: String?,
                    reason: String,
                ) {
                    LOG.warn("drop-zone upload failed: $filename — $reason")
                }
            }
        try {
            s.start()
        } catch (e: Exception) {
            LOG.warn("drop-zone failed to start", e)
            statusText.text = getString(R.string.drop_zone_status_failed, e.message ?: "?")
            return
        }
        server = s
        val url = "http://$ip:${DropZoneHttpServer.DEFAULT_PORT}/"
        urlText.text = url
        pinText.text = getString(R.string.drop_zone_pin_label, pin)
        statusText.text = getString(R.string.drop_zone_status_running)
        countText.text =
            getString(R.string.drop_zone_count_format, 0L, downloadDir.absolutePath)
        startStopButton.text = getString(R.string.drop_zone_stop)
        qrImage.visibility = View.VISIBLE
        qrImage.setImageBitmap(QrCodeRenderer.render(url, QR_SIZE_PX))
    }

    private fun stopServer() {
        server?.stop()
        server = null
        statusText.text = getString(R.string.drop_zone_status_stopped)
        urlText.text = ""
        pinText.text = ""
        qrImage.setImageDrawable(null)
        qrImage.visibility = View.GONE
        startStopButton.text = getString(R.string.drop_zone_start)
    }

    private fun newPin(): String {
        val rng = SecureRandom()
        val sb = StringBuilder(PIN_DIGITS)
        repeat(PIN_DIGITS) { sb.append(rng.nextInt(10)) }
        return sb.toString()
    }
}
