package helium314.keyboard.latin.core.update

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The link to Store (com.yaz.store), which installs and updates every app of
 * ours. When Store is on the phone and the app's own updater is off (the
 * "self" setting below), the app only tells Store when it opens and when the
 * user leaves it; Store checks, downloads and installs. Nothing is asked of
 * a package that is not Store signed with our certificate.
 *
 * Each app adds to its manifest (manifests are not shared):
 *   <uses-permission android:name="com.yaz.store.permission.DOOR" />
 *   <queries><package android:name="com.yaz.store" /></queries>
 * and, in src/debug/AndroidManifest.xml only (the debug Store is a separate
 * package, invisible without its own line):
 *   <queries><package android:name="com.yaz.store.debug" /></queries>
 * Each app places [UpdateSourceRow] in its own Settings.
 *
 * Sources: the idea is Google Play's In-App Updates API (the app asks the
 * store, the store updates it: https://developer.android.com/guide/playcore/in-app-updates);
 * GrapheneOS AppStore, where only the store updates apps
 * (https://github.com/GrapheneOS/AppStore); the check that a package is ours
 * before it is used is the phone kit's Sisters (core/dial/Sisters.kt) and
 * developer.android.com/privacy-and-security/risks/content-resolver
 * (the provider's package is compared to the one expected).
 */
object StoreLink {

    private const val STORE = "com.yaz.store"

    /** Store's debug package, valid in a debug build only (as Sisters.packages treats ".debug"). */
    private const val STORE_DEBUG = "$STORE.debug"

    /** SHA-256 of the YAZ certificate, the one Store is signed with. */
    private const val YAZ_CERT = "f006b86dadc9be1c68deab3f53500dc04f7cb03a69d5bbd79daa0cad8160431b"

    /** The key of the "updates" preferences ([Updates.PREFS]) that holds the setting. */
    internal const val SELF = "self"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The Store packages to look for: the release one, and the debug one in a debug build only. */
    internal fun packages(debug: Boolean): List<String> = if (debug) listOf(STORE, STORE_DEBUG) else listOf(STORE)

    /** Store's door, its provider: `<package>.door`. */
    internal fun authority(pkg: String): String = "$pkg.door"

    /**
     * [hex] as bytes, two digits each. After the common Kotlin idiom for
     * hex strings (chunked(2) and toInt(16)); the stdlib's HexFormat is
     * still experimental in Kotlin 2.x (https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/hex-to-byte-array.html).
     */
    internal fun hexBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** The certificate's SHA-256 digest, as PackageManager takes it. */
    internal fun certificate(): ByteArray = hexBytes(YAZ_CERT)

    /**
     * Store's package when it is installed and signed with our certificate,
     * else null. hasSigningCertificate with CERT_INPUT_SHA256 (API 28), the
     * call Updates.sameApp already uses with CERT_INPUT_RAW_X509
     * (https://developer.android.com/reference/android/content/pm/PackageManager#hasSigningCertificate(java.lang.String,%20byte[],%20int)).
     */
    fun storePackage(context: Context): String? {
        val pm = context.packageManager
        return packages(helium314.keyboard.latin.BuildConfig.DEBUG).firstOrNull { pkg ->
            runCatching {
                pm.getPackageInfo(pkg, 0)
                // An app that does not hold Store's door permission yet (its manifest line missing,
                // or Store installed after it) keeps updating itself: Context.checkSelfPermission,
                // https://developer.android.com/reference/android/content/Context#checkSelfPermission(java.lang.String).
                context.checkSelfPermission("$pkg.permission.DOOR") == PackageManager.PERMISSION_GRANTED &&
                (pm.hasSigningCertificate(pkg, certificate(), PackageManager.CERT_INPUT_SHA256) ||
                    // The debug Store carries the debug key, as the app's debug build does: in a debug
                    // build only, Store's debug package signed like the app counts (Sisters' rule for
                    // ".debug" names; PackageManager.checkSignatures,
                    // https://developer.android.com/reference/android/content/pm/PackageManager#checkSignatures(java.lang.String,%20java.lang.String)).
                    (pkg == STORE_DEBUG &&
                        pm.checkSignatures(context.packageName, pkg) == PackageManager.SIGNATURE_MATCH))
            }.getOrDefault(false)
        }
    }

    /** Whether Store is installed and ours. */
    fun present(context: Context): Boolean = storePackage(context) != null

    /** The app has opened: Store checks it and downloads what is newer. Off the main thread. */
    fun opened(context: Context) = knock(context, "opened")

    /** The user has left the app and it is not busy: Store may install now. Off the main thread. */
    fun left(context: Context) = knock(context, "left")

    /**
     * Calls [method] on Store's door, no argument: Store reads the caller
     * itself. ContentResolver.call by authority (API 29,
     * https://developer.android.com/reference/android/content/ContentResolver#call(java.lang.String,%20java.lang.String,%20java.lang.String,%20android.os.Bundle)),
     * the provider's package compared to Store's first. Whatever fails (Store
     * missing, an old Store without the door, a refusal) is swallowed.
     * Through an unstable client: a call holds Store for a whole download, and
     * Android kills the holder of a stable connection whose provider dies; an
     * unstable one only gets DeadObjectException
     * (https://developer.android.com/reference/android/content/ContentResolver#acquireUnstableContentProviderClient(java.lang.String),
     * https://developer.android.com/reference/android/content/ContentProviderClient#call(java.lang.String,%20java.lang.String,%20android.os.Bundle)).
     */
    private fun knock(context: Context, method: String) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val pkg = storePackage(app) ?: return@runCatching
                val authority = authority(pkg)
                @Suppress("DEPRECATION")
                val owner = app.packageManager.resolveContentProvider(authority, 0)?.packageName
                if (owner == pkg) {
                    app.contentResolver.acquireUnstableContentProviderClient(authority)?.use { client ->
                        client.call(method, null, null)
                    }
                }
            }
        }
    }

    /** What the user chose, or null when they never touched it. */
    private fun chosen(context: Context): Boolean? {
        val prefs = context.getSharedPreferences(Updates.PREFS, Context.MODE_PRIVATE)
        return if (prefs.contains(SELF)) prefs.getBoolean(SELF, true) else null
    }

    /**
     * Whether the app updates itself: the user's choice, else true without
     * Store and false with it. Read at each use, so installing Store later
     * turns the app's own updater off unless the user turned it on.
     */
    fun selfUpdates(context: Context): Boolean = selfOn(chosen(context), present(context))

    /** The user's choice, kept. */
    fun setSelfUpdates(context: Context, on: Boolean) {
        context.getSharedPreferences(Updates.PREFS, Context.MODE_PRIVATE).edit().putBoolean(SELF, on).apply()
    }

    /** Which updater runs now. */
    internal fun route(context: Context): Route {
        val store = present(context)
        return routeFor(selfOn(chosen(context), store), store)
    }

    /** Whether Store updates this app: its own updater is off and Store is there. */
    fun viaStore(context: Context): Boolean = route(context) == Route.STORE
}
