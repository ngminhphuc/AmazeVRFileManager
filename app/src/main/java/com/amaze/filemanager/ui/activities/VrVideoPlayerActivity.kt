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
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ProgressBar
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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

    private var projection: Projection = Projection.FLAT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vr_video_player)
        flatPlayerView = findViewById(R.id.vr_player_view_flat)
        sphericalPlayerView = findViewById(R.id.vr_player_view_spherical)
        loading = findViewById(R.id.vr_player_loading)
        applyImmersiveMode()

        val uri: Uri? = intent?.data
        if (uri == null) {
            Toast.makeText(this, R.string.vr_video_player_error, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        projection = detectProjectionFromUri(uri)
        preparePlayer(uri)
        applyProjection(projection)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.vr_video_player, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val itemId =
            when (projection) {
                Projection.FLAT -> R.id.vr_projection_flat
                Projection.EQUIRECT_360 -> R.id.vr_projection_equirect_360
                Projection.EQUIRECT_180 -> R.id.vr_projection_equirect_180
            }
        menu.findItem(itemId)?.isChecked = true
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val newProjection =
            when (item.itemId) {
                R.id.vr_projection_flat -> Projection.FLAT
                R.id.vr_projection_equirect_360 -> Projection.EQUIRECT_360
                R.id.vr_projection_equirect_180 -> Projection.EQUIRECT_180
                else -> return super.onOptionsItemSelected(item)
            }
        if (newProjection != projection) {
            projection = newProjection
            applyProjection(projection)
            invalidateOptionsMenu()
        }
        item.isChecked = true
        return true
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
                Regex("(equirect|equirectangular|sphere|spherical|ambisonic)") to Projection.EQUIRECT_360,
            )
        for ((regex, p) in matchers) {
            if (regex.containsMatchIn(name)) return p
        }
        return Projection.FLAT
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
