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

package com.amaze.filemanager.filesystem.webdav

/**
 * Persisted configuration for a WebDAV server (Nextcloud, ownCloud,
 * Synology DSM, Box, Yandex, Apache mod_dav, ...).
 *
 * The credentials live in [WebDavStorage]'s isolated preferences file —
 * never the default SharedPreferences — so the settings-export feature
 * cannot accidentally leak the password.
 *
 * @property baseUrl absolute URL of the collection that the user wants to
 *   browse, e.g. `https://cloud.example.com/remote.php/dav/files/me/`. The
 *   trailing slash is normalised by the storage layer.
 * @property username basic-auth username (empty for public/anonymous).
 * @property password basic-auth password (empty for public/anonymous).
 *   Stored as plaintext in private app prefs; this matches how the SMB and
 *   media-server stacks already persist credentials.
 */
data class WebDavServer(
    val id: String,
    val name: String,
    val baseUrl: String,
    val username: String,
    val password: String,
)

/** A single entry returned by a WebDAV PROPFIND with `Depth: 1`. */
data class WebDavEntry(
    val href: String,
    val displayName: String,
    val isDirectory: Boolean,
    val contentLength: Long,
    val contentType: String?,
    val lastModified: Long?,
)
