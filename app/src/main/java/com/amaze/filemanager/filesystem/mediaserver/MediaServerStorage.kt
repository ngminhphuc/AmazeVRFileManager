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

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * JSON-backed persistence layer for the user's configured media servers.
 *
 * Stored in a dedicated, isolated SharedPreferences file
 * ([PREFERENCE_FILE]) so that access tokens are NOT included in the
 * settings-export feature (BackupPrefsFragment.exportPrefs() dumps the
 * default SharedPreferences). If even stronger guarantees are needed,
 * swap the underlying [prefs] for EncryptedSharedPreferences without
 * changing the public API.
 */
object MediaServerStorage {
    private const val PREFERENCE_FILE = "media_servers_prefs"
    private const val PREFERENCE_KEY_SERVERS = "media_servers_json"
    private val gson = Gson()
    private val type = object : TypeToken<List<MediaServer>>() {}.type

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFERENCE_FILE, Context.MODE_PRIVATE)

    fun list(context: Context): List<MediaServer> {
        val raw = prefs(context).getString(PREFERENCE_KEY_SERVERS, null) ?: return emptyList()
        return runCatching { gson.fromJson<List<MediaServer>>(raw, type) }.getOrNull()
            ?: emptyList()
    }

    fun save(
        context: Context,
        servers: List<MediaServer>,
    ) {
        prefs(context)
            .edit()
            .putString(PREFERENCE_KEY_SERVERS, gson.toJson(servers))
            .apply()
    }

    fun upsert(
        context: Context,
        server: MediaServer,
    ) {
        val existing = list(context).toMutableList()
        val idx = existing.indexOfFirst { it.id == server.id }
        if (idx >= 0) {
            existing[idx] = server
        } else {
            existing.add(server)
        }
        save(context, existing)
    }

    fun remove(
        context: Context,
        id: String,
    ) {
        save(context, list(context).filterNot { it.id == id })
    }
}
