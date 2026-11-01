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
 * Translation on the phone by Language (our app, offline-translator's "Translator" merged into it, GPL-3.0),
 * through offline-translator's AIDL service: AidlTranslationService, bound with the action
 * "dev.davidv.translator.ITranslationService" on Language's package, its four AIDL files copied unchanged
 * (root/app/src/main/aidl/dev/davidv/translator)
 * https://github.com/DavidVentura/offline-translator/blob/main/app/src/main/java/dev/davidv/translator/AidlTranslationService.kt
 * Language exports it behind the signature permission com.yaz.language.permission.TRANSLATE.
 *
 * Nothing is bound before the package is ours: Language's, signed with the same key as this app. The text
 * goes to that app only; nothing is logged.
 *
 * Shared across the apps of this base: edit Modules/shared, then run sync.sh.
 */
object Translator {

    private const val ACTION = "dev.davidv.translator.ITranslationService"
    private const val CONNECT_MS = 5_000L
    private const val TRANSLATE_MS = 30_000L

    /**
     * The packages of Language: the release one, and the debug one in a debug build only. Ported as it is from
     * Sisters.packages (phone kit, shared/phone/main/core/dial/Sisters.kt.tpl), for the one app "language".
     */
    private fun packages(): List<String> =
        if (helium314.keyboard.latin.BuildConfig.DEBUG) listOf("com.yaz.language", "com.yaz.language.debug") else listOf("com.yaz.language")

    /**
     * Whether [pkg] is Language's and has this app's signature. Ported from Sisters.isOurs (phone kit,
     * shared/phone/main/core/dial/Sisters.kt.tpl), from the comparison of signatures of PackageManager.checkSignatures
     * https://developer.android.com/reference/android/content/pm/PackageManager#checkSignatures(java.lang.String,%20java.lang.String)
     */
    private fun isOurs(context: Context, pkg: String?): Boolean {
        if (pkg == null || pkg !in packages()) return false
        if (pkg == context.packageName) return true
        return runCatching { context.packageManager.checkSignatures(context.packageName, pkg) == PackageManager.SIGNATURE_MATCH }
            .getOrDefault(false)
    }

    /**
     * Whether [pkg] exports the translation service: the explicit intent (action and package) resolves, as the
     * bound services guide resolves a service before binding
     * https://developer.android.com/develop/background-work/services/bound-services
     * PackageManager.queryIntentServices
     * https://developer.android.com/reference/android/content/pm/PackageManager#queryIntentServices(android.content.Intent,%20int)
     * (the app's manifest lists the package in queries).
     */
    @Suppress("DEPRECATION")
    private fun serves(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.queryIntentServices(Intent(ACTION).setPackage(pkg), 0).isNotEmpty()
    }.getOrDefault(false)

    /**
     * The package to bind: the first of Language's installed packages that passes [isOurs] and exports the
     * service, else null.
     */
    private fun target(context: Context): String? =
        packages().firstOrNull { isOurs(context, it) && serves(context, it) }

    /** Whether Language is installed, signed with the same key as this app, and exports the translation service. */
    fun installed(context: Context): Boolean = target(context) != null

    /** Language's launcher intent, or null when it is not ours. */
    fun launchIntent(context: Context): Intent? =
        target(context)?.let { context.packageManager.getLaunchIntentForPackage(it) }

    /**
     * The languages of Language's catalogue (index_v7.json, "languages"; built by its engine/catalog/languages.py
     * from Firefox's selection): /home/yghouar/Claude Code/APK/YAZ/Language/app/src/main/assets/index_v7.json
     */
    val catalogue = setOf(
        "af", "ar", "az", "be", "bg", "bn", "bs", "ca", "cs", "da", "de", "el", "en", "es", "et", "eu", "fa", "fi",
        "fr", "gl", "gu", "he", "hi", "hr", "hu", "id", "is", "it", "ja", "kn", "ko", "lt", "lv", "ml", "mr", "ms",
        "nb", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sq", "sr", "sv", "ta", "te", "th", "tl", "tr", "ug", "uk",
        "ur", "vi", "zh", "zh_hant"
    )

    /** Language's code for [locale]: the language, Traditional Chinese apart; null when its catalogue lacks it. */
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
            val pkg = target(app) ?: return false
            bound = app.bindService(Intent(ACTION).setPackage(pkg), connection, Context.BIND_AUTO_CREATE)
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
