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

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * JSON-backed persistence for the user's configured WebDAV servers.
 *
 * Stored in a dedicated, isolated SharedPreferences file
 * ([PREFERENCE_FILE]) — same pattern as [com.amaze.filemanager.filesystem
 * .mediaserver.MediaServerStorage] — so credentials are NOT included in
 * the settings-export feature (BackupPrefsFragment.exportPrefs() dumps
 * the default SharedPreferences only).
 */
object WebDavStorage {
    private const val PREFERENCE_FILE = "webdav_servers_prefs"
    private const val PREFERENCE_KEY_SERVERS = "webdav_servers_json"
    private val gson = Gson()
    private val type = object : TypeToken<List<WebDavServer>>() {}.type

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFERENCE_FILE, Context.MODE_PRIVATE)

    fun list(context: Context): List<WebDavServer> {
        val raw = prefs(context).getString(PREFERENCE_KEY_SERVERS, null) ?: return emptyList()
        return runCatching { gson.fromJson<List<WebDavServer>>(raw, type) }.getOrNull()
            ?: emptyList()
    }

    fun save(
        context: Context,
        servers: List<WebDavServer>,
    ) {
        prefs(context)
            .edit()
            .putString(PREFERENCE_KEY_SERVERS, gson.toJson(servers))
            .apply()
    }

    fun upsert(
        context: Context,
        server: WebDavServer,
    ) {
        val existing = list(context).toMutableList()
        val idx = existing.indexOfFirst { it.id == server.id }
        if (idx >= 0) existing[idx] = server else existing.add(server)
        save(context, existing)
    }

    fun remove(
        context: Context,
        id: String,
    ) {
        save(context, list(context).filterNot { it.id == id })
    }

    /**
     * Normalise a user-supplied base URL to always end in `/`. Prevents
     * accidental relative-resolution bugs later when concatenating hrefs.
     */
    fun normaliseBaseUrl(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }
}
