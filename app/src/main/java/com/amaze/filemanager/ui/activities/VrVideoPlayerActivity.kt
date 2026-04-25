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
 * Launched automatically by [com.amaze.filemanager.filesystem.files.FileUtils]
 * for files whose MIME type starts with "video/", or by any ACTION_VIEW intent
 * with a video mime type.
 */
class VrVideoPlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var loading: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vr_video_player)
        playerView = findViewById(R.id.vr_player_view)
        loading = findViewById(R.id.vr_player_loading)
        applyImmersiveMode()

        val uri: Uri? = intent?.data
        if (uri == null) {
            Toast.makeText(this, R.string.vr_video_player_error, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        preparePlayer(uri)
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
        playerView.player = exoPlayer
        player = exoPlayer
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
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
