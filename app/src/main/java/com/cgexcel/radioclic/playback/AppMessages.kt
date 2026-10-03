/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Messages courts destinés à l'utilisateur (élément sauté, erreur réseau…).
 * Affichés dans l'écran principal s'il est visible, sinon sous forme de toast.
 */
object AppMessages {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Vrai quand l'écran principal est au premier plan et collecte les messages. */
    @Volatile
    var uiVisible: Boolean = false

    fun show(context: Context, text: String) {
        if (text.isBlank()) return
        if (uiVisible && _messages.subscriptionCount.value > 0 && _messages.tryEmit(text)) return
        val appContext = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, text, Toast.LENGTH_LONG).show()
        }
    }
}
