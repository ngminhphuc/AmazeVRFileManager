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

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * REST client wrapping the public Jellyfin/Emby and Plex APIs.
 *
 * Both Jellyfin and Emby share the same upstream surface (Emby fork): one
 * implementation, switched by [MediaServerType]. Plex uses its own routes
 * and `X-Plex-Token` header.
 *
 * Synchronous calls — callers run on a background thread / coroutine.
 */
object MediaServerClient {
    private const val CLIENT_NAME = "AmazeVRFileManager"
    private const val DEVICE_ID = "amaze-vr-fm"
    private const val PLEX_PRODUCT = "AmazeVRFileManager"

    private val http: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    /**
     * Authenticate and return a populated [MediaServer]. Throws on failure
     * (network / wrong creds).
     */
    @Throws(Exception::class)
    fun authenticate(
        type: MediaServerType,
        baseUrl: String,
        username: String,
        password: String,
        displayName: String,
    ): MediaServer {
        val cleanUrl = baseUrl.trimEnd('/')
        return when (type) {
            MediaServerType.JELLYFIN, MediaServerType.EMBY ->
                authenticateJellyfin(type, cleanUrl, username, password, displayName)
            MediaServerType.PLEX ->
                authenticatePlex(cleanUrl, username, password, displayName)
        }
    }

    /**
     * Build a server entry from a manually-supplied access token.
     * Used as an escape hatch for users who already have an X-Plex-Token or
     * Jellyfin API key from elsewhere.
     */
    fun fromToken(
        type: MediaServerType,
        baseUrl: String,
        accessToken: String,
        userId: String?,
        displayName: String,
    ): MediaServer =
        MediaServer(
            id = UUID.randomUUID().toString(),
            name = displayName,
            type = type,
            url = baseUrl.trimEnd('/'),
            username = null,
            accessToken = accessToken,
            userId = userId,
        )

