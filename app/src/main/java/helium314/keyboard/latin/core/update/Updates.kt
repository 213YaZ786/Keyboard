package helium314.keyboard.latin.core.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** What the app does when a newer release is out, asked once at launch. */
@Serializable
enum class UpdateMode { OFF, NOTIFY, INSTALL }

/** A release on GitHub: its version, its page, its APK and checksum file. */
data class Release(val version: String, val page: String, val apk: String?, val sums: String?)

/**
 * The app's own releases on GitHub. One small request when the app opens,
 * nothing in the background. Installing downloads the release APK, shown
 * in a notification, checks it (see stage) and keeps it; the app hands it
 * to Android's installer only when the user leaves the app (ON_STOP of the
 * whole process) and it is not busy, so nothing is replaced under their
 * hands. The manifest holds UPDATE_PACKAGES_WITHOUT_USER_ACTION, so Android
 * installs without asking; when it still asks (STATUS_PENDING_USER_ACTION),
 * the confirmation is shown at the next start of the app, once.
 *
 * Sources: PackageInstaller.SessionParams.setRequireUserAction and
 * Manifest.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION
 * (https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int),
 * https://developer.android.com/reference/android/Manifest.permission#UPDATE_PACKAGES_WITHOUT_USER_ACTION):
 * with the permission, an installer updating itself, and an app targeting a
 * recent API, no user action is required. setRequestUpdateOwnership is not
 * used: it needs ENFORCE_UPDATE_OWNERSHIP and only counts on a first install.
 *
 * With Store on the phone and the "self" setting off (see [StoreLink]), the
 * app fetches and stages nothing: [arm] tells Store the app has opened, and
 * at ON_STOP, not busy, it tells Store the app was left; Store installs.
 */
object Updates {

    private const val REPO = "213YaZ786/Keyboard"
    private const val ACTION_STATUS = "helium314.keyboard.latin.UPDATE_STATUS"
    private const val TIMEOUT_MS = 15_000
    /** Far above any release of these apps, to stop an endless download. */
    private const val MAX_APK = 200L * 1024 * 1024
    internal const val PREFS = "updates"
    internal const val INSTALLING = "installing"

    private val json = Json { ignoreUnknownKeys = true }

    /** The newest release, or null when GitHub cannot be read. */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val root = json.parseToJsonElement(
                get("https://api.github.com/repos/$REPO/releases/latest").decodeToString()
            ) as JsonObject
            val tag = (root["tag_name"] as? JsonPrimitive)?.content ?: return@runCatching null
            // A rebuild of the same version is tagged v1.2.3-b45.
            val version = tag.removePrefix("v").substringBefore("-")
            val assets = (root["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            fun asset(name: String) = assets.firstOrNull { (it["name"] as? JsonPrimitive)?.content == name }
                ?.let { (it["browser_download_url"] as? JsonPrimitive)?.content }
            Release(
                version = version,
                // Only this app's own page is ever opened from here.
                page = (root["html_url"] as? JsonPrimitive)?.content?.takeIf { it.startsWith("https://github.com/$REPO/") }
                    ?: "https://github.com/$REPO/releases/latest",
                apk = asset("Keyboard-$version.apk"),
                sums = asset("SHA256SUMS.txt")
            )
        }.getOrNull()
    }

