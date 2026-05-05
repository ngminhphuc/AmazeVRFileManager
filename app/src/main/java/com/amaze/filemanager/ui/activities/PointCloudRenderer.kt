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

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.amaze.filemanager.filesystem.pointcloud.PointCloud
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 2.0 renderer for [PointCloud] data.
 *
 * Geometry is uploaded to two interleaved [FloatBuffer]s (positions + colours)
 * and drawn as `GL_POINTS`. The vertex shader sets `gl_PointSize` so points
 * scale a little with distance — flat sprites are good enough for the LiDAR
 * scans this viewer targets and avoids shipping a custom billboard mesh.
 *
 * Camera state (yaw / pitch / distance) is owned by the activity and pushed
 * via volatile fields; nothing here is thread-safe beyond that. All GL calls
 * run on the [GLSurfaceView] thread.
 */
internal class PointCloudRenderer : GLSurfaceView.Renderer {
    @Volatile var yawRad: Float = 0f

    @Volatile var pitchRad: Float = 0f

    /** Distance from cloud centroid to the camera, in cloud units. */
    @Volatile var distance: Float = 3f

    /**
     * Emitted on the GL thread once a fresh GL context is up so the host can
     * re-queue the cloud buffers. Without this re-upload the surface stays
     * empty after onPause/onResume on devices that don't preserve the
     * context.
     */
    @Volatile var onContextRecreated: (() -> Unit)? = null

    private var pendingCloud: PointCloud? = null

    private var programHandle = 0
    private var positionHandle = 0
    private var colorHandle = 0
    private var mvpHandle = 0
    private var pointSizeHandle = 0

    private var vertexBuffer: FloatBuffer? = null
    private var colorBuffer: FloatBuffer? = null
    private var pointCount = 0

    private val center = floatArrayOf(0f, 0f, 0f)
    private var radius = 1f

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val tmpMatrix = FloatArray(16)

    private var viewportWidth = 1
    private var viewportHeight = 1

    /**
     * Hand a parsed cloud to the renderer. Must be invoked from the GL
     * thread, e.g. via `GLSurfaceView.queueEvent`. The buffers are uploaded
     * during the next [onDrawFrame].
     */
    fun setCloud(cloud: PointCloud) {
        pendingCloud = cloud
        // Centre and radius are computed once on upload so the camera can
        // frame the cloud reproducibly across context recreations.
        val cx = (cloud.minBounds[0] + cloud.maxBounds[0]) * 0.5f
        val cy = (cloud.minBounds[1] + cloud.maxBounds[1]) * 0.5f
        val cz = (cloud.minBounds[2] + cloud.maxBounds[2]) * 0.5f
        val dx = cloud.maxBounds[0] - cloud.minBounds[0]
        val dy = cloud.maxBounds[1] - cloud.minBounds[1]
        val dz = cloud.maxBounds[2] - cloud.minBounds[2]
        center[0] = cx
        center[1] = cy
        center[2] = cz
        radius = maxOf(dx, dy, dz, 1e-3f) * 0.5f
        if (distance <= 0f) distance = radius * 3f
    }

    override fun onSurfaceCreated(
        gl: GL10?,
        config: EGLConfig?,
    ) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        programHandle = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(programHandle, "aPosition")
        colorHandle = GLES20.glGetAttribLocation(programHandle, "aColor")
        mvpHandle = GLES20.glGetUniformLocation(programHandle, "uMvp")
        pointSizeHandle = GLES20.glGetUniformLocation(programHandle, "uPointSize")

        // Buffers don't survive a context destruction; push them away so the
        // host re-uploads on the next setCloud call.
        vertexBuffer = null
        colorBuffer = null
        pointCount = 0
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
        pendingCloud?.let { cloud ->
            vertexBuffer =
                ByteBuffer
                    .allocateDirect(cloud.positions.size * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
                    .apply {
                        put(cloud.positions)
                        position(0)
                    }
            colorBuffer =
                ByteBuffer
                    .allocateDirect(cloud.colors.size * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
                    .apply {
                        put(cloud.colors)
                        position(0)
                    }
            pointCount = cloud.pointCount
            pendingCloud = null
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (pointCount == 0) return
        val verts = vertexBuffer ?: return
        val cols = colorBuffer ?: return

        val aspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        // Near plane scales with cloud radius so very small or very large
        // clouds both stay visible without z-fighting at the centre.
        val near = (radius * 0.01f).coerceAtLeast(0.001f)
        val far = (radius * 50f).coerceAtLeast(near + 10f)
        Matrix.perspectiveM(projectionMatrix, 0, 60f, aspect, near, far)

        // Orbital camera: start distance away on −Z, rotate by yaw/pitch
        // around the cloud centroid. Identity model matrix; we move the
        // view to the camera instead of translating points.
        Matrix.setIdentityM(viewMatrix, 0)
        Matrix.translateM(viewMatrix, 0, 0f, 0f, -distance)
        Matrix.rotateM(viewMatrix, 0, Math.toDegrees(pitchRad.toDouble()).toFloat(), 1f, 0f, 0f)
        Matrix.rotateM(viewMatrix, 0, Math.toDegrees(yawRad.toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.translateM(viewMatrix, 0, -center[0], -center[1], -center[2])

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.multiplyMM(tmpMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, tmpMatrix, 0)

        GLES20.glUseProgram(programHandle)
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        // Heuristic point size: 6 px for a tightly framed cloud, bigger when
        // zoomed in. Capped so a tiny zoom-out doesn't make the cloud invisible.
        val ratio = (radius / distance).coerceIn(0.05f, 1.5f)
        GLES20.glUniform1f(pointSizeHandle, (4f + 8f * ratio).coerceIn(2f, 14f))

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, verts)
        GLES20.glEnableVertexAttribArray(colorHandle)
        GLES20.glVertexAttribPointer(colorHandle, 3, GLES20.GL_FLOAT, false, 0, cols)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, pointCount)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
    }

    /** Reset orbit state so a Reset menu item can recentre on the cloud. */
    fun resetCamera() {
        yawRad = 0f
        pitchRad = 0f
        distance = radius * 3f
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
            "Point cloud program link failed: $info"
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
            "Point cloud shader compile failed: $info"
        }
        return shader
    }

    companion object {
        private val VERTEX_SHADER =
            """
            uniform mat4 uMvp;
            uniform float uPointSize;
            attribute vec4 aPosition;
            attribute vec3 aColor;
            varying vec3 vColor;
            void main() {
              gl_Position = uMvp * aPosition;
              gl_PointSize = uPointSize;
              vColor = aColor;
            }
            """.trimIndent()

        private val FRAGMENT_SHADER =
            """
            precision mediump float;
            varying vec3 vColor;
            void main() {
              // Soft round point: discard fragments outside the unit circle so
              // points look like dots instead of the default GL square sprite.
              vec2 d = gl_PointCoord - vec2(0.5);
              if (dot(d, d) > 0.25) discard;
              gl_FragColor = vec4(vColor, 1.0);
            }
            """.trimIndent()
    }
}
