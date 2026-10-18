// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictation

import android.content.Context
import android.content.Intent
import android.net.Uri
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.core.net.KeyboardNetwork
import helium314.keyboard.latin.core.voice.SpeechChoice
import helium314.keyboard.latin.core.voice.SpeechModel
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.Locale

/**
 * The speech model of the keyboard, one for the whole app so a download goes on when the settings are left and the
 * dictation reads the same choice: Modules' voice kit (core/voice/SpeechModel, from SMS), whose choice is kept in the
 * preferences (SpeechChoice, shared preferences as every setting of the app) and whose download is allowed only while
 * the Network switch is on (KeyboardNetwork). Created at the first tap on the mic or the first visit to the Dictation
 * settings, never before: nothing of the speech engines runs until the user asks.
 *
 * Sources: SMS creates its one SpeechModel in Transcriber the same way
 * https://github.com/213YaZ786/SMS/blob/main/app/src/main/java/com/yaz/sms/core/voice/Transcriber.kt ;
 * the keyboard's language is HeliBoard's RichInputMethodManager.currentSubtypeLocale
 * https://github.com/HeliBorg/HeliBoard/blob/main/app/src/main/java/helium314/keyboard/latin/RichInputMethodManager.kt ;
 * the app's page in Android's settings is Settings.ACTION_APPLICATION_DETAILS_SETTINGS
 * https://developer.android.com/reference/android/provider/Settings#ACTION_APPLICATION_DETAILS_SETTINGS
 */
object KeyboardSpeech {

    @Volatile
    private var model: SpeechModel? = null
    private var checkedFor: String? = null

    fun model(context: Context): SpeechModel {
        model?.let { return it }
        synchronized(this) {
            model?.let { return it }
            val app = context.applicationContext
            val made = SpeechModel(
                app,
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
                object : SpeechChoice {
                    override var name: String
                        get() = app.prefs().getString(Settings.PREF_SPEECH_MODEL, "") ?: ""
                        set(value) = app.prefs().edit().putString(Settings.PREF_SPEECH_MODEL, value).apply()
                },
                language = { language() },
                allowed = { KeyboardNetwork.enabled(app) },
            )
            checkedFor = language().toLanguageTag()
            model = made
            return made
        }
    }

    /** The language of the keyboard now (its current subtype); the phone's when the keyboard is not running. */
    fun language(): Locale =
        runCatching { RichInputMethodManager.getInstance().currentSubtypeLocale }.getOrNull() ?: Locale.getDefault()

    /**
     * Whether the phone's recognizer has to be asked again about the language of the keyboard: it was never asked
     * for this language. Said once per language.
     */
    fun languageChanged(): Boolean {
        val now = language().toLanguageTag()
        if (now == checkedFor) return false
        checkedFor = now
        return true
    }

    /** The app's page in Android's settings, where the microphone can be allowed again after "don't ask again". */
    fun appSettingsIntent(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
