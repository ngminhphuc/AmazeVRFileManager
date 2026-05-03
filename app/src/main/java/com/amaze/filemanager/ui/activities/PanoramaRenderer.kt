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

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * OpenGL ES 2.0 renderer that maps an equirectangular source bitmap onto the
 * inside of a UV-sphere and exposes yaw / pitch / FOV so a hosting activity can
 * wire touch / controller input to look-direction changes.
 *
 * Only monoscopic equirectangular 2:1 images are handled. Stereoscopic
 * (SBS/OU) panoramas render correctly only if the caller pre-crops the half
 * they want — the renderer itself does not split frames.
 *
 * Sphere geometry is cached once for the lifetime of the renderer; the texture
 * is reloaded whenever [setBitmap] is called (on the GL thread via [queueEvent]).
 */
internal class PanoramaRenderer : GLSurfaceView.Renderer {
    enum class Projection { EQUIRECT_360, EQUIRECT_180 }

    @Volatile var yawRad: Float = 0f

    @Volatile var pitchRad: Float = 0f

    @Volatile var fovDeg: Float = 75f

    @Volatile var projection: Projection = Projection.EQUIRECT_360

    // Invoked on the GL thread at the end of [onSurfaceCreated] once a new
    // texture handle is live. The hosting activity uses it to re-queue a
    // bitmap upload when the EGL context was destroyed during pause.
    @Volatile var onContextRecreated: (() -> Unit)? = null

    private var pendingBitmap: Bitmap? = null
    private var programHandle = 0
    private var positionHandle = 0
    private var uvHandle = 0
    private var mvpHandle = 0
    private var textureHandle = 0
    private var textureUniform = 0
    private var viewportWidth = 1
    private var viewportHeight = 1

    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var uvBuffer: FloatBuffer
    private lateinit var indexBuffer: ShortBuffer
    private var indexCount = 0

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val tmpMatrix = FloatArray(16)

    /**
     * Queue a bitmap for upload to the GL texture on the next frame. Callers
     * must invoke this from the GL thread (e.g. via GLSurfaceView.queueEvent).
     * The bitmap is retained but not recycled here — recycle on the caller side
     * after the next render pass if needed.
     */
    fun setBitmap(bitmap: Bitmap) {
        pendingBitmap = bitmap
    }

    override fun onSurfaceCreated(
        gl: GL10?,
        config: EGLConfig?,
    ) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        // Render back face so we can sit inside the sphere and look at the inside wall.
        GLES20.glCullFace(GLES20.GL_FRONT)

        programHandle = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(programHandle, "aPosition")
        uvHandle = GLES20.glGetAttribLocation(programHandle, "aUv")
        mvpHandle = GLES20.glGetUniformLocation(programHandle, "uMvp")
        textureUniform = GLES20.glGetUniformLocation(programHandle, "uTex")