    /** Whether [candidate] is a later version than [current], number by number. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = numbers(candidate)
        val b = numbers(current.substringBefore('-'))
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    // ASCII digits only: a tag with another script's digits reads as 0 (Mushaf's LatinDigitsTest guard).
    private fun numbers(version: String) = version.split('.').map { part -> part.takeWhile { it in '0'..'9' }.take(9).toIntOrNull() ?: 0 }

    /** Whether Android lets this app install packages; the reader allows it once. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Android's own screen where the reader allows it. */
    fun allowInstalls(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** A verified APK kept in the cache until the user leaves the app. */
    internal class Staged(val file: File, val packageName: String, val version: String, val page: String)

    private val gate = UpdateGate()
    @Volatile private var staged: Staged? = null
    @Volatile private var busy: () -> Boolean = { false }
    @Volatile private var foreground = true
    /** Store was told this launch that the app opened; only then is it told the app was left. */
    @Volatile private var storeOpened = false
    /** Android's confirmation screen, held while the app is out of sight. */
    @Volatile private var held: Intent? = null
    private var armed = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Starts watching whether the user is in the app, once for the process:
     * the update goes in at ON_STOP, Android's confirmation (if any) is shown
     * at ON_START. Main thread. [busy] says whether the app must not be
     * replaced now (a call, a message sending, media playing); it is read
     * off the main thread, so it reads the app's own state, never a
     * composition's. A check that throws counts as busy.
     * Registered on the application, not on a screen: leaving by Back
     * destroys the screen before ProcessLifecycleOwner's delayed ON_STOP.
     * Ported from the pattern of
     * https://developer.android.com/reference/androidx/lifecycle/ProcessLifecycleOwner.
     */
    fun arm(context: Context, busy: () -> Boolean = { false }) {
        this.busy = busy
        val app = context.applicationContext
        // Store's route: the app only says it has opened (off the main thread, the check reads PackageManager).
        if (!storeOpened) scope.launch {
            if (StoreLink.viaStore(app)) {
                storeOpened = true
                StoreLink.opened(app)
            }
        }
        if (armed) return
        armed = true
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    foreground = true
                    if (gate.started()) showHeld(app)
                }
                Lifecycle.Event.ON_STOP -> {
                    foreground = false
                    scope.launch { leave(app) }
                }
                else -> Unit
            }
        })
    }

    /**
     * The user has left the app. On Store's route (the "self" setting off,
     * Store there) Store is told, once the app is not busy, and installs;
     * a file the app staged earlier is deleted, never installed behind
     * Store's back. Else the app's own commit, [commitIfLeft]. Run off the
     * main thread. Source: ProcessLifecycleOwner's ON_STOP as in [arm]
     * (https://developer.android.com/reference/androidx/lifecycle/ProcessLifecycleOwner);
     * "busy" read as in [commitIfLeft].
     */
    internal fun leave(app: Context) {
        if (!StoreLink.viaStore(app)) {
            commitIfLeft(app)
            return
        }
        staged?.let {
            it.file.delete()
            staged = null
            gate.dropped()
        }
        val busyNow = runCatching { busy() }.getOrDefault(true)
        if (storeOpened && gate.storeLeaves(foreground, busyNow)) StoreLink.left(app)
    }

    /** The mode left Install: an update waiting is forgotten and its file deleted. */
    fun drop() {
        storeOpened = false
        gate.dropped()
        held = null
        staged?.file?.delete()
        staged = null
    }

    /**
     * Downloads [release], checks it and keeps it for [commitIfLeft]. False
     * when anything fails; a file that fails a check is deleted, never
     * installed. Three checks: the file comes from this app's releases on
     * GitHub, it matches the release's SHA256SUMS, and it is this app, newer,
     * signed with the key of the installed one (Android refuses another key
     * anyway; checking first means the user is never asked for a bad file).
     * A file kept by an earlier launch and still matching is not downloaded
     * again. Nothing is shown to the user but the download's notification.
     */
    suspend fun stage(context: Context, release: Release): Boolean = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val file = File(app.cacheDir, "update.apk")
        // The download shows in a notification, the app open or not.
        val notice = UpdateNotice(app, release.version)
        var done = false
        val ok = try {
            val apk = release.apk?.takeIf(::fromRepo) ?: return@withContext false
            val sums = release.sums?.takeIf(::fromRepo) ?: return@withContext false
            val name = apk.substringAfterLast('/')
            val expected = get(sums).decodeToString().lines()
                .map { it.trim() }
                .firstOrNull { it.endsWith(name) }
                ?.substringBefore(' ')?.lowercase() ?: return@withContext false
            if (!(file.exists() && sha256(file) == expected)) {
                notice.progress(0f)
                download(apk, file, notice::progress)
            }
            done = if (sha256(file) == expected) stageFile(app, file, release.version, release.page) else false
            done
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        } finally {
            if (!done) file.delete()
            if (done) notice.cancel() else notice.failed(release.page)
        }
        // After the notice is settled, so a failed commit's own notice stays.
        if (ok) commitIfLeft(app)
        ok
    }

    /**
     * Keeps [file], already checked against its SHA-256, as the update to
     * install when the user leaves: it must be this app, newer, signed alike
     * (sameApp). The one way into the waiting state, for a download and for
     * the debug receiver that feeds a local APK.
     */
    internal fun stageFile(app: Context, file: File, version: String, page: String): Boolean {
        val target = sameApp(app, file) ?: return false
        listenForConfirmation(app)
        staged = Staged(file, target, version, page)
        gate.staged()
        return true
    }

    /**
     * Commits the waiting update when the user is out of the app and it is
     * not busy; else it waits for the next ON_STOP. Run off the main thread.
     */
    internal fun commitIfLeft(app: Context) {
        val s = staged ?: return
        val busyNow = runCatching { busy() }.getOrDefault(true)
        if (!gate.installNow(foreground, busyNow)) return
        staged = null
        if (!commit(app, s)) UpdateNotice(app, s.version).failed(s.page)
    }

    /**
     * Hands [s] to Android's installer. Written after the session recipe of
     * https://developer.android.com/reference/android/content/pm/PackageInstaller
     * (createSession, openWrite, fsync, commit with an IntentSender).
     */
    private fun commit(app: Context, s: Staged): Boolean = try {
        // Lets UpdatedReceiver tell this update from one installed by hand.
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(INSTALLING, true).apply()
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(s.packageName)
            // No question when the manifest holds UPDATE_PACKAGES_WITHOUT_USER_ACTION
            // and the app updates itself (see the class doc); else Android
            // still answers STATUS_PENDING_USER_ACTION.
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("app.apk", 0, s.file.length()).use { out ->
                s.file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val status = Intent(ACTION_STATUS).setPackage(app.packageName)
            val pending = PendingIntent.getBroadcast(
                app, id, status, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            session.commit(pending.intentSender)
        }
        true
    } catch (e: Exception) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(INSTALLING).apply()
        false
    } finally {
        s.file.delete()
    }

    /** Shows the held confirmation, once. */
    private fun showHeld(context: Context) {
        val confirm = held ?: return
        held = null
        runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Only files attached to this app's own releases are fetched. */
    internal fun fromRepo(url: String): Boolean = url.startsWith("https://github.com/$REPO/releases/download/")

    /**
     * Whether [file] is this app, a later version, signed with every key
     * the installed app is signed with.
     */
    private fun sameApp(context: Context, file: File): String? {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return null
        // This app, newer; or the app it moves to (a new package named in its manifest), signed alike.
        val moving = archive.packageName != context.packageName
        if (moving && archive.packageName != successor(context)) return null
        if (!moving && archive.longVersionCode <= pm.getPackageInfo(context.packageName, 0).longVersionCode) return null
        val signers = archive.signingInfo?.apkContentsSigners.orEmpty()
        val alike = signers.isNotEmpty() && signers.all {
            pm.hasSigningCertificate(context.packageName, it.toByteArray(), PackageManager.CERT_INPUT_RAW_X509)
        }
        return if (alike) archive.packageName else null
    }

    /** The package this app moves to, when its manifest names one (meta-data "handover.successor"). */
    private fun successor(context: Context): String? = runCatching {
        context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData?.getString("handover.successor")
    }.getOrNull()

    @Volatile
    private var listening = false

    /**
     * Android may answer the install with the screen that asks the reader.
     * The app is out of sight when it commits, and an activity is never
     * started from the background: the screen is held and shown at the next
     * start of the app (UpdateGate), once. A failed or cancelled install
     * clears the flag UpdatedReceiver reads. One receiver for the life of the
     * process, however many tries.
     */
    private fun listenForConfirmation(app: Context) {
        if (listening) return
        listening = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                        held = confirm
                        if (gate.pendingUserAction(foreground)) showHeld(context)
                    }
                    PackageInstaller.STATUS_SUCCESS -> Unit
                    else -> context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(INSTALLING).apply()
                }
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /**
     * Opens [start] once it answers 200. Redirects are never left to HttpURLConnection: each hop
     * is read here and must be HTTPS to one of GitHub's hosts before it is followed, three at most.
     * Ported from Mushaf's Net.open (its own code), after
     * https://developer.android.com/reference/java/net/HttpURLConnection#setInstanceFollowRedirects(boolean)
     * and https://developer.android.com/privacy-and-security/risks/cleartext.
     */
    private fun open(start: String): HttpURLConnection {
        var url = checked(URL(start))
        var hops = 0
        while (true) {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "Keyboard")
                setRequestProperty("Accept", "application/vnd.github+json, */*")
            }
            when (val code = conn.responseCode) {
                200 -> return conn
                301, 302, 303, 307, 308 -> {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (hops++ >= MAX_REDIRECTS || location.isNullOrBlank()) error("redirect refused")
                    url = checked(URL(url, location))
                }
                else -> {
                    conn.disconnect()
                    error("http $code")
                }
            }
        }
    }

    /** [url] when it is HTTPS to one of GitHub's hosts, else refused (Mushaf's Net.checked). */
    internal fun checked(url: URL): URL {
        if (url.protocol != "https" || url.host.lowercase() !in GITHUB) error("refused ${url.host}")
        return url
    }

    /** The release list and the APK: GitHub's API, its pages and where it keeps release files. */
    internal val GITHUB = setOf("api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")
    private const val MAX_REDIRECTS = 3
    /** The release list is a few kilobytes: read no more than this (Signal-Android OkHttpUtil.readAsBytes' size limit). */
    private const val MAX_JSON = 1L shl 20

    private fun get(url: String): ByteArray = open(url).run {
        try {
            inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_JSON) error("too large")
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        } finally {
            disconnect()
        }
    }

    private fun download(url: String, into: File, onProgress: (Float) -> Unit) = open(url).run {
        try {
            val total = contentLengthLong
            if (total > MAX_APK) error("too large")
            inputStream.use { input ->
                into.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    var shown = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        read += n
                        if (read > MAX_APK) error("too large")
                        out.write(buffer, 0, n)
                        // Once per percent, not once per buffer.
                        val percent = if (total > 0) (read * 100 / total).toInt() else -1
                        if (percent != shown) {
                            shown = percent
                            onProgress(if (total > 0) read.toFloat() / total else 0f)
                        }
                    }
                }
            }
        } finally {
            disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
