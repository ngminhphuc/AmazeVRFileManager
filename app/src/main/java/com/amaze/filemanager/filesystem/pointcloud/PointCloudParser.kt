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

package com.amaze.filemanager.filesystem.pointcloud

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decoded point cloud sized for direct upload to a GL vertex buffer.
 *
 * @property positions packed (x,y,z) tuples, length = 3 * pointCount
 * @property colors packed (r,g,b) tuples in [0,1], length = 3 * pointCount,
 *     filled with grey (0.85) when the source format had no per-point colour
 * @property pointCount number of points actually loaded after any downsampling
 * @property minBounds axis-aligned minimum corner of the cloud
 * @property maxBounds axis-aligned maximum corner of the cloud
 * @property downsampled true if [PointCloudParser.MAX_POINTS] was hit and the
 *     parser dropped points using a deterministic stride
 */
data class PointCloud(
    val positions: FloatArray,
    val colors: FloatArray,
    val pointCount: Int,
    val minBounds: FloatArray,
    val maxBounds: FloatArray,
    val downsampled: Boolean,
)

/**
 * Sprint 11 — minimal parser for the two point-cloud formats most commonly
 * found on consumer Quest 3 storage:
 *
 *  - **PLY** (Stanford triangle format) — ASCII and binary little-endian. The
 *    parser only consumes the `vertex` element; `face` and other elements are
 *    skipped because we render points, not triangles.
 *  - **PCD** (PointCloudLibrary) — ASCII variant only. Binary PCD is rare in
 *    consumer scans (Polycam, Scaniverse, RealityScan emit ASCII or PLY).
 *
 * Both paths cap output at [MAX_POINTS] using a deterministic stride; clouds
 * larger than that are downsampled rather than rejected so the user always
 * sees something.
 */
object PointCloudParser {
    /**
     * Cap on points kept in memory. 1M points × 6 floats (xyz + rgb) ≈ 24 MB,
     * which is comfortable on Quest 3 (8 GB RAM) without risking OOM in the
     * Filament/GLES upload step. LiDAR scans from Polycam / Scaniverse on
     * iPhone Pro typically produce 100k–500k points.
     */
    const val MAX_POINTS = 1_000_000

    private const val DEFAULT_GREY = 0.85f

    /** Format auto-detected from filename and/or magic header bytes. */
    enum class Format { PLY, PCD }

    /**
     * Parse a point cloud from [input]. Caller is responsible for closing the
     * stream when this method returns.
     *
     * @throws IOException for malformed headers or unreadable data
     * @throws IllegalArgumentException if the format is unsupported
     */
    @JvmStatic
    @Throws(IOException::class)
    fun parse(
        input: InputStream,
        format: Format,
    ): PointCloud {
        return when (format) {
            Format.PLY -> parsePly(input)
            Format.PCD -> parsePcd(input)
        }
    }

    /**
     * Heuristic format detection by file extension. Returns null when the
     * extension does not match a supported format.
     */
    @JvmStatic
    fun formatFor(filename: String): Format? {
        val lower = filename.lowercase()
        return when {
            lower.endsWith(".ply") -> Format.PLY
            lower.endsWith(".pcd") -> Format.PCD
            else -> null
        }
    }

    // ---------------------------------------------------------------------
    // PLY
    // ---------------------------------------------------------------------

    @Throws(IOException::class)
    private fun parsePly(input: InputStream): PointCloud {
        // PLY headers are always ASCII regardless of the body format. Read
        // bytes until we see the "end_header" token followed by ANY line
        // terminator (LF, CRLF, or bare CR) — the PLY spec allows either
        // and Windows-authored files commonly produce CRLF. We wrap the
        // stream in PushbackInputStream so when a CRLF terminator is hit
        // we can consume the trailing LF cleanly without leaving stray
        // bytes that would corrupt the binary body.
        val pushback = PushbackInputStream(input, 2)
        val headerBytes = ByteArrayOutputStream()
        val token = "end_header".toByteArray()
        var sawToken = false
        while (true) {
            val b = pushback.read()
            if (b < 0) throw IOException("Unexpected EOF before PLY end_header")
            headerBytes.write(b)
            if (!sawToken && tailEquals(headerBytes.toByteArray(), token)) {
                sawToken = true
                continue
            }
            if (sawToken) {
                if (b == '\n'.code) break
                if (b == '\r'.code) {
                    val next = pushback.read()
                    if (next >= 0 && next != '\n'.code) {
                        // Bare CR terminator (rare, mac-classic); push the
                        // next byte back so the body reader sees it.
                        pushback.unread(next)
                    }
                    break
                }
                // Any other byte after "end_header" before a terminator
                // means we matched too eagerly (e.g. inside a comment).
                sawToken = false
            }
        }
        val header = headerBytes.toString("US-ASCII")
        val parsedHeader = parsePlyHeader(header)
        return when (parsedHeader.format) {
            PlyFormat.ASCII -> readPlyAscii(pushback, parsedHeader)
            PlyFormat.BINARY_LITTLE_ENDIAN -> readPlyBinary(pushback, parsedHeader, ByteOrder.LITTLE_ENDIAN)
            PlyFormat.BINARY_BIG_ENDIAN -> readPlyBinary(pushback, parsedHeader, ByteOrder.BIG_ENDIAN)
        }
    }

