package bypass.whitelist.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import bypass.whitelist.App
import bypass.whitelist.BuildConfig
import bypass.whitelist.MainActivity
import bypass.whitelist.R
import cc.cors.connect.api.CorsClient
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread

/**
 * Fetches a release's APK into the app's own storage and hands it to
 * Android's installer.
 *
 * Up to 1.3.18 the update sheet gave the APK's GitHub URL to the browser and
 * stopped there. From a retail Russian line GitHub release assets come down at
 * ~33 KB/s (measured 2026-10-25), the browser gives up part-way, and what it
 * leaves in Downloads is a cut-off file that Android refuses with "there's a
 * problem with the app file" — which is how 1.3.18 failed to install. So the
 * app now does the three things the browser could not:
 *
 * - it asks our own mirror first, which those lines reach at full speed, and
 *   GitHub only after it;
 * - it resumes a cut-off download from where it stopped, over whichever route
 *   still answers, instead of starting over;
 * - it refuses to hand over a file whose size or SHA-256 differ from what the
 *   release declared.
 *
 * Installing still takes the user's tap. A sideloaded app cannot install
 * anything silently, so the last step is Android's own confirmation screen —
 * and, the first time, its "allow installs from this app" switch, which the
 * installer offers by itself.
 *
 * One download at a time, on its own thread. [state] and every listener call
 * happen on the main thread.
 */
object UpdateDownload {

    sealed class State {
        data object Idle : State()
        /** [totalBytes] is 0 when neither the release nor the server said. */
        data class Running(val doneBytes: Long, val totalBytes: Long) : State()
        data class Ready(val file: File) : State()
        data class Failed(val reason: Reason) : State()
    }

    enum class Reason {
        /** No route delivered the whole file. What did arrive is kept for the next try. */
        NETWORK,
        /** A whole file arrived and was not the one the release declared. It was deleted. */
        CORRUPT,
    }

    /** What the last download did. Main thread only; see [stateFor]. */
    var state: State = State.Idle
        private set

    /** The release [state] is about; null before the first download. */
    private var version: String? = null

    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /** [state], when it is about [update]; [State.Idle] when it is about anything else. */
    fun stateFor(update: AppUpdate): State =
        if (version == update.version) state else State.Idle

    /**
     * Calls [listener] on the main thread with every state change, starting
     * now with the current one.
     *
     * Someone watching is also what decides who finishes the job: while a
     * listener is registered the download ends silently and the listener is
     * trusted to act on it; with none, a notification says what happened. So
     * register only while there is a screen that will act — and [forget]
     * when it goes.
     */
    fun observe(listener: (State) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun forget(listener: (State) -> Unit) {
        listeners -= listener
    }

    /**
     * Starts fetching [update], unless that is already under way. Main thread.
     *
     * A file that already arrived and still verifies is reported [State.Ready]
     * without fetching anything; a part left by an earlier attempt is
     * continued, not restarted.
     */
    fun start(update: AppUpdate) {
        if (version == update.version && state is State.Running) return
        version = update.version
        publish(State.Running(0L, update.sizeBytes))
        thread(name = "update-download", isDaemon = true) {
            val outcome = run(App.instance, update)
            main.post {
                // A newer release may have started its own download meanwhile;
                // this one's answer is then about nothing anyone is showing.
                if (version == update.version) publish(outcome)
            }
        }
    }

    /**
     * Opens Android's installer on [file]. False when nothing could take it,
     * which on a phone means the package installer has been disabled.
     */
    fun install(context: Context, file: File): Boolean = try {
        context.startActivity(installIntent(context, file))
        // A "tap to install" posted while nobody watched is answered now.
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "no installer for the update: ${e.message}")
        false
    }

