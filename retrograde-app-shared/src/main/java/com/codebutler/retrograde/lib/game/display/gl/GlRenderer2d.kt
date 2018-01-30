/*
 * GlRenderer2d.kt
 *
 * Copyright (C) 2017 Retrograde Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.codebutler.retrograde.lib.game.display.gl

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import timber.log.Timber
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class GlRenderer2d : GLSurfaceView.Renderer {

    private var square: Square? = null
    private var fboTex = 0
    private var renderBufferId = 0

    private var fboWidth: Int = 0
    private var fboHeight: Int = 0

    private var imageWidth: Int = 0
    private var imageHeight: Int = 0

    var createdCallback: (() -> Unit)? = null
    var drawCallback: (() -> Unit)? = null

    var glVersion: GlVersion? = null
        private set

    var fboId = 0
        private set

    fun setBitmap(bitmap: Bitmap) {
        square?.bitmap = bitmap
    }

    fun setHwWidthHeight(width: Int, height: Int) {
        imageWidth = width
        imageHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        if (imageWidth == 0 || imageHeight == 0) {
            return
        }

        GLES20.glViewport(0, 0, fboWidth, fboHeight)
        GlUtil.checkGlError("glViewport")

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GlUtil.checkGlError("glClear")

        if (square?.bitmap != null) {
            square?.draw()
        } else {
            GlUtil.checkGlError("3d draw start")
            square?.draw(fboTex, imageWidth, imageHeight)
            GlUtil.checkGlError("3d draw end")
        }

        drawCallback?.invoke()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        square = Square()
        GLES20.glClearColor(0f, 0f, 0f, 1.0f)

        val params = IntArray(2)
        GLES20.glGetIntegerv(GLES30.GL_MAJOR_VERSION, params, 0)
        GLES20.glGetIntegerv(GLES30.GL_MINOR_VERSION, params, 1)
        glVersion = GlVersion(params[0], params[1])

        Timber.d("GOT GL VERSION ON CREATE!! $glVersion")

        createdCallback?.invoke()
    }

    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
        fboWidth = width
        fboHeight = height

        GLES20.glViewport(0, 0, width, height)
        GlUtil.checkGlError("glViewport")

        square?.setGlBounds(width, height)

        initFbo(width, height)
    }

    // From http://opengles2learning.blogspot.com/2014/02/render-to-texture-rtt.html
    private fun initFbo(fboWidth: Int, fboHeight: Int) {
        val temp = IntArray(1)

        //generate fbo id
        GLES20.glGenFramebuffers(1, temp, 0)
        GlUtil.checkGlError("glGenFramebuffers")
        fboId = temp[0]

        //generate texture
        GLES20.glGenTextures(1, temp, 0)
        GlUtil.checkGlError("glGenTextures")
        fboTex = temp[0]

        //generate render buffer
        GLES20.glGenRenderbuffers(1, temp, 0)
        GlUtil.checkGlError("glGenRenderbuffers")
        renderBufferId = temp[0]

        //Bind Frame buffer
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GlUtil.checkGlError("glBindFramebuffer")

        //Bind texture
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GlUtil.checkGlError("glBindTexture")

        //Define texture parameters
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, fboWidth, fboHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GlUtil.checkGlError("tex params")

        //Bind render buffer and define buffer dimension
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, renderBufferId)
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, fboWidth, fboHeight)
        GlUtil.checkGlError("glRenderbufferStorage")

        //Attach texture FBO color attachment
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0)
        GlUtil.checkGlError("glFramebufferTexture2D")

        //Attach render buffer to depth attachment
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, renderBufferId)
        GlUtil.checkGlError("glFramebufferRenderbuffer")

        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw Exception("incomplete fb $status")
        }

        GlUtil.checkGlError("fbo init done!!")

        //we are done, reset
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        GlUtil.checkGlError("fbo init end!!")
    }

    companion object {
        fun loadShader(type: Int, shaderCode: String): Int {
            // create a vertex shader type (GLES20.GL_VERTEX_SHADER)
            // or a fragment shader type (GLES20.GL_FRAGMENT_SHADER)
            val shader = GLES20.glCreateShader(type)

            // add the source code to the shader and compile it
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)

            return shader
        }
    }
}
