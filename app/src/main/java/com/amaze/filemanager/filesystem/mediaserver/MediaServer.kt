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

package com.amaze.filemanager.filesystem.mediaserver

/**
 * Configuration for a configured Jellyfin / Emby / Plex server.
 *
 * Persisted as JSON in SharedPreferences. The `accessToken` is stored alongside
 * the username so authenticated reconnection does not require the user to
 * re-enter their password on every app launch.
 */
data class MediaServer(
    val id: String,
    val name: String,
    val type: MediaServerType,
    val url: String,
    val username: String?,
    val accessToken: String,
    val userId: String?,
)

enum class MediaServerType {
    JELLYFIN,
    EMBY,
    PLEX,
    ;

    companion object {
        fun fromString(s: String?): MediaServerType =
            when (s?.uppercase()) {
                "EMBY" -> EMBY
                "PLEX" -> PLEX
                else -> JELLYFIN
            }
    }
}

/** A folder, library, or video item returned from a media server browse call. */
data class MediaItem(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val isVideo: Boolean,
    val mediaUrl: String?,
    val durationMillis: Long?,
)
