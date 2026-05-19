package org.librestore

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class TtsManager(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val ptBr = Locale("pt", "BR")
                val result = tts?.setLanguage(ptBr)
                ready = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                if (!ready) {
                    tts?.setLanguage(Locale.getDefault())
                    ready = true
                }
            }
        }
    }

    fun falar(texto: String) {
        if (!ready) return
        tts?.speak(texto, TextToSpeech.QUEUE_ADD, null, texto.hashCode().toString())
    }

    fun parar() {
        tts?.stop()
    }

    fun destruir() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
