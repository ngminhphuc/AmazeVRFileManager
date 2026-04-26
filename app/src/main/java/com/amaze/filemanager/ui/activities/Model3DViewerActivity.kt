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
import android.os.Bundle
import android.view.Choreographer
import android.view.SurfaceView
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.amaze.filemanager.R
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Inline 3D model previewer for glTF binary files (`.glb`).
 *
 * Powered by Google Filament's [ModelViewer]. The activity owns a
 * [SurfaceView] and pumps a Choreographer-driven render loop while the
 * window is foreground. Touch events are forwarded to `ModelViewer` for
 * orbit / pan / pinch-zoom controls.
 *
 * Loads `.glb` (binary glTF) only — `.gltf` (text + external buffer/image
 * files) requires a custom resource resolver and is deferred.
 */
class Model3DViewerActivity : AppCompatActivity(), Choreographer.FrameCallback {
    companion object {
        private val LOG = LoggerFactory.getLogger(Model3DViewerActivity::class.java)

        init {
            // Loads filament native libraries and image utilities. Safe to call
            // multiple times — guarded internally.
            Utils.init()
        }
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var emptyView: TextView
    private lateinit var choreographer: Choreographer
    private lateinit var modelViewer: ModelViewer
    private var sunLight: Int = 0
    private var indirectLight: com.google.android.filament.IndirectLight? = null
    private var rendering: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model3d_viewer)
        setSupportActionBar(findViewById(R.id.model3d_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = intent.data?.lastPathSegment ?: getString(R.string.model3d_viewer)

        surfaceView = findViewById(R.id.model3d_surface)
        emptyView = findViewById(R.id.model3d_empty)
        choreographer = Choreographer.getInstance()
        modelViewer = ModelViewer(surfaceView)

        @Suppress("ClickableViewAccessibility")
        surfaceView.setOnTouchListener { _, event ->
            modelViewer.onTouchEvent(event)
            true
        }

        installSunLight()

        val uri = intent.data
        if (uri == null || !loadGlb(uri)) {
            emptyView.visibility = View.VISIBLE
            return
        }
        emptyView.visibility = View.GONE
        rendering = true
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        if (rendering) choreographer.postFrameCallback(this)
    }

    override fun onPause() {
        super.onPause()
        choreographer.removeFrameCallback(this)
    }

    override fun onDestroy() {
        choreographer.removeFrameCallback(this)
        if (sunLight != 0) {
            modelViewer.scene.removeEntity(sunLight)
            modelViewer.engine.destroyEntity(sunLight)
            EntityManager.get().destroy(sunLight)
            sunLight = 0
        }
        indirectLight?.let {
            modelViewer.scene.indirectLight = null
            modelViewer.engine.destroyIndirectLight(it)
            indirectLight = null
        }
        // Free the loaded glTF asset (textures, vertex/index buffers, materials).
        // We deliberately do NOT call engine.destroy() here: ModelViewer's
        // internal UiHelper remains attached to the SurfaceView as a
        // SurfaceHolder.Callback, and Android will fire surfaceDestroyed
        // *after* this activity's onDestroy returns. UiHelper's callback then
        // calls engine.destroySwapChain(), which would assert on an
        // already-destroyed engine. Filament's official samples follow the
        // same pattern (rely on process death for engine teardown). This is
        // a single Engine retained per activity instance — acceptable for
        // our use case.
        modelViewer.destroyModel()
        super.onDestroy()
    }

    override fun doFrame(frameTimeNanos: Long) {
        choreographer.postFrameCallback(this)
        modelViewer.render(frameTimeNanos)
    }

    /**
     * Adds a single directional "sun" light so PBR materials are not pitch
     * black. A full IBL environment would be more accurate but requires
     * shipping a KTX cubemap; this is the minimum viable lighting setup.
     */
    private fun installSunLight() {
        val light = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 1.0f, 1.0f)
            .intensity(80_000.0f)
            .direction(-0.5f, -1.0f, -0.5f)
            .castShadows(false)
            .build(modelViewer.engine, light)
        modelViewer.scene.addEntity(light)
        sunLight = light

        // Soft ambient via a tiny IBL (single SH band) so shaded sides aren't black.
        val sh =
            floatArrayOf(
                0.6f, 0.6f, 0.6f, // L00
                0.0f, 0.0f, 0.0f, // L1-1
                0.0f, 0.0f, 0.0f, // L10
                0.0f, 0.0f, 0.0f, // L11
                0.0f, 0.0f, 0.0f, // L2-2
                0.0f, 0.0f, 0.0f, // L2-1
                0.0f, 0.0f, 0.0f, // L20
                0.0f, 0.0f, 0.0f, // L21
                0.0f, 0.0f, 0.0f, // L22
            )
        val ibl =
            com.google.android.filament.IndirectLight.Builder()
                .irradiance(3, sh)
                .intensity(30_000.0f)
                .build(modelViewer.engine)
        modelViewer.scene.indirectLight = ibl
        indirectLight = ibl
    }

    private fun loadGlb(uri: Uri): Boolean {
        return try {
            val bytes =
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: run {
                        LOG.warn("Failed to open $uri")
                        toastError(R.string.model3d_load_failed)
                        return false
                    }
            if (bytes.size < 4) {
                toastError(R.string.model3d_load_failed)
                return false
            }
            // glTF binary magic = "glTF" (0x46546C67 LE). If missing, abort.
            val isGlb =
                bytes[0] == 0x67.toByte() &&
                    bytes[1] == 0x6C.toByte() &&
                    bytes[2] == 0x54.toByte() &&
                    bytes[3] == 0x46.toByte()
            if (!isGlb) {
                toastError(R.string.model3d_only_glb)
                return false
            }
            val buf = ByteBuffer.allocateDirect(bytes.size)
            buf.put(bytes)
            buf.rewind()
            modelViewer.loadModelGlb(buf)
            modelViewer.transformToUnitCube()
            true
        } catch (e: IOException) {
            LOG.error("loadGlb failed", e)
            toastError(R.string.model3d_load_failed)
            false
        } catch (e: RuntimeException) {
            LOG.error("loadGlb runtime failure", e)
            toastError(R.string.model3d_load_failed)
            false
        } catch (e: OutOfMemoryError) {
            LOG.error("loadGlb out of memory", e)
            toastError(R.string.model3d_load_failed)
            false
        }
    }

    private fun toastError(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_LONG).show()
    }
}
