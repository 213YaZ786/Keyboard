// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.SpeechRecognizer
import helium314.keyboard.latin.BuildConfig

/**
 * Who hears the keyboard's dictation: our Voice app, else the phone's own on-device recognizer; and when neither is
 * there, Store (Voice is installed from it). Nothing is bound or opened before the package behind the name is one of
 * ours and signed with the key of this app.
 *
 * Sources: the package check is the phone kit's Sisters (Modules/shared/phone/main/core/dial/Sisters.kt.tpl:
 * the package must be in our list, then PackageManager.checkSignatures, the ".debug" packages only in a debug build)
 * https://developer.android.com/reference/android/content/pm/PackageManager#checkSignatures(java.lang.String,%20java.lang.String)
 * The service is named as Voice declares it (VoiceRecognitionService, an exported android.speech.RecognitionService)
 * and bound by SpeechRecognizer.createSpeechRecognizer(context, ComponentName)
 * https://developer.android.com/reference/android/speech/SpeechRecognizer#createSpeechRecognizer(android.content.Context,%20android.content.ComponentName)
 * The on-device recognizer is SpeechRecognizer.isOnDeviceRecognitionAvailable (API 33), as the voice kit's SystemSpeech
 * used it. Store is opened by its launcher intent, PackageManager.getLaunchIntentForPackage.
 */
object Recognizers {

    private const val VOICE = "com.yaz.voice"
    private const val STORE = "com.yaz.store"
    private const val VOICE_SERVICE = "com.yaz.voice.recognition.VoiceRecognitionService"

    /** The packages of one of our apps: the release one, and the debug one in a debug build only. */
    internal fun packages(app: String): List<String> =
        if (BuildConfig.DEBUG) listOf(app, "$app.debug") else listOf(app)

    /** Whether [pkg] is installed and signed with the same key as this app. */
    private fun isOurs(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(pkg, 0)
        context.packageManager.checkSignatures(context.packageName, pkg) == PackageManager.SIGNATURE_MATCH
    }.getOrDefault(false)

    /** Voice's recognition service, when Voice is on the phone, ours, and the service is there and enabled. */
    fun voice(context: Context): ComponentName? = packages(VOICE).firstOrNull { isOurs(context, it) }?.let { pkg ->
        ComponentName(pkg, VOICE_SERVICE).takeIf {
            runCatching { context.packageManager.getServiceInfo(it, 0) }.isSuccess
        }
    }

    /** The phone has a recognizer that works on the device (Android 13 and later). */
    fun onDevice(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

    /** Opens Store, where Voice is installed from; null when Store is not on the phone or is not ours. */
    fun storeIntent(context: Context): Intent? =
        packages(STORE).firstOrNull { isOurs(context, it) }
            ?.let { context.packageManager.getLaunchIntentForPackage(it) }
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens Voice itself (its welcome and engine settings), when it is there. */
    fun voiceIntent(context: Context): Intent? =
        voice(context)?.let { context.packageManager.getLaunchIntentForPackage(it.packageName) }
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The mic key has something to do: a recognizer to listen with, or Store to get Voice from. */
    fun anyway(context: Context): Boolean = voice(context) != null || onDevice(context) || storeIntent(context) != null
}
