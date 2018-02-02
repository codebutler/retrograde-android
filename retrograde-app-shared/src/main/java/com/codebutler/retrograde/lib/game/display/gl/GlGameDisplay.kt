/*
 * GameGlSurfaceView.kt
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

import android.arch.lifecycle.LifecycleOwner
import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.view.Choreographer
import com.codebutler.retrograde.lib.game.display.FpsCalculator
import com.codebutler.retrograde.lib.game.display.GameDisplay
import kotlin.properties.Delegates

class GlGameDisplay(context: Context) : GameDisplay, Choreographer.FrameCallback {

    private val glSurfaceView = GLSurfaceView(context)
    private val renderer = GlRenderer2d()
    private val fpsCalculator = FpsCalculator()

    private var isReady = false

    val glVersion
        get() = renderer.glVersion!!

    init {
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        renderer.createdCallback = {
            isReady = true
            readyCallback?.invoke()
        }

        renderer.drawCallback = {
            fpsCalculator.update()
        }
    }

    override var readyCallback: (() -> Unit)? by Delegates.observable<(() -> Unit)?>(null) {
        prop, old, new -> if (isReady) { new?.invoke() }
    }

    override val view = glSurfaceView

    override val fps: Long
        get() = fpsCalculator.fps

    override fun render(bitmap: Bitmap) {
        renderer.setBitmap(bitmap)
    }

    val currentHwFramebuffer: Int?
        get() = renderer.fboId

    fun renderHw(width: Int, height: Int) {
        renderer.setHwWidthHeight(width, height)
    }

    override fun onResume(owner: LifecycleOwner) {
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onPause(owner: LifecycleOwner) {
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        Choreographer.getInstance().postFrameCallback(this)
        view.requestRender()
    }
}
