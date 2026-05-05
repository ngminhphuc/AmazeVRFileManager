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

import android.util.Xml
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Minimal WebDAV client built on the OkHttp dependency that already ships
 * with the app. Implements just the HTTP verbs Sprint 12 needs:
 *
 *   * `PROPFIND` with `Depth: 1` for directory listing
 *   * `GET` for file download / streaming (callers use the same URL with
 *     `DefaultHttpDataSource` for Media3 streaming)
 *   * `OPTIONS` as the cheap "Test connection" probe
 *
 * Sardine and other dedicated WebDAV libraries pull in Apache HttpClient
 * + Spring Web; replicating the small subset we need on top of OkHttp
 * keeps APK size flat and avoids transitive deps that aren't android-clean.
 *
 * Authentication is HTTP Basic only (the overwhelming majority of consumer
 * WebDAV deployments accept this; Nextcloud / Synology / Apache mod_dav
 * all do). Bearer tokens / digest are out of scope for Sprint 12.
 */
object WebDavClient {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** RFC 1123 date used by `getlastmodified`. */
    private val httpDateFormat: ThreadLocal<SimpleDateFormat> =
        ThreadLocal.withInitial {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }

    private const val PROPFIND_BODY =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<d:propfind xmlns:d=\"DAV:\">" +
            "<d:prop>" +
            "<d:displayname/><d:getcontentlength/><d:getcontenttype/>" +
            "<d:getlastmodified/><d:resourcetype/>" +
            "</d:prop>" +
            "</d:propfind>"

    private val xmlMediaType = "application/xml; charset=utf-8".toMediaType()

    /** "Basic <base64>" string suitable for `Authorization` HTTP header. */
    fun basicAuthHeader(server: WebDavServer): String? =
        if (server.username.isBlank() && server.password.isBlank()) {
            null
        } else {
            Credentials.basic(server.username, server.password)
        }

    /**
     * Reachability probe used by the "Test connection" button. Issues
     * `PROPFIND` with `Depth: 0` against the configured base URL — this
     * is the canonical "is this WebDAV" probe (returns 207 Multi-Status
     * on real WebDAV endpoints) and also validates the credentials at
     * the same time, avoiding the false-negatives we'd get from `OPTIONS`
     * on servers that only advertise the `DAV` header on selected paths
     * (Synology DSM, Box, some Apache mod_dav virtual hosts).
     */
    @Throws(IOException::class)
    fun probe(server: WebDavServer): Boolean {
        val url = server.baseUrl.toHttpUrlOrNull() ?: throw IOException("Invalid URL")
        val auth = basicAuthHeader(server)
        val body = PROPFIND_BODY.toRequestBody(xmlMediaType)
        val builder =
            Request.Builder()
                .url(url)
                .header("Depth", "0")
                .header("Content-Type", "application/xml; charset=utf-8")
                .method("PROPFIND", body)
        if (auth != null) builder.header("Authorization", auth)
        client.newCall(builder.build()).execute().use { resp ->
            // 207 Multi-Status is the documented success for PROPFIND.
            // Some servers also accept 200 OK so treat any 2xx as success
            // — `isSuccessful` covers both.
            if (!resp.isSuccessful) {
                throw IOException("PROPFIND HTTP ${resp.code} ${resp.message}")
            }
        }
        return true
    }

