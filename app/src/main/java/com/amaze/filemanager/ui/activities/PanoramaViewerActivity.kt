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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.amaze.filemanager.R
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.PI

/**
 * Inline panorama still-image viewer for equirectangular JPG / PNG sources.
 *
 * The image is uploaded as a GL texture and mapped onto the inside of a
 * UV-sphere by [PanoramaRenderer]. Touch drag pans (yaw / pitch) and
 * two-finger pinch zooms (field of view).
 *
 * Auto-detection of 360° vs 180° content is driven by filename heuristics in
 * [com.amaze.filemanager.filesystem.files.FileUtils] — users can override via
 * the overflow menu.
 */
class PanoramaViewerActivity : AppCompatActivity() {
    companion object {
        private val LOG = LoggerFactory.getLogger(PanoramaViewerActivity::class.java)

        /**
         * Optional intent extra: one of "EQUIRECT_360" or "EQUIRECT_180".
         * When absent, [PanoramaRenderer.Projection.EQUIRECT_360] is used.
         */
        const val EXTRA_PROJECTION = "panorama_projection"
    }

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var emptyView: TextView
    private lateinit var renderer: PanoramaRenderer
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var dragDetector: GestureDetector
    private var loadExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_panorama_viewer)
        setSupportActionBar(findViewById(R.id.panorama_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title =
            intent.data?.lastPathSegment ?: getString(R.string.panorama_viewer)

        glSurfaceView = findViewById(R.id.panorama_surface)
        emptyView = findViewById(R.id.panorama_empty)

        renderer = PanoramaRenderer()
        renderer.projection =
            runCatching {
                PanoramaRenderer.Projection.valueOf(
                    intent.getStringExtra(EXTRA_PROJECTION)
                        ?: PanoramaRenderer.Projection.EQUIRECT_360.name,
                )
            }.getOrDefault(PanoramaRenderer.Projection.EQUIRECT_360)

        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        scaleDetector =
            ScaleGestureDetector(
                this,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        // Inverse so pinch-out widens view (zooms out), pinch-in narrows (zooms in).
                        renderer.fovDeg =
                            (renderer.fovDeg / detector.scaleFactor).coerceIn(MIN_FOV, MAX_FOV)
                        return true
                    }
                },
            )

        dragDetector =
            GestureDetector(
                this,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onScroll(
                        e1: MotionEvent?,
                        e2: MotionEvent,
                        distanceX: Float,
                        distanceY: Float,
                    ): Boolean {
                        // Invert direction: dragging right should reveal content on the left.
                        val w = glSurfaceView.width.coerceAtLeast(1)
                        val h = glSurfaceView.height.coerceAtLeast(1)
                        // Scale delta by current FOV so drag feel is uniform across zoom levels.
                        val yawScale = (renderer.fovDeg * PI.toFloat() / 180f) / w
                        val pitchScale = (renderer.fovDeg * PI.toFloat() / 180f) / h
                        renderer.yawRad -= distanceX * yawScale
                        renderer.pitchRad =
                            (renderer.pitchRad - distanceY * pitchScale)
                                .coerceIn(-MAX_PITCH_RAD, MAX_PITCH_RAD)
                        return true
                    }
                },
            )

        @Suppress("ClickableViewAccessibility")
        glSurfaceView.setOnTouchListener { _, event ->
            // Scale takes priority so pinch doesn't also pan.
            val scaleHandled = scaleDetector.onTouchEvent(event)
            val dragHandled =
                if (!scaleDetector.isInProgress) dragDetector.onTouchEvent(event) else false
            scaleHandled || dragHandled
        }

        val uri = intent.data
        if (uri == null) {
            showError(R.string.panorama_error)
            return
        }
        loadBitmap(uri)
    }

    private fun loadBitmap(uri: Uri) {
        loadExecutor.execute {
            val bmp =
                try {
                    contentResolver.openInputStream(uri).use { stream ->
                        requireNotNull(stream) { "null stream for $uri" }
                        val opts =
                            BitmapFactory.Options().apply {
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                            }
                        BitmapFactory.decodeStream(stream, null, opts)
                    }
                } catch (t: Throwable) {
                    LOG.warn("Failed to decode panorama bitmap for {}", uri, t)
                    null
                }
            runOnUiThread {
                if (bmp == null) {
                    showError(R.string.panorama_error)
                } else {
                    glSurfaceView.queueEvent {
                        renderer.setBitmap(bmp)
                    }
                }
            }
        }
    }

    private fun showError(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_LONG).show()
        emptyView.setText(resId)
        emptyView.visibility = android.view.View.VISIBLE
        glSurfaceView.visibility = android.view.View.INVISIBLE
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
    }

    override fun onPause() {
        glSurfaceView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        loadExecutor.shutdownNow()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.panorama_viewer, menu)
        when (renderer.projection) {
            PanoramaRenderer.Projection.EQUIRECT_360 ->
                menu.findItem(R.id.panorama_projection_360)?.isChecked = true
            PanoramaRenderer.Projection.EQUIRECT_180 ->
                menu.findItem(R.id.panorama_projection_180)?.isChecked = true
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
            R.id.panorama_projection_360 -> {
                switchProjection(PanoramaRenderer.Projection.EQUIRECT_360)
                item.isChecked = true
                return true
            }
            R.id.panorama_projection_180 -> {
                switchProjection(PanoramaRenderer.Projection.EQUIRECT_180)
                item.isChecked = true
                return true
            }
            R.id.panorama_reset_view -> {
                renderer.yawRad = 0f
                renderer.pitchRad = 0f
                renderer.fovDeg = 75f
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun switchProjection(projection: PanoramaRenderer.Projection) {
        if (renderer.projection == projection) return
        renderer.projection = projection
        glSurfaceView.queueEvent { renderer.rebuildSphereForProjection() }
    }
}

// FOV bounds keep the view from collapsing (<30°) or bleeding into
// fisheye territory (>110°). Clamp pitch just shy of 90° to avoid
// gimbal flip when the user drags past vertical.
private const val MIN_FOV = 30f
private const val MAX_FOV = 110f
private const val MAX_PITCH_RAD = 1.4f
