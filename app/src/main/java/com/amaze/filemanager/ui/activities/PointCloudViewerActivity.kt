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

import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.amaze.filemanager.R
import com.amaze.filemanager.filesystem.pointcloud.PointCloud
import com.amaze.filemanager.filesystem.pointcloud.PointCloudParser
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.PI

/**
 * Inline viewer for `.ply` and `.pcd` point clouds.
 *
 * Files are decoded on a single-thread executor and uploaded to the GL thread
 * via [PointCloudRenderer.setCloud]. Drag pans yaw/pitch around the cloud
 * centroid, pinch zooms by scaling the orbit distance.
 */
class PointCloudViewerActivity : AppCompatActivity() {
    companion object {
        private val LOG = LoggerFactory.getLogger(PointCloudViewerActivity::class.java)

        // FOV is fixed; we orbit by distance instead. Min/max clamp keeps the
        // camera from passing through the cloud or zooming so far the cloud
        // becomes a single pixel.
        private const val MIN_DISTANCE_RATIO = 0.1f
        private const val MAX_DISTANCE_RATIO = 30f
        private const val MAX_PITCH_RAD = (PI / 2 - 0.01).toFloat()
    }

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var emptyView: TextView
    private lateinit var renderer: PointCloudRenderer
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var dragDetector: GestureDetector
    private val loadExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var contentUri: Uri? = null
    private var detectedFormat: PointCloudParser.Format? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_point_cloud_viewer)
        setSupportActionBar(findViewById(R.id.point_cloud_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title =
            intent.data?.lastPathSegment ?: getString(R.string.point_cloud_viewer)

        glSurfaceView = findViewById(R.id.point_cloud_surface)
        emptyView = findViewById(R.id.point_cloud_empty)

        renderer = PointCloudRenderer()
        glSurfaceView.setEGLContextClientVersion(2)
        // Best-effort context preservation; the renderer's onContextRecreated
        // hook covers devices that ignore the hint.
        glSurfaceView.preserveEGLContextOnPause = true
        renderer.onContextRecreated = {
            contentUri?.let { uri -> runOnUiThread { loadCloud(uri) } }
        }
        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        scaleDetector =
            ScaleGestureDetector(
                this,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        val current = renderer.distance
                        val baseline = current.coerceAtLeast(0.001f)
                        val next = baseline / detector.scaleFactor
                        renderer.distance =
                            next.coerceIn(
                                baseline * MIN_DISTANCE_RATIO,
                                baseline * MAX_DISTANCE_RATIO,
                            )
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
                        val w = glSurfaceView.width.coerceAtLeast(1)
                        val h = glSurfaceView.height.coerceAtLeast(1)
                        // Drag a full screen → 180° orbit. Matches behaviour
                        // of the panorama viewer so the two feel consistent.
                        val yawScale = PI.toFloat() / w
                        val pitchScale = PI.toFloat() / h
                        renderer.yawRad += distanceX * yawScale
                        renderer.pitchRad =
                            (renderer.pitchRad + distanceY * pitchScale)
                                .coerceIn(-MAX_PITCH_RAD, MAX_PITCH_RAD)
                        return true
                    }
                },
            )

        @Suppress("ClickableViewAccessibility")
        glSurfaceView.setOnTouchListener { _, event ->
            val scaleHandled = scaleDetector.onTouchEvent(event)
            val dragHandled =
                if (!scaleDetector.isInProgress) dragDetector.onTouchEvent(event) else false
            scaleHandled || dragHandled
        }

        val uri = intent.data
        if (uri == null) {
            showError(R.string.point_cloud_error)
            return
        }
        contentUri = uri
        detectedFormat = PointCloudParser.formatFor(uri.lastPathSegment.orEmpty())
        if (detectedFormat == null) {
            showError(R.string.point_cloud_unsupported)
            return
        }
        loadCloud(uri)
    }

    private fun loadCloud(uri: Uri) {
        val format = detectedFormat ?: return
        loadExecutor.execute {
            val cloud =
                try {
                    contentResolver.openInputStream(uri).use { stream ->
                        requireNotNull(stream) { "null stream for $uri" }
                        PointCloudParser.parse(stream, format)
                    }
                } catch (e: IOException) {
                    LOG.warn("Failed to parse point cloud {}", uri, e)
                    null
                } catch (e: NumberFormatException) {
                    LOG.warn("Malformed numeric token in {}", uri, e)
                    null
                } catch (e: OutOfMemoryError) {
                    LOG.warn("OOM parsing point cloud {}", uri, e)
                    null
                } catch (e: RuntimeException) {
                    LOG.warn("Runtime failure parsing point cloud {}", uri, e)
                    null
                }
            runOnUiThread {
                if (cloud == null || cloud.pointCount == 0) {
                    showError(R.string.point_cloud_error)
                    return@runOnUiThread
                }
                postCloud(cloud)
            }
        }
    }

    private fun postCloud(cloud: PointCloud) {
        if (cloud.downsampled) {
            Toast.makeText(
                this,
                getString(R.string.point_cloud_downsampled, cloud.pointCount),
                Toast.LENGTH_LONG,
            ).show()
        }
        emptyView.visibility = View.GONE
        glSurfaceView.visibility = View.VISIBLE
        glSurfaceView.queueEvent { renderer.setCloud(cloud) }
    }

    private fun showError(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_LONG).show()
        emptyView.setText(resId)
        emptyView.visibility = View.VISIBLE
        glSurfaceView.visibility = View.INVISIBLE
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
        menuInflater.inflate(R.menu.point_cloud_viewer, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
            R.id.point_cloud_reset_view -> {
                renderer.resetCamera()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
}