        buildSphere(STACKS, SECTORS)

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureHandle = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )

        // Signal the host so it can re-upload the bitmap whenever the EGL
        // context was destroyed (GLSurfaceView default on pause on some
        // devices). Without this the sphere would render black on resume.
        onContextRecreated?.invoke()
    }

    override fun onSurfaceChanged(
        gl: GL10?,
        width: Int,
        height: Int,
    ) {
        viewportWidth = width
        viewportHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        pendingBitmap?.let { bmp ->
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            pendingBitmap = null
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val aspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        val fov = fovDeg.coerceIn(30f, 110f)
        Matrix.perspectiveM(projectionMatrix, 0, fov, aspect, 0.1f, 100f)

        Matrix.setIdentityM(viewMatrix, 0)
        // Pitch then yaw (intrinsic X then Y) so horizontal drag is always level.
        Matrix.rotateM(viewMatrix, 0, Math.toDegrees(pitchRad.toDouble()).toFloat(), 1f, 0f, 0f)
        Matrix.rotateM(viewMatrix, 0, Math.toDegrees(yawRad.toDouble()).toFloat(), 0f, 1f, 0f)

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.multiplyMM(tmpMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, tmpMatrix, 0)

        GLES20.glUseProgram(programHandle)
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle)
        GLES20.glUniform1i(textureUniform, 0)

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glEnableVertexAttribArray(uvHandle)
        GLES20.glVertexAttribPointer(uvHandle, 2, GLES20.GL_FLOAT, false, 0, uvBuffer)

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(uvHandle)
    }

    private fun buildSphere(
        stacks: Int,
        sectors: Int,
    ) {
        val vertexData = FloatArray((stacks + 1) * (sectors + 1) * 3)
        val uvData = FloatArray((stacks + 1) * (sectors + 1) * 2)
        var v = 0
        var u = 0
        for (stack in 0..stacks) {
            val stackT = stack.toFloat() / stacks
            val phi = (PI * stackT).toFloat() // 0..pi (top to bottom)
            val sinPhi = sin(phi)
            val cosPhi = cos(phi)
            for (sector in 0..sectors) {
                val sectorT = sector.toFloat() / sectors
                // theta: for 360° full sweep, for 180° half sweep (range -90° .. +90°).
                val theta =
                    if (projection == Projection.EQUIRECT_360) {
                        (2f * PI.toFloat()) * sectorT
                    } else {
                        PI.toFloat() * sectorT - (PI.toFloat() / 2f)
                    }
                val sinTheta = sin(theta)
                val cosTheta = cos(theta)
                // Sphere pointing outward along +Z for sector=0; radius 1.
                vertexData[v++] = sinPhi * sinTheta
                vertexData[v++] = cosPhi
                vertexData[v++] = sinPhi * cosTheta

                // For 360° the bitmap wraps full [0,1]; for 180° we place content in the middle
                // half of U (0.25..0.75) is a common encoding; we keep 0..1 over the half sphere
                // so a VR180-cropped bitmap fills the view naturally.
                uvData[u++] = sectorT
                uvData[u++] = stackT
            }
        }

        val indices = ShortArray(stacks * sectors * 6)
        var i = 0
        for (stack in 0 until stacks) {
            for (sector in 0 until sectors) {
                val first = (stack * (sectors + 1) + sector).toShort()
                val second = (first + sectors + 1).toShort()
                indices[i++] = first
                indices[i++] = second
                indices[i++] = (first + 1).toShort()
                indices[i++] = second
                indices[i++] = (second + 1).toShort()
                indices[i++] = (first + 1).toShort()
            }
        }

        vertexBuffer =
            ByteBuffer.allocateDirect(vertexData.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().put(vertexData).position(0) as FloatBuffer
        uvBuffer =
            ByteBuffer.allocateDirect(uvData.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().put(uvData).position(0) as FloatBuffer
        indexBuffer =
            ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder())
                .asShortBuffer().put(indices).position(0) as ShortBuffer
        indexCount = indices.size
    }

    /**
     * Rebuild the sphere geometry when switching between 360° and 180°. The
     * existing texture binding is kept because U/V mapping regenerates too.
     * Must run on the GL thread (i.e. from GLSurfaceView.queueEvent).
     */
    fun rebuildSphereForProjection() {
        buildSphere(STACKS, SECTORS)
    }

    private fun buildProgram(
        vs: String,
        fs: String,
    ): Int {
        val vShader = compileShader(GLES20.GL_VERTEX_SHADER, vs)
        val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fs)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vShader)
        GLES20.glAttachShader(program, fShader)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) {
            val info = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            "Panorama program link failed: $info"
        }
        return program
    }

    private fun compileShader(
        type: Int,
        source: String,
    ): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] != 0) {
            val info = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            "Panorama shader compile failed: $info"
        }
        return shader
    }

    companion object {
        private const val STACKS = 32
        private const val SECTORS = 64

        private val VERTEX_SHADER =
            """
            uniform mat4 uMvp;
            attribute vec4 aPosition;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
              gl_Position = uMvp * aPosition;
              vUv = aUv;
            }
            """.trimIndent()

        private val FRAGMENT_SHADER =
            """
            precision mediump float;
            uniform sampler2D uTex;
            varying vec2 vUv;
            void main() {
              gl_FragColor = texture2D(uTex, vUv);
            }
            """.trimIndent()
    }
}
