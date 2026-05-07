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

package com.amaze.filemanager.filesystem.smb

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import java.io.IOException

/**
 * Media3/ExoPlayer [androidx.media3.datasource.DataSource] implementation backed by
 * jcifs-ng so video files sitting on an SMB share can be streamed directly to
 * the VR video player without being fully downloaded first.
 *
 * Range requests ([DataSpec.position] + [DataSpec.length]) are translated into
 * [SmbRandomAccessFile.seek] calls, enabling seek-while-playing behaviour.
 */
@OptIn(UnstableApi::class)
class SmbDataSource : BaseDataSource(true) {
    private var uri: Uri? = null
    private var smbFile: SmbFile? = null
    private var raf: SmbRandomAccessFile? = null
    private var bytesRemaining: Long = 0L
    private var opened: Boolean = false

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val u = dataSpec.uri
        this.uri = u
        val smbUrl = u.toString()
        val ctx = CifsContexts.create(smbUrl, null)
        // Keep the SmbFile alive for the lifetime of this data source.
        // jcifs-ng's SmbRandomAccessFile delegates I/O through the SmbFile
        // it was constructed with (this.file.read(...)), so closing the
        // SmbFile while raf is still in use causes subsequent reads to
        // fail. We close both in close() below in the right order.
        val file = SmbFile(smbUrl, ctx)
        this.smbFile = file
        try {
            val length = file.length()
            val randomAccessFile = SmbRandomAccessFile(file, "r")
            // Assign immediately so close() will clean up if seek() below throws.
            this.raf = randomAccessFile
            if (dataSpec.position > 0) {
                randomAccessFile.seek(dataSpec.position)
            }
            bytesRemaining =
                if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                    length - dataSpec.position
                } else {
                    dataSpec.length
                }
            if (bytesRemaining < 0) {
                throw IOException("Invalid SMB data spec: negative remaining length")
            }
        } catch (e: Exception) {
            close()
            throw if (e is IOException) e else IOException(e)
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    @Throws(IOException::class)
    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) {
            return 0
        }
        if (bytesRemaining == 0L) {
            return C.RESULT_END_OF_INPUT
        }
        val toRead = Math.min(length.toLong(), bytesRemaining).toInt()
        val read = raf?.read(buffer, offset, toRead) ?: -1
        if (read == -1) {
            return C.RESULT_END_OF_INPUT
        }
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    @Throws(IOException::class)
    override fun close() {
        // Close raf first so any pending I/O completes against a still-live
        // SmbFile, then release the SmbFile handle itself.
        try {
            raf?.close()
        } catch (_: Exception) {
        }
        try {
            smbFile?.close()
        } catch (_: Exception) {
        }
        raf = null
        smbFile = null
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
