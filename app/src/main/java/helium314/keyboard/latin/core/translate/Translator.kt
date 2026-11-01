package helium314.keyboard.latin.core.translate

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import dev.davidv.translator.ErrorType
import dev.davidv.translator.ITranslationCallback
import dev.davidv.translator.ITranslationService
import dev.davidv.translator.TranslationError
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** What Translator answered for one text. */
sealed interface TranslationResult {
    data class Ok(val text: String) : TranslationResult

    /** Translator could not tell which language the text is in. */
    data object NotDetected : TranslationResult

    /** The text is in [language] (its display name), which is not downloaded in Translator. */
    data class NotDownloaded(val language: String) : TranslationResult

    data object Failed : TranslationResult
}

/**
 * Translation on the phone by DavidVentura/offline-translator ("Translator", GPL-3.0), through the service
 * it exports: AidlTranslationService, bound with the action "dev.davidv.translator.ITranslationService" on
 * its package, its four AIDL files copied unchanged (root/app/src/main/aidl/dev/davidv/translator)
 * https://github.com/DavidVentura/offline-translator/blob/main/app/src/main/java/dev/davidv/translator/AidlTranslationService.kt
 *
 * Nothing is bound before the package behind the name is Translator itself: its signing certificate must be
 * the one published in its README. The text goes to that app only; nothing is logged.
 *
 * Shared across the apps of this base: edit Modules/shared, then run sync.sh.
 */
object Translator {

    const val PACKAGE = "dev.davidv.translator"
    private const val ACTION = "dev.davidv.translator.ITranslationService"

    /** SHA-256 of Translator's signing certificate (its README, checked on the installed APK). */
    private const val CERT_SHA256 = "2b3806e745d809018a51be58d0635ffc74cc9733439407ab1ed0424a4db3e1fb"
    private const val CONNECT_MS = 5_000L
    private const val TRANSLATE_MS = 30_000L

    /**
     * The languages of Translator's catalogue (index_v6.json, "languages"):
     * https://github.com/DavidVentura/offline-translator/blob/main/app/src/main/assets/index_v6.json
     */
    val catalogue = setOf(
        "ar", "az", "be", "bg", "bn", "bs", "ca", "cs", "da", "de", "el", "en", "es", "et", "eu", "fa", "fi", "fr",
        "gl", "gu", "he", "hi", "hr", "hu", "id", "is", "it", "ja", "ka", "kn", "ko", "lt", "lv", "ml", "mr", "ms",
        "nb", "nl", "nn", "no", "pl", "pt", "ro", "ru", "sk", "sl", "sq", "sr", "sv", "sw", "ta", "te", "th", "tl",
        "tr", "ug", "uk", "ur", "vi", "zh", "zh_hant"
    )

    /**
     * Whether Translator is installed and signed with its own certificate. PackageManager.hasSigningCertificate
     * https://developer.android.com/reference/android/content/pm/PackageManager#hasSigningCertificate(java.lang.String,%20byte[],%20int)
     * in the spirit of Sisters.isOurs (phone kit), here against a fixed certificate.
     */
    fun installed(context: Context): Boolean = runCatching {
        val cert = CERT_SHA256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        context.packageManager.hasSigningCertificate(PACKAGE, cert, PackageManager.CERT_INPUT_SHA256)
    }.getOrDefault(false)

    /** Translator's launcher intent, or null when it is not the real one. */
    fun launchIntent(context: Context): Intent? =
        if (installed(context)) context.packageManager.getLaunchIntentForPackage(PACKAGE) else null

    /** Translator's code for [locale]: the language, Traditional Chinese apart; null when its catalogue lacks it. */
    fun codeFor(locale: Locale): String? {
        val language = when (val lang = locale.language) {
            "iw" -> "he" // Java's old codes
            "in" -> "id"
            "fil" -> "tl"
            else -> lang
        }
        val code = if (language == "zh" && (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO"))) "zh_hant" else language
        return code.takeIf { it in catalogue }
    }

    /** The locale of a catalogue [code], to show its name. */
    fun localeOf(code: String): Locale = Locale.forLanguageTag(code.replace('_', '-'))

    /**
     * A connection kept while a panel is open, so typing does not bind again at every letter; [close] unbinds.
     * Bound on the first [translate]. Callback to suspend: CompletableDeferred, kotlinx.coroutines
     * https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-completable-deferred/
     * Binding: https://developer.android.com/develop/background-work/services/bound-services
     */
    class Session(context: Context) : AutoCloseable {
        private val app = context.applicationContext
        private var bound = false

        @Volatile
        private var service = CompletableDeferred<ITranslationService>()

        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service.complete(ITranslationService.Stub.asInterface(binder))
            }

            // BIND_AUTO_CREATE brings the service back: wait for the next connection
            override fun onServiceDisconnected(name: ComponentName) {
                service = CompletableDeferred()
            }

            override fun onBindingDied(name: ComponentName) {
                service.completeExceptionally(RemoteException("binding died"))
            }

            override fun onNullBinding(name: ComponentName) {
                service.completeExceptionally(RemoteException("no binding"))
            }
        }

        private fun bind(): Boolean {
            if (bound) return true
            if (!installed(app)) return false
            bound = app.bindService(Intent(ACTION).setPackage(PACKAGE), connection, Context.BIND_AUTO_CREATE)
            if (!bound) app.unbindService(connection) // the documentation asks for it even when bindService fails
            return bound
        }

        /**
         * [text] into the language [to] (a catalogue code); Translator detects the source unless [from] (a catalogue
         * code) names it. fromLanguage of ITranslationService.translate (offline-translator)
         * https://github.com/DavidVentura/offline-translator/blob/main/app/src/main/aidl/dev/davidv/translator/ITranslationService.aidl
         */
        suspend fun translate(text: String, to: String, from: String = ""): TranslationResult {
            if (!bind()) return TranslationResult.Failed
            return try {
                val remote = withTimeout(CONNECT_MS) { service.await() }
                withTimeout(TRANSLATE_MS) { ask(remote, text, to, from) }
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) TranslationResult.Failed else throw e
            } catch (_: Exception) {
                TranslationResult.Failed
            }
        }

        private suspend fun ask(remote: ITranslationService, text: String, to: String, from: String): TranslationResult {
            val answer = CompletableDeferred<TranslationResult>()
            remote.translate(text, from, to, object : ITranslationCallback.Stub() {
                override fun onTranslationResult(translatedText: String?) {
                    answer.complete(translatedText?.let { TranslationResult.Ok(it) } ?: TranslationResult.Failed)
                }

                override fun onTranslationError(error: TranslationError?) {
                    answer.complete(when (error?.type) {
                        ErrorType.COULD_NOT_DETECT_LANGUAGE -> TranslationResult.NotDetected
                        ErrorType.DETECTED_BUT_UNAVAILABLE -> TranslationResult.NotDownloaded(error?.language.orEmpty())
                        else -> TranslationResult.Failed
                    })
                }
            })
            return answer.await()
        }

        override fun close() {
            if (!bound) return
            bound = false
            runCatching { app.unbindService(connection) }
            service.cancel()
            service = CompletableDeferred()
        }
    }
}
