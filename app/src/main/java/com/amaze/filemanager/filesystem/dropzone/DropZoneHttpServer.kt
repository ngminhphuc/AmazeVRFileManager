/*
 * Copyright (C) 2014-2020 Arpit Khurana <arpitkh96@gmail.com>, Vishal Nehra <vishalmeham2@gmail.com>,
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

package com.amaze.filemanager.filesystem.dropzone

import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Sprint 14: minimal HTTP server for cross-device drop-zone (Phase 2.9).
 *
 * Listens on `0.0.0.0:port` and exposes two endpoints:
 *
 * - `GET /`         — landing HTML page with `<input type="file" multiple>`
 *                     and the optional PIN field, posting back to /upload.
 * - `POST /upload`  — multipart/form-data parser; writes each file part to
 *                     [downloadDir] with conflict-safe renaming.
 *
 * Other paths return 404. The server is intentionally trivial — no chunked
 * transfer, no keep-alive, no compression — because the only client is the
 * paired phone running its own (browser) form on the local Wi-Fi. Big-file
 * uploads stream to disk in 4 KiB chunks so we never buffer the whole body
 * in RAM.
 *
 * Auth: when [pin] is non-empty, requests must include either an
 * `X-Drop-PIN` header or a `pin` form field that matches. Wrong / missing
 * PIN returns 401. The PIN is rotated each time [start] is called.
 *
 * The server uses a small executor (4 threads) so simultaneous file
 * uploads from the same phone don't serialise.
 */
