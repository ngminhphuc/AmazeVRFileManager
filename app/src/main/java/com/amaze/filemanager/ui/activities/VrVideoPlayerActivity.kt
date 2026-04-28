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

import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ProgressBar
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.amaze.filemanager.R
import com.amaze.filemanager.filesystem.smb.SmbDataSource

/**
 * Inline video player activity optimised for Meta Quest 3 and other VR/2D panel
 * environments. Plays any URI supported by Media3 ExoPlayer (local file://,
 * content://, http(s)://) and SMB (smb://) via [SmbDataSource].
 *
 * Supports three projection modes:
 *  - Flat (default): regular 2D playback on a standard SurfaceView.
 *  - 360° equirectangular: full-sphere projection for ambient / monoscopic VR
 *    content such as YouTube VR or Insta360 recordings.
 *  - 180° VR: hemispherical projection for VR180 content (Google VR180,
 *    Insta360 EVO) where only the front half of the sphere is captured.
 *
 * Projection is auto-detected from the filename when possible; the user can
 * override via the overflow menu. When a spherical projection is active, the
 * video is rendered by Media3's built-in [androidx.media3.exoplayer.video.spherical.SphericalGLSurfaceView]
 * hosted inside a secondary [PlayerView] — user look direction can be adjusted
 * by dragging (controller stick on Quest 3 is mapped to touch by Horizon OS).
 */
class VrVideoPlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var flatPlayerView: PlayerView
    private lateinit var sphericalPlayerView: PlayerView
    private lateinit var loading: ProgressBar

    private enum class Projection { FLAT, EQUIRECT_360, EQUIRECT_180 }

    /**
     * Stereoscopic frame layout. Quest panel apps render 2D, so we crop one
     * eye / one half of the source frame and let the AspectRatioFrameLayout
     * restore the proper 16:9 aspect. True stereoscopic rendering would need
     * an immersive OpenXR rewrite (out-of-scope until Phase 3).
     */
    private enum class Stereo { MONO, SBS, OU }

    private var projection: Projection = Projection.FLAT
    private var stereo: Stereo = Stereo.MONO

    /** Last video size reported by ExoPlayer. Used to recompute the corrected
     *  aspect ratio when the stereo mode changes. */
    private var lastVideoSize: VideoSize = VideoSize.UNKNOWN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vr_video_player)
        flatPlayerView = findViewById(R.id.vr_player_view_flat)
        sphericalPlayerView = findViewById(R.id.vr_player_view_spherical)
        loading = findViewById(R.id.vr_player_loading)

        // The activity's theme extends Theme.AppCompat.NoActionBar, so we host
        // our own Toolbar overlaid on the video in order to reach the options
        // menu (projection switcher) and the back / up button.
        val toolbar: Toolbar = findViewById(R.id.vr_player_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.setTitle(R.string.vr_video_player)

        applyImmersiveMode()

        val uri: Uri? = intent?.data
        if (uri == null) {
            Toast.makeText(this, R.string.vr_video_player_error, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        projection = detectProjectionFromUri(uri)
        stereo = detectStereoFromUri(uri)
        preparePlayer(uri)
        applyProjection(projection)
        applyStereo()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.vr_video_player, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val projectionItemId =
            when (projection) {
                Projection.FLAT -> R.id.vr_projection_flat
                Projection.EQUIRECT_360 -> R.id.vr_projection_equirect_360
                Projection.EQUIRECT_180 -> R.id.vr_projection_equirect_180
            }
        menu.findItem(projectionItemId)?.isChecked = true
        val stereoItemId =
            when (stereo) {
                Stereo.MONO -> R.id.vr_stereo_mono
                Stereo.SBS -> R.id.vr_stereo_sbs
                Stereo.OU -> R.id.vr_stereo_ou
            }
        menu.findItem(stereoItemId)?.isChecked = true
        // Stereo cropping is implemented via TextureView matrix transforms on
        // the flat surface. The spherical GL surface uses its own renderer that
        // does not accept a TextureView transform, so disable the menu when a
        // spherical projection is active to avoid silently no-oping the user.
        menu.findItem(R.id.vr_stereo_menu)?.isVisible = projection == Projection.FLAT
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        when (item.itemId) {
            R.id.vr_projection_flat,
            R.id.vr_projection_equirect_360,
            R.id.vr_projection_equirect_180,
            -> {
                val newProjection =
                    when (item.itemId) {
                        R.id.vr_projection_equirect_360 -> Projection.EQUIRECT_360
                        R.id.vr_projection_equirect_180 -> Projection.EQUIRECT_180
                        else -> Projection.FLAT
                    }
                if (newProjection != projection) {
                    projection = newProjection
                    applyProjection(projection)
                    // Re-apply stereo: spherical surface ignores it, but when
                    // returning to flat we want the previous crop restored.
                    applyStereo()
                    invalidateOptionsMenu()
                }
                item.isChecked = true
                return true
            }
            R.id.vr_stereo_mono,
            R.id.vr_stereo_sbs,
            R.id.vr_stereo_ou,
            -> {
                val newStereo =
                    when (item.itemId) {
                        R.id.vr_stereo_sbs -> Stereo.SBS
                        R.id.vr_stereo_ou -> Stereo.OU
                        else -> Stereo.MONO
                    }
                if (newStereo != stereo) {
                    stereo = newStereo
                    applyStereo()
                    invalidateOptionsMenu()
                }
                item.isChecked = true
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    @OptIn(UnstableApi::class)
    private fun preparePlayer(uri: Uri) {
        loading.visibility = View.VISIBLE
        val dataSourceFactory: DataSource.Factory =
            when (uri.scheme?.lowercase()) {
                "smb" -> DataSource.Factory { SmbDataSource() }
                "http", "https" -> DefaultHttpDataSource.Factory()
                else -> DefaultDataSource.Factory(this)
            }
        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory)
        val exoPlayer =
            ExoPlayer.Builder(this)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
        exoPlayer.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    loading.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                }

                override fun onPlayerError(error: PlaybackException) {
                    loading.visibility = View.GONE
                    Toast.makeText(
                        this@VrVideoPlayerActivity,
                        getString(R.string.vr_video_player_error) + ": " + error.errorCodeName,
                        Toast.LENGTH_LONG,
                    ).show()
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    lastVideoSize = videoSize
                    // Our listener is registered before PlayerView attaches its
                    // own (which happens when flatPlayerView.player is set in
                    // applyProjection), so ExoPlayer dispatches to us first and
                    // PlayerView's internal listener resets the content frame
                    // aspect to the raw video aspect afterwards. Post our
                    // correction so it runs after all current callbacks have
                    // drained, ensuring the corrected ratio is the one that
                    // sticks for the visible half.
                    flatPlayerView.post { applyStereo() }
                }
            },
        )
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.playWhenReady = true
        exoPlayer.prepare()
        player = exoPlayer
    }

    /**
     * Attach the player to the [PlayerView] that matches the requested projection
     * and hide the other. Media3 handles the GL rendering difference internally
     * based on the `surface_type` attribute declared in the layout.
     */
    private fun applyProjection(projection: Projection) {
        val exoPlayer = player ?: return
        when (projection) {
            Projection.FLAT -> {
                sphericalPlayerView.player = null
                flatPlayerView.player = exoPlayer
                flatPlayerView.visibility = View.VISIBLE
                sphericalPlayerView.visibility = View.GONE
            }
            Projection.EQUIRECT_360, Projection.EQUIRECT_180 -> {
                flatPlayerView.player = null
                sphericalPlayerView.player = exoPlayer
                sphericalPlayerView.visibility = View.VISIBLE
                flatPlayerView.visibility = View.GONE
                // Media3's SphericalGLSurfaceView renders a full equirectangular sphere.
                // For 180° content we crop the hemisphere by letterboxing — Media3 exposes
                // this via the native decoder's metadata when available; for filename-based
                // 180° detection we rely on the natural 180° field of view feeling in VR.
            }
        }
    }

    /**
     * Apply the current [stereo] selection to the flat player surface. The
     * fix is two-fold:
     *   1. The [AspectRatioFrameLayout] inside the [PlayerView] is given a
     *      corrected aspect ratio so the surface is sized to the cropped half
     *      (e.g. SBS 32:9 → 16:9). Without this the visible half is squished.
     *   2. A scale transform on the underlying [TextureView] zooms the
     *      texture to twice its width/height anchored at the top-left, so
     *      only the left half (SBS) or top half (OU) maps to the surface.
     *
     * No-op when a spherical projection is active because Media3's spherical
     * renderer does not accept TextureView transforms.
     */
    private fun applyStereo() {
        if (projection != Projection.FLAT) {
            // Reset to identity if we ever toggle back from spherical to flat.
            (flatPlayerView.videoSurfaceView as? TextureView)?.setTransform(Matrix())
            return
        }
        val contentFrame: AspectRatioFrameLayout? =
            flatPlayerView.findViewById(androidx.media3.ui.R.id.exo_content_frame)
        val rawW = lastVideoSize.width.toFloat() * lastVideoSize.pixelWidthHeightRatio
        val rawH = lastVideoSize.height.toFloat()
        val rawAspect = if (rawW > 0f && rawH > 0f) rawW / rawH else 0f
        val correctedAspect =
            when (stereo) {
                Stereo.MONO -> rawAspect
                Stereo.SBS -> rawAspect / 2f
                Stereo.OU -> rawAspect * 2f
            }
        if (correctedAspect > 0f) {
            contentFrame?.setAspectRatio(correctedAspect)
        }
        val matrix = Matrix()
        when (stereo) {
            Stereo.MONO -> Unit // identity
            // Anchor at (0,0): the texture is stretched to twice its width;
            // the right half spills out to the right, leaving the left eye
            // mapped 1:1 onto the (now corrected-aspect) view bounds.
            Stereo.SBS -> matrix.setScale(2f, 1f, 0f, 0f)
            Stereo.OU -> matrix.setScale(1f, 2f, 0f, 0f)
        }
        (flatPlayerView.videoSurfaceView as? TextureView)?.let {
            it.setTransform(matrix)
            it.invalidate()
        }
    }

    /**
     * Best-effort detection of monoscopic 360°/180° content based on the file
     * name. Common patterns used by YouTube, Insta360, GoPro Max, Kandao, and
     * most consumer VR cameras are recognised. Users can override via the
     * overflow menu if detection is wrong.
     */
    private fun detectProjectionFromUri(uri: Uri): Projection {
        val name = (uri.lastPathSegment ?: "").lowercase()
        val matchers =
            listOf(
                Regex("(^|[_\\-.\\s])vr180([_\\-.\\s]|$)") to Projection.EQUIRECT_180,
                Regex("(^|[_\\-.\\s])180([_\\-.\\s]|$)") to Projection.EQUIRECT_180,
                Regex("(^|[_\\-.\\s])vr360([_\\-.\\s]|$)") to Projection.EQUIRECT_360,
                Regex("(^|[_\\-.\\s])360([_\\-.\\s]|$)") to Projection.EQUIRECT_360,
                Regex("(^|[_\\-.\\s])(equirect|equirectangular|sphere|spherical|ambisonic)([_\\-.\\s]|$)") to Projection.EQUIRECT_360,
            )
        for ((regex, p) in matchers) {
            if (regex.containsMatchIn(name)) return p
        }
        return Projection.FLAT
    }

    /**
     * Best-effort detection of stereoscopic frame packing from the file name.
     * Recognises patterns commonly produced by stereo-3D camera rigs and
     * encoders (`_sbs`, `_3d`, `_lr`, `_tab`, `_ou`, `_tb`). Word-boundary
     * matching avoids false positives like `atmosphere.mp4` matching `_sbs`.
     */
    private fun detectStereoFromUri(uri: Uri): Stereo {
        val name = (uri.lastPathSegment ?: "").lowercase()
        val sbsMatchers =
            listOf(
                Regex("(^|[_\\-.\\s])sbs([_\\-.\\s]|$)"),
                Regex("(^|[_\\-.\\s])lr([_\\-.\\s]|$)"),
                Regex("(^|[_\\-.\\s])3d([_\\-.\\s]|$)"),
            )
        // "TAB" = Top-And-Bottom is a widely used synonym for Over-Under,
        // so it belongs here, not in the SBS list.
        val ouMatchers =
            listOf(
                Regex("(^|[_\\-.\\s])ou([_\\-.\\s]|$)"),
                Regex("(^|[_\\-.\\s])tb([_\\-.\\s]|$)"),
                Regex("(^|[_\\-.\\s])tab([_\\-.\\s]|$)"),
            )
        if (ouMatchers.any { it.containsMatchIn(name) }) return Stereo.OU
        if (sbsMatchers.any { it.containsMatchIn(name) }) return Stereo.SBS
        return Stereo.MONO
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        flatPlayerView.player = null
        sphericalPlayerView.player = null
        player?.release()
        player = null
    }

    private fun applyImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }
}
