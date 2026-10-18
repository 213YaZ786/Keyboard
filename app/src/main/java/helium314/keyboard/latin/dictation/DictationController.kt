// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.core.voice.Dictation
import helium314.keyboard.latin.core.voice.SpeechModel
import helium314.keyboard.latin.core.voice.Whisper
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsDestination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Dictation in the keyboard, the way iOS 26 does it: the keyboard stays, the mic turns into a stop button, the words
 * go into the field as they are recognised and typing is still possible (a key ends the dictation).
 * The engines are Modules' voice kit (core/voice/Dictation: the phone's own recognizer, or whisper.cpp with Silero's
 * voice detector); this class is the keyboard's side: the right to the microphone, the choice of engine, the words
 * into the field (InputLogic.dictationComposing and onTextInput), the stop button (SuggestionStripView.setDictating),
 * and the end of the dictation when the user types, the keyboard closes or the field changes.
 * Never in incognito mode nor in a password field (SettingsValues.mIncognitoModeEnabled covers both, as
 * isTranslateKeyShown in ToolbarUtils does for Translate).
 *
 * Sources: the microphone is asked for from an activity because an input method cannot ask, as Transcribro does in
 * its ActionRecognizeSpeechScreen
 * https://github.com/soupslurpr/Transcribro/blob/main/app/src/main/kotlin/dev/soupslurpr/transcribro/ui/action_recognize_speech/ActionRecognizeSpeechScreen.kt
 * (DictationPermissionActivity); the words appear in the field as Android's SpeechRecognizer clients show partial
 * results (RecognitionListener.onPartialResults,
 * https://developer.android.com/reference/android/speech/RecognitionListener#onPartialResults(android.os.Bundle)),
 * which HeliBoard's input logic takes as composing text (InputLogic, RichInputConnection.setComposingText
 * https://github.com/HeliBorg/HeliBoard/blob/main/app/src/main/java/helium314/keyboard/latin/RichInputConnection.java).
 * HeliBoard's own mic key switches to another voice input method (LatinIME.onEvent, RichInputMethodManager.switchToShortcutIme):
 * that is replaced here.
 */
class DictationController(private val ime: LatinIME) : Dictation.Listener {

    private val dictation = Dictation(ime)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The stop button shows: a dictation is listening. */
    var listening = false
        private set

    /** Words may still arrive (a tap on stop lets the last phrase be written out first). */
    private var accepting = false
    private var composing = false
    private var prefix = ""
    private var committing = false
    private var engine = Dictation.Engine.SYSTEM
    private var starting = false

    /** The tap on the mic: starts, or stops. */
    fun toggle() {
        if (listening) stop(flush = true) else start()
    }

    /** A key was typed, a suggestion picked: the dictation ends and what is shown stays as it is. */
    fun onTyping() {
        if (committing || !listening) return
        stop(flush = false)
    }

    /**
     * Ends the dictation. [flush]: the words still held are written out first (the user tapped stop); else they are
     * dropped (a key, the keyboard closing, another field).
     */
    fun stop(flush: Boolean) {
        if (!listening && !accepting) return
        listening = false
        ime.setDictating(false)
        if (flush) {
            dictation.stop(true)
        } else {
            accepting = false
            dictation.stop(false)
            finishComposing()
        }
    }

    /** The keyboard is going: nothing of the engines stays. */
    fun destroy() {
        stop(flush = false)
        scope.cancel()
    }

    private fun start() {
        if (starting || dictation.busy) return
        if (Settings.getValues().mIncognitoModeEnabled) return // incognito and password fields: no microphone
        if (ime.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askForMicrophone()
            return
        }
        starting = true
        scope.launch {
            try {
                val speech = KeyboardSpeech.model(ime)
                // The phone's recognizer is asked about the keyboard's language when that is not known yet
                if (!speech.system.value || KeyboardSpeech.languageChanged()) speech.recheck().join()
                val chosen = when {
                    speech.usesSystem -> Dictation.Engine.SYSTEM
                    Whisper.loaded && speech.state.value == SpeechModel.State.Ready && speech.file.exists() -> Dictation.Engine.WHISPER
                    else -> null
                }
                if (chosen == null) {
                    openDictationSettings() // no engine is ready: the settings choose one
                    return@launch
                }
                begin(chosen, speech)
            } finally {
                starting = false
            }
        }
    }

    private fun begin(chosen: Dictation.Engine, speech: SpeechModel) {
        engine = chosen
        ime.dictationBegin()
        composing = false
        prefix = ""
        accepting = true
        listening = true
        ime.setDictating(true)
        if (!dictation.start(chosen, KeyboardSpeech.language(), speech.file, this)) {
            listening = false
            accepting = false
            ime.setDictating(false)
        }
    }

    override fun partial(text: String) {
        if (!accepting) return
        if (!composing) {
            prefix = if (ime.dictationSpaceBefore() && !startsWithSign(text)) " " else ""
            composing = true
        }
        ime.dictationComposing(prefix + text)
    }

    override fun committed(text: String) {
        if (!accepting) return
        val lead = if (composing) prefix else if (ime.dictationSpaceBefore() && !startsWithSign(text)) " " else ""
        composing = false
        prefix = ""
        committing = true
        try {
            ime.onTextInput(lead + text)
        } finally {
            committing = false
        }
    }

    override fun stopped(why: Dictation.Why) {
        accepting = false
        val wasListening = listening
        listening = false
        if (wasListening) ime.setDictating(false)
        finishComposing()
        when (why) {
            Dictation.Why.NO_MICROPHONE_RIGHT -> askForMicrophone()
            Dictation.Why.UNAVAILABLE -> fallBack()
            else -> Unit
        }
    }

    /** The phone's recognizer cannot hear this language: the downloaded model takes over, else the settings open. */
    private fun fallBack() {
        val speech = KeyboardSpeech.model(ime)
        if (engine == Dictation.Engine.SYSTEM && Whisper.loaded && speech.file.exists()) {
            // the model is there but the choice said the phone: the model takes this one
            begin(Dictation.Engine.WHISPER, speech)
        } else {
            openDictationSettings()
        }
    }

    private fun finishComposing() {
        if (composing) {
            composing = false
            prefix = ""
            ime.dictationEnd()
        }
    }

    /** Words may start with a sign that joins them to the text before ("," "." ")"): no space then. */
    private fun startsWithSign(text: String): Boolean = text.isNotEmpty() && ",.;:!?)]}…%".indexOf(text[0]) >= 0

    /** An input method cannot ask for the microphone: a transparent activity of the app does, then the user taps again. */
    private fun askForMicrophone() {
        ime.startActivity(
            Intent(ime, DictationPermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        )
    }

    private fun openDictationSettings() {
        ime.startActivity(
            Intent(ime, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(SettingsActivity.EXTRA_DESTINATION, SettingsDestination.Dictation)
        )
    }

    companion object {
        /** The keys that change nothing typed (shift, the layout switches) do not end a dictation. */
        fun endsDictation(keyCode: Int): Boolean = with(KeyCode) { !keyCode.isModifier() && keyCode != KeyCode.VOICE_INPUT }
    }
}
