package com.codebutler.retrograde.lib.game.display.gl

import android.opengl.GLES20
import timber.log.Timber

object GlUtil {

    // From: https://github.com/google/grafika/blob/master/src/com/android/grafika/gles/GlUtil.java
    fun checkGlError(op: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            val msg = op + ": glError 0x" + Integer.toHexString(error)
            Timber.e(msg)
            throw RuntimeException(msg)
        }
    }
}
