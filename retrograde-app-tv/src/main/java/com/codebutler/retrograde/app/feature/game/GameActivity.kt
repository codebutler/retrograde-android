/*
 * GameActivity.kt
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
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.codebutler.retrograde.app.feature.game

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.codebutler.retrograde.BuildConfig
import com.codebutler.retrograde.R
import com.codebutler.retrograde.common.kotlin.bindView
import com.codebutler.retrograde.common.kotlin.isAllZeros
import com.codebutler.retrograde.lib.android.RetrogradeActivity
import com.codebutler.retrograde.lib.core.CoreManager
import com.codebutler.retrograde.lib.game.GameLoader
import com.codebutler.retrograde.lib.game.audio.GameAudio
import com.codebutler.retrograde.lib.game.display.GameDisplay
import com.codebutler.retrograde.lib.game.display.gl.GlGameDisplay
import com.codebutler.retrograde.lib.game.display.sw.SwGameDisplay
import com.codebutler.retrograde.lib.library.GameLibrary
import com.codebutler.retrograde.lib.library.db.RetrogradeDatabase
import com.codebutler.retrograde.lib.library.db.entity.Game
import com.codebutler.retrograde.lib.retro.RetroDroid
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.kotlin.autoDisposable
import dagger.Provides
import io.reactivex.Completable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import timber.log.Timber
import javax.inject.Inject

class GameActivity : RetrogradeActivity() {
    companion object {
        private const val EXTRA_GAME_ID = "game_id"

        fun newIntent(context: Context, game: Game) =
                Intent(context, GameActivity::class.java).apply {
            putExtra(EXTRA_GAME_ID, game.id)
        }
    }

    @Inject lateinit var gameLibrary: GameLibrary
    @Inject lateinit var gameLoader: GameLoader

    private val progressBar by bindView<ProgressBar>(R.id.progress)
    private val gameDisplayLayout by bindView<FrameLayout>(R.id.game_display_layout)

    private lateinit var gameDisplay: GameDisplay

    private var game: Game? = null
    private var retroDroid: RetroDroid? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val enableOpengl = prefs.getBoolean(getString(R.string.pref_key_flags_opengl), false)

        gameDisplay = if (enableOpengl) {
            GlGameDisplay(this)
        } else {
            SwGameDisplay(this)
        }

        gameDisplay.readyCallback = cb@ {
            // FIXME: Full Activity lifecycle handling.
            if (savedInstanceState != null) {
                return@cb
            }

            gameDisplayLayout.post {
                val gameId = intent.getIntExtra(EXTRA_GAME_ID, -1)
                gameLoader.load(gameId)
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .autoDisposable(scope())
                        .subscribe(
                                { data ->
                                    progressBar.visibility = View.GONE
                                    loadRetro(data)
                                },
                                { error ->
                                    Timber.e(error, "Failed to load game")
                                    finish()
                                })
            }
        }

        gameDisplayLayout.addView(gameDisplay.view, MATCH_PARENT, MATCH_PARENT)
        lifecycle.addObserver(gameDisplay)

        if (BuildConfig.DEBUG) {
            addFpsView()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // This activity runs in its own process which should not live beyond the activity lifecycle.
        System.exit(0)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        super.dispatchGenericMotionEvent(event)
        retroDroid?.onMotionEvent(event)
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        super.dispatchKeyEvent(event)
        retroDroid?.onKeyEvent(event)
        return true
    }

    private fun addFpsView() {
        val frameLayout = findViewById<FrameLayout>(R.id.game_layout)

        val fpsView = TextView(this)
        fpsView.textSize = 18f
        fpsView.setTextColor(Color.WHITE)
        fpsView.setShadowLayer(2f, 0f, 0f, Color.BLACK)

        frameLayout.addView(fpsView)

        fun updateFps() {
            fpsView.text = getString(R.string.fps_format, gameDisplay.fps, retroDroid?.fps ?: 0L)
            fpsView.postDelayed({ updateFps() }, 1000)
        }
        updateFps()
    }

    private fun loadRetro(data: GameLoader.GameData) {
        try {
            val retroDroid = RetroDroid(gameDisplay, GameAudio(), this, data.coreFile)
            lifecycle.addObserver(retroDroid)

            retroDroid.gameUnloadedCallback = { saveData ->
                val game = this.game
                val saveCompletable = if (saveData != null && saveData.isAllZeros().not() && game != null) {
                    gameLibrary.setGameSave(game, saveData)
                } else {
                    Completable.complete()
                }
                saveCompletable
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe {
                            setResult(Activity.RESULT_OK)
                            finish()
                        }
            }

            retroDroid.loadGame(data.gameFile.absolutePath, data.saveData)
            retroDroid.start()

            this.game = data.game
            this.retroDroid = retroDroid
        } catch (ex: Exception) {
            Timber.e(ex, "Exception during retro initialization")
            finish()
        }
    }

    @dagger.Module
    class Module {

        @Provides
        fun gameLoader(coreManager: CoreManager, retrogradeDatabase: RetrogradeDatabase, gameLibrary: GameLibrary) =
                GameLoader(coreManager, retrogradeDatabase, gameLibrary)
    }
}