class DropZoneHttpServer(
    private val downloadDir: File,
    private val pin: String,
    private val port: Int = DEFAULT_PORT,
) {
    interface Listener {
        fun onUploadComplete(
            file: File,
            sizeBytes: Long,
        )

        fun onUploadFailed(
            filename: String?,
            reason: String,
        )
    }

    companion object {
        const val DEFAULT_PORT: Int = 8080
        private const val MAX_REQUEST_HEADERS_BYTES: Int = 16 * 1024
        private val LOG = LoggerFactory.getLogger(DropZoneHttpServer::class.java)
    }

    @Volatile private var serverSocket: ServerSocket? = null

    @Volatile private var running = false
    private val executor = Executors.newFixedThreadPool(4)
    private val uploadCounter = AtomicLong(0)
    var listener: Listener? = null

    @Throws(IOException::class)
    fun start() {
        if (running) return
        if (!downloadDir.exists() && !downloadDir.mkdirs()) {
            throw IOException("Cannot create download directory $downloadDir")
        }
        val s = ServerSocket(port)
        serverSocket = s
        running = true
        Thread({ acceptLoop(s) }, "dropzone-http-accept").start()
    }

    fun stop() {
        running = false
        serverSocket?.runCatching { close() }
        serverSocket = null
        executor.shutdownNow()
    }

    /** Total number of successful uploads since the last [start]. */
    fun uploadCount(): Long = uploadCounter.get()

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val socket =
                runCatching { s.accept() }.getOrNull() ?: return
            executor.execute {
                try {
                    handleConnection(socket)
                } catch (e: Throwable) {
                    LOG.warn("drop-zone connection failed", e)
                } finally {
                    socket.runCatching { close() }
                }
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        val input = PushbackInputStream(socket.getInputStream(), 8192)
        val output = socket.getOutputStream()
        val (requestLine, headers) = readHeaders(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) {
            writeResponse(output, 400, "Bad Request", "text/plain", "Bad request line")
            return
        }
        val method = parts[0]
        val path = parts[1]
        when {
            method == "GET" && (path == "/" || path == "/index.html") ->
                writeResponse(output, 200, "OK", "text/html; charset=utf-8", landingHtml())
            method == "POST" && path.startsWith("/upload") ->
                handleUpload(input, output, headers)
            else ->
                writeResponse(output, 404, "Not Found", "text/plain", "Not found: $path")
        }
    }

    private fun readHeaders(input: InputStream): Pair<String, Map<String, String>>? {
        val buf = ByteArrayOutputStream()
        var prev = -1
        var prev2 = -1
        var prev3 = -1
        while (buf.size() < MAX_REQUEST_HEADERS_BYTES) {
            val b = input.read()
            if (b == -1) return null
            buf.write(b)
            if (prev3 == 0x0D && prev2 == 0x0A && prev == 0x0D && b == 0x0A) break
            prev3 = prev2
            prev2 = prev
            prev = b
        }
        val headerText = buf.toString(StandardCharsets.ISO_8859_1.name())
        val lines = headerText.split("\r\n")
        if (lines.isEmpty() || lines[0].isBlank()) return null
        val requestLine = lines[0]
        val headers = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val k = line.substring(0, colon).trim().lowercase()
            val v = line.substring(colon + 1).trim()
            headers[k] = v
        }
        return requestLine to headers
    }

    private fun handleUpload(
        input: PushbackInputStream,
        output: OutputStream,
        headers: Map<String, String>,
    ) {
        val contentType = headers["content-type"]
        val contentLength =
            headers["content-length"]?.toLongOrNull()
                ?: run {
                    writeResponse(output, 411, "Length Required", "text/plain", "Content-Length missing")
                    return
                }
        if (contentType == null || !contentType.startsWith("multipart/form-data")) {
            writeResponse(
                output,
                415,
                "Unsupported Media Type",
                "text/plain",
                "Expected multipart/form-data",
            )
            return
        }
        val boundary =
            contentType.substringAfter("boundary=", "").trim().trim('"').takeIf { it.isNotBlank() }
                ?: run {
                    writeResponse(
                        output,
                        400,
                        "Bad Request",
                        "text/plain",
                        "Missing multipart boundary",
                    )
                    return
                }
        val headerPin = headers["x-drop-pin"]
        var pinOk = pin.isEmpty() || (headerPin != null && headerPin == pin)

        val parser = MultipartParser(input, contentLength, boundary)
        val saved = mutableListOf<Pair<File, Long>>()
        try {
            while (parser.nextPart()) {
                val name = parser.fieldName ?: continue
                if (name == "pin" && !pinOk) {
                    val value = parser.readTextPart()
                    if (value == pin) pinOk = true
                    continue
                }
                if (parser.filename == null) {
                    parser.discardPart()
                    continue
                }
                if (!pinOk) {
                    parser.discardPart()
                    continue
                }
                val target = uniqueTarget(downloadDir, parser.filename!!)
                val written = parser.writePartTo(target)
                saved += target to written
                uploadCounter.incrementAndGet()
                listener?.onUploadComplete(target, written)
            }
        } catch (e: IOException) {
            listener?.onUploadFailed(parser.filename, e.message ?: "I/O error")
            writeResponse(output, 400, "Bad Request", "text/plain", "Upload failed: ${e.message}")
            return
        }
        if (!pinOk) {
            writeResponse(output, 401, "Unauthorized", "text/plain", "PIN required")
            return
        }
        val body =
            buildString {
                append("Received ${saved.size} file(s):\n")
                for ((f, n) in saved) {
                    append("  ${f.name} ($n bytes)\n")
                }
            }
        writeResponse(output, 200, "OK", "text/plain; charset=utf-8", body)
    }

    private fun uniqueTarget(
        dir: File,
        requested: String,
    ): File {
        val safe = requested.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        var candidate = File(dir, safe)
        if (!candidate.exists()) return candidate
        val dot = safe.lastIndexOf('.')
        val stem = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ""
        var i = 1
        while (true) {
            candidate = File(dir, "$stem ($i)$ext")
            if (!candidate.exists()) return candidate
            i++
        }
    }

    private fun writeResponse(
        output: OutputStream,
        code: Int,
        status: String,
        contentType: String,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val header =
            "HTTP/1.1 $code $status\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n" +
                "\r\n"
        output.write(header.toByteArray(StandardCharsets.ISO_8859_1))
        output.write(bytes)
        output.flush()
    }

    private fun landingHtml(): String =
        """<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Amaze drop-zone</title>
<style>
body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;background:#111;color:#eee;margin:0;padding:24px;}
h1{font-weight:400;font-size:22px;margin:0 0 16px}
form{background:#1c1c1c;padding:16px;border-radius:12px;border:1px solid #333}
input[type=file]{width:100%;padding:12px;background:#222;color:#eee;border:1px dashed #555;border-radius:8px}
input[type=text]{width:100%;padding:12px;background:#222;color:#eee;border:1px solid #555;border-radius:8px;margin-top:12px;font-size:18px;letter-spacing:4px;text-align:center}
button{margin-top:16px;width:100%;padding:14px;background:#1976d2;color:#fff;border:0;border-radius:8px;font-size:16px}
.note{margin-top:16px;color:#888;font-size:13px}
</style></head><body>
<h1>Amaze drop-zone</h1>
<form action="/upload" method="post" enctype="multipart/form-data">
  <input type="file" name="file" multiple required>
  ${if (pin.isNotEmpty()) "<input type=\"text\" name=\"pin\" placeholder=\"PIN\" inputmode=\"numeric\" maxlength=\"6\" required>" else ""}
  <button type="submit">Upload to Quest</button>
</form>
<p class="note">Files land in /sdcard/Download/AmazeDrop/.</p>
</body></html>
"""
}

