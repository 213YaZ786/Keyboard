// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictation

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.suggestions.SuggestionStripView
import java.util.Locale

/**
 * Dictation in the keyboard, the way iOS 26 does it: the keyboard stays, the mic turns into a stop button, the words
 * go into the field as they are recognised and typing is still possible (a key ends the dictation).
 * The words are heard by Android's SpeechRecognizer (KeyboardRecognizer): our Voice app's recognition service, else the
 * phone's own on-device recognizer (Recognizers); this class is the keyboard's side: the right to the microphone, the
 * words into the field (InputLogic.dictationComposing and onTextInput), the stop button
 * (SuggestionStripView.setDictating), and the end of the dictation when the user types, the keyboard closes or the
 * field changes. With neither Voice nor an on-device recognizer, the bar says Voice is not installed and offers Store
 * (VoiceMissingView; Voice is installed from Store).
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
class DictationController(private val ime: LatinIME) : KeyboardRecognizer.Listener {

    private val dictation = KeyboardRecognizer(ime)

    /** The stop button shows: a dictation is listening. */
    var listening = false
        private set

    /** Words may still arrive (a tap on stop lets the last phrase be written out first). */
    private var accepting = false
    private var composing = false
    private var prefix = ""
    private var committing = false
    private var service: ComponentName? = null
    private var triedOnDevice = false

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

    /** The keyboard is going: nothing of the recognizer stays. */
    fun destroy() {
        stop(flush = false)
    }

    private fun start() {
        if (dictation.busy) return
        if (Settings.getValues().mIncognitoModeEnabled) return // incognito and password fields: no microphone
        if (ime.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askForMicrophone()
            return
        }
        val voice = Recognizers.voice(ime)
        if (voice == null && !Recognizers.onDevice(ime)) {
            // nobody to listen: the bar says so and offers Store, where Voice is installed from (VoiceMissingView, shown as
            // HeliBoard shows an inline autofill view, SuggestionStripView.setExternalSuggestionView with its close button,
            // https://github.com/HeliBorg/HeliBoard/blob/main/app/src/main/java/helium314/keyboard/latin/LatinIME.java);
            // the strip is found in the input view as KeyboardSwitcher does (findViewById R.id.suggestion_strip_view)
            val strip = KeyboardSwitcher.getInstance().wrapperView?.rootView?.findViewById<SuggestionStripView>(R.id.suggestion_strip_view)
            val store = Recognizers.storeIntent(ime)
            // the message stays in the bar until a key, the close button, another field or Install (SuggestionStripView.showMessage);
            // Install ends it as the close button does (LatinIME.removeExternalSuggestions)
            if (strip != null) strip.showMessage(VoiceMissingView(ime) {
                ime.removeExternalSuggestions()
                store?.let { ime.startActivity(it) }
            })
            else store?.let { ime.startActivity(it) }
            return
        }
        triedOnDevice = voice == null
        begin(voice)
    }

    /** Listens with Voice's service [with], or the phone's on-device recognizer when null. */
    private fun begin(with: ComponentName?) {
        service = with
        ime.dictationBegin()
        composing = false
        prefix = ""
        accepting = true
        listening = true
        ime.setDictating(true)
        if (!dictation.start(language(), with, this)) {
            listening = false
            accepting = false
            ime.setDictating(false)
        }
    }

    /** The language of the keyboard now (its current subtype); the phone's when the keyboard is not running. */
    private fun language(): Locale =
        runCatching { RichInputMethodManager.getInstance().currentSubtypeLocale }.getOrNull() ?: Locale.getDefault()

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

    override fun stopped(why: KeyboardRecognizer.Why) {
        accepting = false
        val wasListening = listening
        listening = false
        if (wasListening) ime.setDictating(false)
        finishComposing()
        when (why) {
            KeyboardRecognizer.Why.NO_MICROPHONE_RIGHT -> askForMicrophone()
            KeyboardRecognizer.Why.UNAVAILABLE -> fallBack()
            else -> Unit
        }
    }

    /** Voice cannot hear this language: the phone's on-device recognizer takes over once, else Voice itself opens. */
    private fun fallBack() {
        if (service != null && !triedOnDevice && Recognizers.onDevice(ime)) {
            triedOnDevice = true
            begin(null)
        } else {
            Recognizers.voiceIntent(ime)?.let { ime.startActivity(it) }
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

    companion object {
        /** The keys that change nothing typed (shift, the layout switches) do not end a dictation. */
        fun endsDictation(keyCode: Int): Boolean = with(KeyCode) { !keyCode.isModifier() && keyCode != KeyCode.VOICE_INPUT }
    }
}