    /**
     * Lists immediate children of [collectionUrl]. The returned list has
     * the parent collection itself filtered out; entries are sorted folders
     * first, then by displayName ascending.
     */
    @Throws(IOException::class)
    fun listChildren(
        server: WebDavServer,
        collectionUrl: String,
    ): List<WebDavEntry> {
        val httpUrl =
            collectionUrl.toHttpUrlOrNull()
                ?: throw IOException("Invalid URL: $collectionUrl")
        val auth = basicAuthHeader(server)
        val body = PROPFIND_BODY.toRequestBody(xmlMediaType)
        val builder =
            Request.Builder()
                .url(httpUrl)
                .header("Depth", "1")
                .header("Content-Type", "application/xml; charset=utf-8")
                .method("PROPFIND", body)
        if (auth != null) builder.header("Authorization", auth)

        val raw =
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("PROPFIND HTTP ${resp.code} ${resp.message}")
                }
                resp.body?.string() ?: throw IOException("Empty PROPFIND response")
            }

        val all = parseMultistatus(raw, httpUrl)
        // Drop the parent collection's self-entry from the Depth:1 result.
        // parseMultistatus normalises every <d:href> into an absolute URL
        // via resolveHref, so we extract just the path portion of each
        // entry and compare against the request URL's path. This handles
        // servers that emit relative hrefs, absolute paths, or full URLs
        // uniformly.
        val selfPath = normaliseHref(httpUrl.encodedPath)
        val children =
            all.filter { entry ->
                val entryPath = entry.href.toHttpUrlOrNull()?.encodedPath ?: entry.href
                normaliseHref(entryPath) != selfPath
            }
        return children.sortedWith(
            compareByDescending<WebDavEntry> { it.isDirectory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName },
        )
    }

    /**
     * Resolves a possibly-relative `<d:href>` value (servers commonly emit
     * an absolute path like `/remote.php/dav/files/me/sub/`) against the
     * request URL so callers always get an absolute, fetchable URL.
     */
    fun resolveHref(
        baseRequestUrl: HttpUrl,
        href: String,
    ): String {
        val resolved = baseRequestUrl.resolve(href) ?: return href
        return resolved.toString()
    }

    private fun normaliseHref(s: String): String {
        var v = s
        if (v.endsWith("/")) v = v.substring(0, v.length - 1)
        return v
    }

    private fun parseMultistatus(
        xml: String,
        requestUrl: HttpUrl,
    ): List<WebDavEntry> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(xml.reader())

        val out = mutableListOf<WebDavEntry>()
        var event = parser.eventType
        var inResponse = false
        var href: String? = null
        var displayName: String? = null
        var contentLength = -1L
        var contentType: String? = null
        var lastModified: Long? = null
        var isCollection = false

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    val ns = parser.namespace
                    if (ns == "DAV:") {
                        when (name) {
                            "response" -> {
                                inResponse = true
                                href = null
                                displayName = null
                                contentLength = -1L
                                contentType = null
                                lastModified = null
                                isCollection = false
                            }
                            "href" -> if (inResponse) href = parser.nextText()
                            "displayname" -> if (inResponse) displayName = parser.nextText()
                            "getcontentlength" ->
                                if (inResponse) {
                                    contentLength = parser.nextText().trim().toLongOrNull() ?: -1L
                                }
                            "getcontenttype" -> if (inResponse) contentType = parser.nextText()
                            "getlastmodified" ->
                                if (inResponse) {
                                    val raw = parser.nextText().trim()
                                    lastModified =
                                        runCatching {
                                            httpDateFormat.get()!!.parse(raw)?.time
                                        }.getOrNull()
                                }
                            "collection" -> if (inResponse) isCollection = true
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.namespace == "DAV:" && parser.name == "response" && inResponse) {
                        href?.let { rawHref ->
                            val absolute = resolveHref(requestUrl, rawHref)
                            val display =
                                displayName?.takeIf { it.isNotBlank() }
                                    ?: deriveDisplayName(rawHref)
                            out.add(
                                WebDavEntry(
                                    href = absolute,
                                    displayName = display,
                                    isDirectory = isCollection,
                                    contentLength = contentLength,
                                    contentType = contentType,
                                    lastModified = lastModified,
                                ),
                            )
                        }
                        inResponse = false
                    }
                }
            }
            event = parser.next()
        }
        return out
    }

    /**
     * Last non-empty path segment of [rawHref], URL-decoded. Used as a
     * display fallback when the server does not emit `<d:displayname>`
     * (Apache mod_dav, lighttpd, ...).
     */
    private fun deriveDisplayName(rawHref: String): String {
        val noQuery = rawHref.substringBefore('?').substringBefore('#')
        val trimmed = if (noQuery.endsWith("/")) noQuery.dropLast(1) else noQuery
        val seg = trimmed.substringAfterLast('/', missingDelimiterValue = trimmed)
        return runCatching { URLDecoder.decode(seg, "UTF-8") }.getOrNull() ?: seg
    }

    /**
     * Convenience: open an [okhttp3.Response] streaming GET on [absoluteUrl].
     * Caller is responsible for closing the response body. Currently only
     * used by [WebDavClient.downloadToFile] but kept public so future
     * callers (preview, hash, etc.) can stream without re-implementing
     * authentication.
     */
    @Throws(IOException::class)
    fun openInputStream(
        server: WebDavServer,
        absoluteUrl: String,
    ): okhttp3.Response {
        val httpUrl =
            absoluteUrl.toHttpUrlOrNull()
                ?: throw IOException("Invalid URL: $absoluteUrl")
        val auth = basicAuthHeader(server)
        val builder = Request.Builder().url(httpUrl).get()
        if (auth != null) builder.header("Authorization", auth)
        val resp = client.newCall(builder.build()).execute()
        if (!resp.isSuccessful) {
            val code = resp.code
            val msg = resp.message
            resp.close()
            throw IOException("GET HTTP $code $msg")
        }
        return resp
    }

    /**
     * Streams [absoluteUrl] to [destinationFile]. Closes the network
     * response and the file sink before returning. Throws [IOException]
     * on any HTTP, I/O or auth failure.
     */
    @Throws(IOException::class)
    fun downloadToFile(
        server: WebDavServer,
        absoluteUrl: String,
        destinationFile: java.io.File,
    ) {
        openInputStream(server, absoluteUrl).use { resp ->
            val body = resp.body ?: throw IOException("Empty body")
            destinationFile.outputStream().use { sink ->
                body.byteStream().use { src ->
                    src.copyTo(sink)
                }
            }
        }
    }
}