    /**
     * Startup housekeeping. Run once per process, before anything can start a
     * download.
     *
     * The notification goes regardless: a fresh process has no download in
     * flight, so a progress bar left by one that was killed would otherwise
     * sit in the shade forever. The downloaded APK goes once there is nothing
     * left to install — the update went in, or the release was pulled — so its
     * 35 MB does not sit in the cache for good.
     */
    fun clearStale(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        if (UpdateCheck.pending != null) return
        thread(name = "update-cleanup", isDaemon = true) {
            File(context.cacheDir, DIR).deleteRecursively()
        }
    }

    private fun publish(next: State) {
        state = next
        val watched = listeners.isNotEmpty()
        listeners.forEach { it(next) }
        notify(App.instance, next, watched)
    }

    // ---- the download itself, on the worker thread ----

    private fun run(context: Context, update: AppUpdate): State {
        val dir = File(context.cacheDir, DIR)
        val apk = File(dir, fileNameOf(update))
        val part = File(dir, apk.name + PART_SUFFIX)
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
            // Whatever is here for an older release will never be installed.
            dir.listFiles()?.forEach { if (it != apk && it != part) it.delete() }
            if (apk.exists()) {
                if (verify(context, apk, update)) return State.Ready(apk)
                apk.delete()
            }
            fetch(update, part)
            if (!verify(context, part, update)) {
                // Not a cut-off file — that is caught by size before this — but
                // a whole one that is not the release. Resuming it would only
                // rebuild the same wrong bytes, so it goes.
                part.delete()
                return State.Failed(Reason.CORRUPT)
            }
            if (!part.renameTo(apk)) throw IOException("cannot rename $part")
            State.Ready(apk)
        } catch (e: Exception) {
            // Not only IOException: anything that escaped here would end the
            // thread and leave the sheet showing "Downloading…" for good.
            Log.i(TAG, "update download stopped at ${part.length()} bytes: $e")
            State.Failed(Reason.NETWORK)
        }
    }

    /**
     * Brings [part] up to the whole file, trying [routesFor] in order.
     *
     * A route that drops the connection after delivering something is tried
     * again from where it stopped: it was working, and on these networks a
     * dropped connection is weather, not a verdict. A route that delivers
     * nothing — refused, timed out, 404 — is left for the next one, and so is
     * one that crawls. Crawling routes get a second turn, without the
     * patience limit, once nothing faster has answered: a slow route that
     * works beats none.
     */
    private fun fetch(update: AppUpdate, part: File) {
        val crawling = mutableListOf<Route>()
        for (route in routesFor(update)) {
            when (attempt(route, update, part, mayGiveUpWhenSlow = true)) {
                Pull.COMPLETE -> return
                Pull.SLOW -> crawling += route
                Pull.MISSING, null -> Unit
            }
        }
        for (route in crawling) {
            if (attempt(route, update, part, mayGiveUpWhenSlow = false) == Pull.COMPLETE) return
        }
        throw IOException("no route delivered the file")
    }

    /** [pull] on one route until it answers, or stops making progress — null then. */
    private fun attempt(route: Route, update: AppUpdate, part: File, mayGiveUpWhenSlow: Boolean): Pull? {
        repeat(ATTEMPTS_PER_ROUTE) {
            val before = part.length()
            try {
                return pull(route, update, part, mayGiveUpWhenSlow)
            } catch (e: IOException) {
                Log.i(TAG, "update route ${route.label} failed at ${part.length()} bytes: ${e.message}")
                if (part.length() <= before) return null
            }
        }
        return null
    }

    private enum class Pull { COMPLETE, SLOW, MISSING }

    /** One request on one route, continuing [part] from its current length. */
    private fun pull(route: Route, update: AppUpdate, part: File, mayGiveUpWhenSlow: Boolean): Pull {
        val offset = part.length()
        val url = URL(route.url)
        val opened = if (route.viaTunnel) url.openConnection(CorsClient.socksProxy()) else url.openConnection()
        val conn = (opened as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            // An APK is already compressed; gzip would only cost Range its meaning.
            setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            val append = when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    // A server that answers a different range than asked would
                    // splice two places of the file together.
                    val range = conn.getHeaderField("Content-Range").orEmpty()
                    if (!range.startsWith("bytes $offset-")) throw IOException("asked from $offset, got '$range'")
                    true
                }
                HttpURLConnection.HTTP_OK -> false
                416 -> {
                    // Nothing past where we are: either the part is already
                    // whole, or it is longer than the file and cannot be.
                    if (update.sizeBytes > 0 && offset == update.sizeBytes) return Pull.COMPLETE
                    part.delete()
                    throw IOException("range $offset- not satisfiable")
                }
                HttpURLConnection.HTTP_NOT_FOUND, HttpURLConnection.HTTP_GONE -> return Pull.MISSING
                else -> throw IOException("${route.label} answered $code")
            }
            val total = if (update.sizeBytes > 0) update.sizeBytes else {
                val length = conn.contentLengthLong
                if (length > 0) (if (append) offset else 0L) + length else 0L
            }
            FileOutputStream(part, append).use { out ->
                conn.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var done = if (append) offset else 0L
                    val startedAt = SystemClock.elapsedRealtime()
                    val startedFrom = done
                    var reportedAt = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        if (total in 1 until done) throw IOException("longer than the declared $total bytes")
                        val now = SystemClock.elapsedRealtime()
                        if (now - reportedAt >= PROGRESS_EVERY_MS) {
                            reportedAt = now
                            val snapshot = State.Running(done, total)
                            main.post { if (state is State.Running) publish(snapshot) }
                        }
                        // Judged over a whole window, not per read: a link that
                        // stalls for a few seconds and then catches up is fine.
                        val elapsed = now - startedAt
                        if (mayGiveUpWhenSlow && elapsed >= SLOW_WINDOW_MS) {
                            val rate = (done - startedFrom) * 1000L / elapsed
                            if (rate < MIN_BYTES_PER_SECOND) {
                                Log.i(TAG, "update route ${route.label} crawls at $rate B/s, trying the next")
                                return Pull.SLOW
                            }
                        }
                    }
                    // The stream ended. Whether it ended at the end is the
                    // question the browser never asked.
                    if (total > 0 && done < total) throw IOException("cut off at $done of $total")
                    return Pull.COMPLETE
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private data class Route(val url: String, val viaTunnel: Boolean) {
        val label: String get() = (if (viaTunnel) "tunnel:" else "direct:") + Uri.parse(url).host
    }

    /**
     * Where the APK can come from, best first.
     *
     * Our mirror before GitHub: it carries the same file (N100 copies it from
     * the release and checks the hash), and Russian lines reach it at full
     * speed. Each source directly before through the tunnel: the app excludes
     * itself from its own VpnService, so "directly" is whatever network the
     * phone has, and on a whitelist tariff the tunnel's local SOCKS5 is the
     * only way out. Without a tunnel that route is a refused connect to a
     * closed local port, which costs nothing.
     */
    private fun routesFor(update: AppUpdate): List<Route> {
        val sources = buildList {
            if (update.assetName.isNotEmpty()) {
                add("https://${BuildConfig.PC_CALLBACK_HOST}/api/apk/${Uri.encode(update.assetName)}")
            }
            add(update.downloadUrl)
        }
        return sources.flatMap { listOf(Route(it, viaTunnel = false), Route(it, viaTunnel = true)) }
    }

    /**
     * True when [file] is exactly the release's APK, and Android would take it
     * as an update of this app.
     *
     * The hash settles the first part whenever GitHub gave one. The package
     * check is the last word for a release without a digest, and it also
     * catches a build of something else or of an older version — both of
     * which the installer would refuse with a message no user can act on.
     */
    private fun verify(context: Context, file: File, update: AppUpdate): Boolean {
        if (update.sizeBytes > 0 && file.length() != update.sizeBytes) return false
        if (update.sha256.isNotEmpty() && sha256Of(file) != update.sha256) {
            Log.w(TAG, "update ${file.name}: hash differs from the release")
            return false
        }
        val info = context.packageManager.getPackageArchiveInfo(file.path, 0) ?: return false
        return info.packageName == context.packageName &&
            PackageInfoCompat.getLongVersionCode(info) > BuildConfig.VERSION_CODE
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** The asset's own name when it is a plain file name; never a path. */
    private fun fileNameOf(update: AppUpdate): String {
        val name = update.assetName
        val plain = name.isNotEmpty() && name.endsWith(".apk") &&
            name.all { it.isLetterOrDigit() || it in "._-" } && !name.startsWith(".")
        return if (plain) name else "prometheus-connect-${update.version.filter { it.isDigit() || it == '.' }}.apk"
    }

    private fun installIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    // ---- the notification: for when nobody is watching ----

    /**
     * Mirrors [state] in the notification shade, so a download outlives the
     * sheet it was started from.
     *
     * Progress is shown regardless. The end is shown only when nobody is
     * watching: the sheet that is open opens the installer itself, and a
     * "tap to install" left behind it would outlive the install.
     */
    private fun notify(context: Context, state: State, watched: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val release = version ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, context.getString(R.string.notification_channel_update),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            Notification.Builder(context)
        }.setSmallIcon(android.R.drawable.stat_sys_download)
        val notification = when (state) {
            is State.Running -> {
                val total = state.totalBytes
                val percent = if (total > 0) (state.doneBytes * 100 / total).toInt() else 0
                builder
                    .setContentTitle(context.getString(R.string.update_notification_downloading, release))
                    .setProgress(100, percent, total <= 0)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(openApp(context))
                    .build()
            }
            is State.Ready -> {
                if (watched) {
                    nm.cancel(NOTIFICATION_ID)
                    return
                }
                builder
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(context.getString(R.string.update_notification_ready, release))
                    .setContentText(context.getString(R.string.update_notification_ready_sub))
                    .setAutoCancel(true)
                    .setContentIntent(
                        PendingIntent.getActivity(
                            context, REQUEST_INSTALL, installIntent(context, state.file),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        )
                    )
                    .build()
            }
            is State.Failed -> {
                if (watched) {
                    nm.cancel(NOTIFICATION_ID)
                    return
                }
                builder
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle(context.getString(R.string.update_notification_failed, release))
                    .setContentText(context.getString(R.string.update_notification_failed_sub))
                    .setAutoCancel(true)
                    .setContentIntent(openApp(context))
                    .build()
            }
            State.Idle -> {
                nm.cancel(NOTIFICATION_ID)
                return
            }
        }
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, REQUEST_OPEN,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private const val TAG = "UpdateDownload"
    /** Under cacheDir, which file_paths.xml already exposes to the FileProvider. */
    private const val DIR = "updates"
    private const val PART_SUFFIX = ".part"
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val CHANNEL_ID = "update"
    private const val NOTIFICATION_ID = 4107
    private const val REQUEST_INSTALL = 4107
    private const val REQUEST_OPEN = 4108
    private const val ATTEMPTS_PER_ROUTE = 4
    private const val CONNECT_TIMEOUT_MS = 10_000
    /** Long enough for a tunnel that pauses while it rebinds; short enough to move on from a dead one. */
    private const val READ_TIMEOUT_MS = 30_000
    private const val BUFFER_BYTES = 64 * 1024
    /** Notifications are rate-limited by the system; more often than this they are dropped. */
    private const val PROGRESS_EVERY_MS = 750L
    private const val SLOW_WINDOW_MS = 20_000L
    /**
     * Below this a 35 MB file takes over ten minutes, and some other route is
     * worth a try. GitHub to a Russian line was measured at ~33 KB/s.
     */
    private const val MIN_BYTES_PER_SECOND = 48 * 1024L
    private val USER_AGENT = "PrometheusConnect/${BuildConfig.VERSION_NAME}"
}
