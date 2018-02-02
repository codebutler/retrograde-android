/*
 * SwGameView.kt
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

package com.codebutler.retrograde.lib.game.display.sw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.ImageView
import com.codebutler.retrograde.lib.game.display.FpsCalculator
import com.codebutler.retrograde.lib.game.display.GameDisplay
import kotlin.properties.Delegates

class SwGameDisplay(context: Context) : GameDisplay {

    private val fpsCalculator = FpsCalculator()
    private var isReady = false

    override val view: ImageView = ImageView(context)

    override val fps
        get() = fpsCalculator.fps

    override var readyCallback: (() -> Unit)? by Delegates.observable<(() -> Unit)?>(null) {
        prop, old, new -> if (isReady) { new?.invoke() }
    }

    init {
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View?) {
                readyCallback?.invoke()
                isReady = true
            }

            override fun onViewDetachedFromWindow(v: View?) {
                isReady = false
            }
        })
    }

    override fun render(bitmap: Bitmap) {
        view.handler.post {
            val drawable = BitmapDrawable(view.resources, bitmap)
            drawable.paint.isAntiAlias = false
            drawable.paint.isDither = false
            drawable.paint.isFilterBitmap = false
            view.setImageDrawable(drawable)
            fpsCalculator.update()
        }
    }
}