    @Throws(Exception::class)
    private fun authenticateJellyfin(
        type: MediaServerType,
        baseUrl: String,
        username: String,
        password: String,
        displayName: String,
    ): MediaServer {
        val authHeader =
            "MediaBrowser Client=\"$CLIENT_NAME\", " +
                "Device=\"Quest3\", DeviceId=\"$DEVICE_ID\", Version=\"1.0\""
        val payload =
            JsonObject().apply {
                addProperty("Username", username)
                addProperty("Pw", password)
            }
        val body =
            payload.toString().toRequestBody("application/json".toMediaType())
        val request =
            Request.Builder()
                .url("$baseUrl/Users/AuthenticateByName")
                .header("X-Emby-Authorization", authHeader)
                .post(body)
                .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("Login failed: HTTP ${resp.code}")
            }
            val json = JsonParser.parseString(resp.body!!.string()).asJsonObject
            return MediaServer(
                id = UUID.randomUUID().toString(),
                name = displayName,
                type = type,
                url = baseUrl,
                username = username,
                accessToken = json.get("AccessToken").asString,
                userId = json.getAsJsonObject("User").get("Id").asString,
            )
        }
    }

    @Throws(Exception::class)
    private fun authenticatePlex(
        baseUrl: String,
        username: String,
        password: String,
        displayName: String,
    ): MediaServer {
        val body =
            ("user[login]=${urlEncode(username)}&user[password]=${urlEncode(password)}")
                .toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val clientId = DEVICE_ID
        val request =
            Request.Builder()
                .url("https://plex.tv/users/sign_in.json")
                .header("X-Plex-Client-Identifier", clientId)
                .header("X-Plex-Product", PLEX_PRODUCT)
                .header("X-Plex-Version", "1.0")
                .header("Accept", "application/json")
                .post(body)
                .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("Plex.tv login failed: HTTP ${resp.code}")
            }
            val json = JsonParser.parseString(resp.body!!.string()).asJsonObject
            val token = json.getAsJsonObject("user").get("authToken").asString
            return MediaServer(
                id = UUID.randomUUID().toString(),
                name = displayName,
                type = MediaServerType.PLEX,
                url = baseUrl,
                username = username,
                accessToken = token,
                userId = null,
            )
        }
    }

    /**
     * List the libraries (root browse entry) for a server.
     */
    @Throws(Exception::class)
    fun listLibraries(server: MediaServer): List<MediaItem> =
        when (server.type) {
            MediaServerType.JELLYFIN, MediaServerType.EMBY -> jellyfinViews(server)
            MediaServerType.PLEX -> plexSections(server)
        }

    /**
     * List children of a folder/library by its [parentId].
     */
    @Throws(Exception::class)
    fun listChildren(
        server: MediaServer,
        parentId: String,
    ): List<MediaItem> =
        when (server.type) {
            MediaServerType.JELLYFIN, MediaServerType.EMBY -> jellyfinChildren(server, parentId)
            MediaServerType.PLEX -> plexChildren(server, parentId)
        }

    private fun jellyfinViews(server: MediaServer): List<MediaItem> {
        val req =
            Request.Builder()
                .url("${server.url}/Users/${server.userId}/Views")
                .header("X-Emby-Token", server.accessToken)
                .build()
        return http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "Views failed: HTTP ${resp.code}" }
            val arr =
                JsonParser.parseString(resp.body!!.string())
                    .asJsonObject.getAsJsonArray("Items")
            arr.map {
                val o = it.asJsonObject
                MediaItem(
                    id = o.get("Id").asString,
                    name = o.get("Name").asString,
                    isFolder = true,
                    isVideo = false,
                    mediaUrl = null,
                    durationMillis = null,
                )
            }
        }
    }

    private fun jellyfinChildren(
        server: MediaServer,
        parentId: String,
    ): List<MediaItem> {
        val req =
            Request.Builder()
                .url(
                    "${server.url}/Users/${server.userId}/Items" +
                        "?ParentId=$parentId&Fields=MediaSources,RunTimeTicks&Limit=500",
                )
                .header("X-Emby-Token", server.accessToken)
                .build()
        return http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "Items failed: HTTP ${resp.code}" }
            val arr =
                JsonParser.parseString(resp.body!!.string())
                    .asJsonObject.getAsJsonArray("Items")
            arr.map {
                val o = it.asJsonObject
                val isFolder = o.get("IsFolder").asBoolean
                val type = o.get("Type").asString
                val isVideo =
                    !isFolder && (type == "Movie" || type == "Episode" || type == "Video")
                val id = o.get("Id").asString
                val ticks =
                    o.takeIf { it.asJsonObject.has("RunTimeTicks") }
                        ?.asJsonObject?.get("RunTimeTicks")?.asLong
                MediaItem(
                    id = id,
                    name = o.get("Name").asString,
                    isFolder = isFolder,
                    isVideo = isVideo,
                    mediaUrl =
                        if (isVideo) {
                            "${server.url}/Videos/$id/stream?Static=true" +
                                "&api_key=${urlEncode(server.accessToken)}"
                        } else {
                            null
                        },
                    durationMillis = ticks?.let { t -> t / 10_000 },
                )
            }
        }
    }

    private fun plexSections(server: MediaServer): List<MediaItem> {
        val req =
            Request.Builder()
                .url("${server.url}/library/sections")
                .header("X-Plex-Token", server.accessToken)
                .header("Accept", "application/json")
                .build()
        return http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "Sections failed: HTTP ${resp.code}" }
            val container =
                JsonParser.parseString(resp.body!!.string())
                    .asJsonObject.getAsJsonObject("MediaContainer")
            val arr =
                container.takeIf { it.has("Directory") }
                    ?.getAsJsonArray("Directory") ?: JsonArray()
            arr.map {
                val o = it.asJsonObject
                MediaItem(
                    id = o.get("key").asString,
                    name = o.get("title").asString,
                    isFolder = true,
                    isVideo = false,
                    mediaUrl = null,
                    durationMillis = null,
                )
            }
        }
    }

    private fun plexChildren(
        server: MediaServer,
        parentId: String,
    ): List<MediaItem> {
        val path =
            if (parentId.startsWith("/")) parentId else "/library/sections/$parentId/all"
        val req =
            Request.Builder()
                .url("${server.url}$path")
                .header("X-Plex-Token", server.accessToken)
                .header("Accept", "application/json")
                .build()
        return http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "Plex children failed: HTTP ${resp.code}" }
            val container =
                JsonParser.parseString(resp.body!!.string())
                    .asJsonObject.getAsJsonObject("MediaContainer")
            val items = mutableListOf<MediaItem>()
            container.takeIf { it.has("Directory") }
                ?.getAsJsonArray("Directory")?.forEach {
                    val o = it.asJsonObject
                    items.add(
                        MediaItem(
                            id = o.get("key").asString,
                            name = o.get("title").asString,
                            isFolder = true,
                            isVideo = false,
                            mediaUrl = null,
                            durationMillis = null,
                        ),
                    )
                }
            container.takeIf { it.has("Video") }
                ?.getAsJsonArray("Video")?.forEach {
                    val o = it.asJsonObject
                    val partKey =
                        o.takeIf { it.asJsonObject.has("Media") }
                            ?.asJsonObject?.getAsJsonArray("Media")?.firstOrNull()
                            ?.asJsonObject?.getAsJsonArray("Part")?.firstOrNull()
                            ?.asJsonObject?.get("key")?.asString
                    val streamUrl =
                        partKey?.let {
                            "${server.url}$it?X-Plex-Token=${urlEncode(server.accessToken)}"
                        }
                    items.add(
                        MediaItem(
                            id = o.get("ratingKey").asString,
                            name = o.get("title").asString,
                            isFolder = false,
                            isVideo = streamUrl != null,
                            mediaUrl = streamUrl,
                            durationMillis =
                                if (o.has("duration")) {
                                    o.get("duration").asLong
                                } else {
                                    null
                                },
                        ),
                    )
                }
            items
        }
    }

    private fun urlEncode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