    private enum class PlyFormat { ASCII, BINARY_LITTLE_ENDIAN, BINARY_BIG_ENDIAN }

    private data class PlyProperty(val name: String, val type: String)

    private data class PlyElement(
        val name: String,
        val count: Int,
        val properties: List<PlyProperty>,
    )

    private data class PlyHeader(
        val format: PlyFormat,
        val elements: List<PlyElement>,
    )

    private fun parsePlyHeader(header: String): PlyHeader {
        val lines = header.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.firstOrNull() != "ply") {
            throw IOException("Missing 'ply' magic on first line")
        }
        var format: PlyFormat? = null
        val elements = mutableListOf<PlyElement>()
        var currentName: String? = null
        var currentCount = 0
        var currentProps = mutableListOf<PlyProperty>()
        for (line in lines.drop(1)) {
            when {
                line.startsWith("format ") -> {
                    val parts = line.split(' ')
                    format =
                        when (parts.getOrNull(1)) {
                            "ascii" -> PlyFormat.ASCII
                            "binary_little_endian" -> PlyFormat.BINARY_LITTLE_ENDIAN
                            "binary_big_endian" -> PlyFormat.BINARY_BIG_ENDIAN
                            else -> throw IOException("Unsupported PLY format: $line")
                        }
                }
                line.startsWith("comment ") || line.startsWith("obj_info ") -> Unit
                line.startsWith("element ") -> {
                    if (currentName != null) {
                        elements.add(PlyElement(currentName, currentCount, currentProps))
                    }
                    val parts = line.split(' ')
                    currentName = parts.getOrNull(1) ?: throw IOException("Bad element line")
                    currentCount = parts.getOrNull(2)?.toIntOrNull() ?: throw IOException("Bad element count")
                    currentProps = mutableListOf()
                }
                line.startsWith("property ") -> {
                    val parts = line.split(' ')
                    if (parts.size < 3) throw IOException("Bad property line: $line")
                    if (parts[1] == "list") {
                        // list properties (e.g. face vertex indices) consume
                        // a count then values per row; we record them with a
                        // type marker so the body reader can skip the row.
                        currentProps.add(PlyProperty(parts.last(), "list"))
                    } else {
                        currentProps.add(PlyProperty(parts.last(), parts[1]))
                    }
                }
                line == "end_header" -> Unit
            }
        }
        if (currentName != null) {
            elements.add(PlyElement(currentName, currentCount, currentProps))
        }
        if (format == null) throw IOException("PLY header missing format directive")
        return PlyHeader(format, elements)
    }

    private fun readPlyAscii(
        input: InputStream,
        header: PlyHeader,
    ): PointCloud {
        val vertexElement =
            header.elements.firstOrNull { it.name == "vertex" }
                ?: throw IOException("PLY missing vertex element")
        val xIdx = vertexElement.properties.indexOfFirst { it.name == "x" }
        val yIdx = vertexElement.properties.indexOfFirst { it.name == "y" }
        val zIdx = vertexElement.properties.indexOfFirst { it.name == "z" }
        if (xIdx < 0 || yIdx < 0 || zIdx < 0) {
            throw IOException("PLY vertex element missing x/y/z properties")
        }
        val rIdx = vertexElement.properties.indexOfFirst { it.name == "red" }
        val gIdx = vertexElement.properties.indexOfFirst { it.name == "green" }
        val bIdx = vertexElement.properties.indexOfFirst { it.name == "blue" }
        val hasColor = rIdx >= 0 && gIdx >= 0 && bIdx >= 0

        val reader = BufferedReader(InputStreamReader(input, Charsets.US_ASCII))
        // The PLY spec stores elements in the body in the order they were
        // declared in the header. If anything (face, edge, metadata...) was
        // declared before "vertex", we have to consume that many lines first
        // or vertex parsing reads the wrong rows.
        for (e in header.elements) {
            if (e.name == "vertex") break
            for (i in 0 until e.count) {
                if (reader.readLine() == null) {
                    throw IOException("PLY truncated while skipping element ${e.name}")
                }
            }
        }
        val stride = strideFor(vertexElement.count)
        val capacity = (vertexElement.count + stride - 1) / stride
        val positions = FloatArray(capacity * 3)
        val colors = FloatArray(capacity * 3)
        val min = floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        val max = floatArrayOf(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        var written = 0
        for (i in 0 until vertexElement.count) {
            val line = reader.readLine() ?: break
            if (i % stride != 0) continue
            val tokens = line.trim().split(Regex("\\s+"))
            val x = tokens[xIdx].toFloat()
            val y = tokens[yIdx].toFloat()
            val z = tokens[zIdx].toFloat()
            positions[written * 3] = x
            positions[written * 3 + 1] = y
            positions[written * 3 + 2] = z
            updateBounds(min, max, x, y, z)
            if (hasColor) {
                colors[written * 3] = (tokens[rIdx].toInt() and 0xFF) / 255f
                colors[written * 3 + 1] = (tokens[gIdx].toInt() and 0xFF) / 255f
                colors[written * 3 + 2] = (tokens[bIdx].toInt() and 0xFF) / 255f
            } else {
                colors[written * 3] = DEFAULT_GREY
                colors[written * 3 + 1] = DEFAULT_GREY
                colors[written * 3 + 2] = DEFAULT_GREY
            }
            written++
        }
        return PointCloud(
            positions = positions.copyOf(written * 3),
            colors = colors.copyOf(written * 3),
            pointCount = written,
            minBounds = min,
            maxBounds = max,
            downsampled = stride > 1,
        )
    }

    @Throws(IOException::class)
    private fun readPlyBinary(
        input: InputStream,
        header: PlyHeader,
        order: ByteOrder,
    ): PointCloud {
        val vertexElement =
            header.elements.firstOrNull { it.name == "vertex" }
                ?: throw IOException("PLY missing vertex element")
        val xIdx = vertexElement.properties.indexOfFirst { it.name == "x" }
        val yIdx = vertexElement.properties.indexOfFirst { it.name == "y" }
        val zIdx = vertexElement.properties.indexOfFirst { it.name == "z" }
        if (xIdx < 0 || yIdx < 0 || zIdx < 0) {
            throw IOException("PLY vertex element missing x/y/z properties")
        }
        val rIdx = vertexElement.properties.indexOfFirst { it.name == "red" }
        val gIdx = vertexElement.properties.indexOfFirst { it.name == "green" }
        val bIdx = vertexElement.properties.indexOfFirst { it.name == "blue" }
        val hasColor = rIdx >= 0 && gIdx >= 0 && bIdx >= 0

        val rowSize = vertexElement.properties.sumOf { sizeOfPlyType(it.type) }
        if (rowSize <= 0) throw IOException("PLY vertex row contains list/unknown property")
        // Same ordering rule as the ASCII path: skip body bytes for any
        // element declared before vertex. Variable-length list properties
        // can't be skipped without parsing each row, so we reject those
        // upfront — none of the consumer scanners we target produce a
        // pre-vertex element with a list property.
        for (e in header.elements) {
            if (e.name == "vertex") break
            val rs = e.properties.sumOf { sizeOfPlyType(it.type) }
            if (rs <= 0) {
                throw IOException("PLY element ${e.name} before vertex has variable-size rows")
            }
            skipFully(input, rs.toLong() * e.count.toLong())
        }
        val rowBytes = ByteArray(rowSize)
        val rowBuf = ByteBuffer.wrap(rowBytes).order(order)
        val stride = strideFor(vertexElement.count)
        val capacity = (vertexElement.count + stride - 1) / stride
        val positions = FloatArray(capacity * 3)
        val colors = FloatArray(capacity * 3)
        val min = floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        val max = floatArrayOf(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        val offsets = IntArray(vertexElement.properties.size)
        var off = 0
        for (i in vertexElement.properties.indices) {
            offsets[i] = off
            off += sizeOfPlyType(vertexElement.properties[i].type)
        }
        var written = 0
        for (i in 0 until vertexElement.count) {
            readFully(input, rowBytes)
            if (i % stride != 0) continue
            val x = readPlyFloat(rowBuf, offsets[xIdx], vertexElement.properties[xIdx].type)
            val y = readPlyFloat(rowBuf, offsets[yIdx], vertexElement.properties[yIdx].type)
            val z = readPlyFloat(rowBuf, offsets[zIdx], vertexElement.properties[zIdx].type)
            positions[written * 3] = x
            positions[written * 3 + 1] = y
            positions[written * 3 + 2] = z
            updateBounds(min, max, x, y, z)
            if (hasColor) {
                colors[written * 3] = readPlyByteUnit(rowBuf, offsets[rIdx], vertexElement.properties[rIdx].type)
                colors[written * 3 + 1] = readPlyByteUnit(rowBuf, offsets[gIdx], vertexElement.properties[gIdx].type)
                colors[written * 3 + 2] = readPlyByteUnit(rowBuf, offsets[bIdx], vertexElement.properties[bIdx].type)
            } else {
                colors[written * 3] = DEFAULT_GREY
                colors[written * 3 + 1] = DEFAULT_GREY
                colors[written * 3 + 2] = DEFAULT_GREY
            }
            written++
        }
        return PointCloud(
            positions = positions.copyOf(written * 3),
            colors = colors.copyOf(written * 3),
            pointCount = written,
            minBounds = min,
            maxBounds = max,
            downsampled = stride > 1,
        )
    }

    private fun sizeOfPlyType(type: String): Int =
        when (type) {
            "char", "int8", "uchar", "uint8" -> 1
            "short", "int16", "ushort", "uint16" -> 2
            "int", "int32", "uint", "uint32", "float", "float32" -> 4
            "double", "float64" -> 8
            else -> -1
        }

    private fun readPlyFloat(
        buf: ByteBuffer,
        off: Int,
        type: String,
    ): Float =
        when (type) {
            "float", "float32" -> buf.getFloat(off)
            "double", "float64" -> buf.getDouble(off).toFloat()
            "int", "int32" -> buf.getInt(off).toFloat()
            "uint", "uint32" -> (buf.getInt(off).toLong() and 0xFFFFFFFFL).toFloat()
            "short", "int16" -> buf.getShort(off).toFloat()
            "ushort", "uint16" -> (buf.getShort(off).toInt() and 0xFFFF).toFloat()
            "char", "int8" -> buf.get(off).toFloat()
            "uchar", "uint8" -> (buf.get(off).toInt() and 0xFF).toFloat()
            else -> Float.NaN
        }

    private fun readPlyByteUnit(
        buf: ByteBuffer,
        off: Int,
        type: String,
    ): Float =
        when (type) {
            "uchar", "uint8" -> (buf.get(off).toInt() and 0xFF) / 255f
            "char", "int8" -> ((buf.get(off).toInt() and 0xFF)) / 255f
            "ushort", "uint16" -> (buf.getShort(off).toInt() and 0xFFFF) / 65535f
            else -> DEFAULT_GREY
        }

    // ---------------------------------------------------------------------
    // PCD (ASCII)
    // ---------------------------------------------------------------------

    @Throws(IOException::class)
    private fun parsePcd(input: InputStream): PointCloud {
        val reader = BufferedReader(InputStreamReader(input, Charsets.US_ASCII))
        var fields: List<String> = emptyList()
        var pointCount = -1
        var dataAscii = false
        // Read header lines until we see DATA <kind>; PCD spec keeps headers
        // line-oriented so a buffered reader works for ASCII bodies. Binary
        // PCD would need byte-level reading; we reject it explicitly.
        while (true) {
            val line = reader.readLine() ?: throw IOException("PCD header truncated before DATA")
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val parts = trimmed.split(Regex("\\s+"))
            when (parts[0].uppercase()) {
                "FIELDS" -> fields = parts.drop(1)
                "POINTS" -> pointCount = parts[1].toInt()
                "DATA" -> {
                    dataAscii = parts[1].equals("ascii", ignoreCase = true)
                    break
                }
            }
        }
        if (!dataAscii) throw IOException("Only ASCII PCD is supported")
        if (pointCount < 0) throw IOException("PCD header missing POINTS")
        val xIdx = fields.indexOf("x")
        val yIdx = fields.indexOf("y")
        val zIdx = fields.indexOf("z")
        if (xIdx < 0 || yIdx < 0 || zIdx < 0) {
            throw IOException("PCD header missing x/y/z fields")
        }
        // PCD packs RGB into a single 4-byte float lane; the bit layout is
        // 0x00RRGGBB reinterpret-cast from float. Older PCL also writes
        // separate 'r','g','b' fields — we honour both.
        val rgbIdx = fields.indexOf("rgb")
        val rIdx = fields.indexOf("r")
        val gIdx = fields.indexOf("g")
        val bIdx = fields.indexOf("b")

        val stride = strideFor(pointCount)
        val capacity = (pointCount + stride - 1) / stride
        val positions = FloatArray(capacity * 3)
        val colors = FloatArray(capacity * 3)
        val min = floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        val max = floatArrayOf(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        var written = 0
        for (i in 0 until pointCount) {
            val line = reader.readLine() ?: break
            if (i % stride != 0) continue
            val tokens = line.trim().split(Regex("\\s+"))
            val x = tokens[xIdx].toFloat()
            val y = tokens[yIdx].toFloat()
            val z = tokens[zIdx].toFloat()
            positions[written * 3] = x
            positions[written * 3 + 1] = y
            positions[written * 3 + 2] = z
            updateBounds(min, max, x, y, z)
            when {
                rgbIdx >= 0 -> {
                    val packed = java.lang.Float.floatToRawIntBits(tokens[rgbIdx].toFloat())
                    colors[written * 3] = ((packed shr 16) and 0xFF) / 255f
                    colors[written * 3 + 1] = ((packed shr 8) and 0xFF) / 255f
                    colors[written * 3 + 2] = (packed and 0xFF) / 255f
                }
                rIdx >= 0 && gIdx >= 0 && bIdx >= 0 -> {
                    colors[written * 3] = (tokens[rIdx].toInt() and 0xFF) / 255f
                    colors[written * 3 + 1] = (tokens[gIdx].toInt() and 0xFF) / 255f
                    colors[written * 3 + 2] = (tokens[bIdx].toInt() and 0xFF) / 255f
                }
                else -> {
                    colors[written * 3] = DEFAULT_GREY
                    colors[written * 3 + 1] = DEFAULT_GREY
                    colors[written * 3 + 2] = DEFAULT_GREY
                }
            }
            written++
        }
        return PointCloud(
            positions = positions.copyOf(written * 3),
            colors = colors.copyOf(written * 3),
            pointCount = written,
            minBounds = min,
            maxBounds = max,
            downsampled = stride > 1,
        )
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun strideFor(count: Int): Int = if (count <= MAX_POINTS) 1 else (count + MAX_POINTS - 1) / MAX_POINTS

    private fun updateBounds(
        min: FloatArray,
        max: FloatArray,
        x: Float,
        y: Float,
        z: Float,
    ) {
        if (x < min[0]) min[0] = x
        if (y < min[1]) min[1] = y
        if (z < min[2]) min[2] = z
        if (x > max[0]) max[0] = x
        if (y > max[1]) max[1] = y
        if (z > max[2]) max[2] = z
    }

    @Throws(IOException::class)
    private fun readFully(
        input: InputStream,
        out: ByteArray,
    ) {
        var off = 0
        while (off < out.size) {
            val n = input.read(out, off, out.size - off)
            if (n <= 0) throw IOException("Unexpected EOF reading PLY body")
            off += n
        }
    }

    @Throws(IOException::class)
    private fun skipFully(
        input: InputStream,
        bytes: Long,
    ) {
        // InputStream.skip is allowed to return less than requested even
        // when the stream isn't at EOF. Loop until we've consumed exactly
        // [bytes], falling back to read() when skip returns 0.
        var remaining = bytes
        val sink = ByteArray(8192)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            val toRead = minOf(remaining, sink.size.toLong()).toInt()
            val n = input.read(sink, 0, toRead)
            if (n <= 0) throw IOException("Unexpected EOF skipping PLY element")
            remaining -= n
        }
    }

    private fun tailEquals(
        haystack: ByteArray,
        needle: ByteArray,
    ): Boolean {
        if (haystack.size < needle.size) return false
        for (i in needle.indices) {
            if (haystack[haystack.size - needle.size + i] != needle[i]) return false
        }
        return true
    }
}