/**
 * Streaming `multipart/form-data` parser scoped to one request body.
 *
 * The parser owns a [PushbackInputStream] view of the request body. Each
 * call to [nextPart] consumes a `--boundary\r\n` line, parses headers up
 * to a blank line, and exposes [fieldName] / [filename] for the caller
 * to dispatch. The caller then chooses one of:
 *
 * - [readTextPart] — buffer the part body in memory and return it as a
 *   string (small text fields like `pin`).
 * - [writePartTo] — stream the part body straight into a [File].
 * - [discardPart] — drop the part body without allocating memory.
 *
 * In all three cases the parser scans for the trailing `\r\n--boundary`
 * marker and stops the part body just before it, then peeks the two
 * bytes after to distinguish `\r\n` (more parts coming) from `--`
 * (terminating boundary). After the terminating boundary, [done] is set
 * and subsequent [nextPart] calls return false.
 */
private class MultipartParser(
    private val input: PushbackInputStream,
    contentLength: Long,
    boundary: String,
) {
    private val crlf = byteArrayOf(0x0D, 0x0A)
    private val delim = ("--$boundary").toByteArray(StandardCharsets.ISO_8859_1)
    private val tail = crlf + delim
    private var remaining = contentLength
    private var atFirstPart = true
    private var done = false

    var fieldName: String? = null
        private set
    var filename: String? = null
        private set

    fun nextPart(): Boolean {
        if (done) return false
        if (atFirstPart) {
            // First boundary is not preceded by CRLF; just consume the
            // `--boundary\r\n` line.
            val line = readLine() ?: return false
            if (!line.startsWith("--")) return false
            // Check for empty body (`--boundary--`)
            if (line.endsWith("--")) {
                done = true
                return false
            }
            atFirstPart = false
        }
        fieldName = null
        filename = null
        while (true) {
            val line = readLine() ?: return false
            if (line.isEmpty()) break
            val lower = line.lowercase()
            if (lower.startsWith("content-disposition:")) {
                fieldName = extractParam(line, "name")
                filename = extractParam(line, "filename")
            }
        }
        return true
    }

    fun readTextPart(): String {
        val baos = ByteArrayOutputStream()
        copyPartTo(baos)
        return baos.toString(StandardCharsets.UTF_8.name())
    }

    fun discardPart() {
        copyPartTo(NullOutputStream)
    }

    fun writePartTo(target: File): Long {
        target.outputStream().buffered().use { os ->
            return copyPartTo(os)
        }
    }

    /**
     * Copies the current part's body to [out] and stops just before the
     * trailing `\r\n--boundary` marker. Returns the number of bytes
     * written. After return the boundary is fully consumed and [done] is
     * set if the terminating `--` follows.
     */
    private fun copyPartTo(out: OutputStream): Long {
        // Sliding window of last tail.size bytes seen. When the window
        // matches `\r\n--boundary`, we've reached the end of the part.
        // Bytes that fall out of the window are written to [out].
        val window = ByteArray(tail.size)
        var fill = 0
        var written = 0L
        while (true) {
            if (remaining <= 0) {
                done = true
                throw IOException("Unexpected end of multipart body")
            }
            val b = input.read()
            if (b < 0) {
                done = true
                throw IOException("Unexpected EOF while reading part")
            }
            remaining--
            if (fill < tail.size) {
                window[fill++] = b.toByte()
            } else {
                out.write(window[0].toInt())
                written++
                System.arraycopy(window, 1, window, 0, tail.size - 1)
                window[tail.size - 1] = b.toByte()
            }
            if (fill == tail.size && window.contentEquals(tail)) {
                // Peek 2 bytes: \r\n (next part) or -- (final).
                val p1 = input.read().also { remaining-- }
                val p2 = input.read().also { remaining-- }
                if (p1 == '-'.code && p2 == '-'.code) {
                    done = true
                } else if (p1 != 0x0D || p2 != 0x0A) {
                    throw IOException("Malformed multipart boundary suffix")
                }
                out.flush()
                return written
            }
        }
    }

    private fun readLine(): String? {
        val sb = ByteArrayOutputStream()
        var prev = -1
        while (true) {
            if (remaining <= 0) return null
            val b = input.read()
            if (b == -1) return null
            remaining--
            if (prev == 0x0D && b == 0x0A) {
                val raw = sb.toByteArray()
                val len = if (raw.isNotEmpty() && raw.last() == 0x0D.toByte()) raw.size - 1 else raw.size
                return String(raw, 0, len, StandardCharsets.ISO_8859_1)
            }
            if (prev != -1) sb.write(prev)
            prev = b
        }
    }

    private fun extractParam(
        header: String,
        key: String,
    ): String? {
        val idx = header.indexOf("$key=", ignoreCase = true)
        if (idx < 0) return null
        val rest = header.substring(idx + key.length + 1).trim()
        if (rest.startsWith("\"")) {
            val end = rest.indexOf('"', 1)
            return if (end > 0) rest.substring(1, end) else null
        }
        val end = rest.indexOfAny(charArrayOf(';', ' '))
        return if (end < 0) rest else rest.substring(0, end)
    }
}

private object NullOutputStream : OutputStream() {
    override fun write(b: Int) {
        // discard
    }

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        // discard
    }
}
