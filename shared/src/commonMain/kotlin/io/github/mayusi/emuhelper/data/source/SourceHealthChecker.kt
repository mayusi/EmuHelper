package io.github.mayusi.emuhelper.data.source

// Phase (Windows port): SourceHealthChecker moved to :shared commonMain so the desktop app can
// reuse the same IA mirror/datacenter reachability probing as Android. The ONLY former Android
// coupling — android.util.Log — is now the multiplatform seam:
//   • android.util.Log  -> io.github.mayusi.emuhelper.platform.Log (installed per-platform)
//   • @Inject/@Singleton-> plain constructor injection (Android wires it in its Hilt @Module;
//                          desktop builds it by hand). The probing LOGIC is unchanged.
import io.github.mayusi.emuhelper.data.config.Catalog
import io.github.mayusi.emuhelper.platform.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A single source URL result from a health check. */
data class SourceHealth(
    val console: String,
    val url: String,
    val alive: Boolean,
    /** HTTP status code as a string (e.g. "200", "403") or a short error description. */
    val detail: String
)

/**
 * Pings each configured source endpoint and reports which are alive/dead.
 * Uses a lightweight HEAD or ranged-GET request with a short timeout so the
 * full check finishes in reasonable time even for large catalogs.
 *
 * Concurrency is bounded by a [Semaphore] with [MAX_CONCURRENCY] permits.
 */
class SourceHealthChecker(
    okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "SourceHealthChecker"
        private const val MAX_CONCURRENCY = 6
        private const val TIMEOUT_MS = 12_000L
    }

    /** Cookie-less client so health checks never carry session credentials. */
    private val client: OkHttpClient = okHttpClient
        .newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Checks all endpoints in [Catalog.IA_LINKS] and returns a [SourceHealth] per URL.
     * [onProgress] is called after each URL completes with (done, total) counts.
     */
    suspend fun checkAll(
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): List<SourceHealth> = withContext(Dispatchers.IO) {
        // Flatten to a stable list of (console, url) pairs.
        val work: List<Pair<String, String>> = Catalog.IA_LINKS
            .entries
            .flatMap { (console, urls) -> urls.map { console to it } }

        val total = work.size
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val semaphore = Semaphore(MAX_CONCURRENCY)

        coroutineScope {
            work.map { (console, url) ->
                async {
                    val result = semaphore.withPermit { probe(console, url) }
                    val completed = done.incrementAndGet()
                    onProgress(completed, total)
                    result
                }
            }.awaitAll()
        }
    }

    /**
     * Send a HEAD request (or ranged GET fallback) and interpret the response.
     *
     * FIX 3: The OkHttp [Call] is captured before execute() so we can cancel it in the
     * finally block when the coroutine is cancelled. Without this, the blocking execute()
     * keeps the TCP connection open for up to [TIMEOUT_MS] even after the coroutine scope
     * is cancelled, wasting radios during active downloads on a handheld device.
     */
    private fun probe(console: String, url: String): SourceHealth {
        val request = Request.Builder()
            .url(url)
            .head()
            .header("User-Agent", "Mozilla/5.0")
            .build()
        val call: Call = client.newCall(request)
        return try {
            call.execute().use { resp ->
                val code = resp.code
                val alive = code in 200..399 // 2xx, 3xx — reachable
                if (!alive && code == 405) {
                    // HEAD not allowed: fall back to a ranged GET
                    return rangedGet(console, url)
                }
                Log.i(TAG, "HEAD $url -> $code")
                SourceHealth(console, url, alive, code.toString())
            }
        } catch (e: IOException) {
            // Check if this was due to cancellation (call.isCanceled()) or a real network error.
            if (call.isCanceled()) throw kotlinx.coroutines.CancellationException("probe cancelled")
            val msg = e.message?.take(80) ?: e.javaClass.simpleName
            Log.i(TAG, "probe $url -> exception: $msg")
            SourceHealth(console, url, alive = false, detail = msg)
        } catch (e: Exception) {
            if (call.isCanceled()) throw kotlinx.coroutines.CancellationException("probe cancelled")
            val msg = e.message?.take(80) ?: e.javaClass.simpleName
            Log.i(TAG, "probe $url -> exception: $msg")
            SourceHealth(console, url, alive = false, detail = msg)
        } finally {
            // Cancel the in-flight call whenever the coroutine is done with this probe
            // (either it completed normally, threw, or the coroutine was cancelled).
            // Calling cancel() on an already-finished call is a safe no-op.
            call.cancel()
        }
    }

    /** Fallback for servers that reject HEAD: fetch first byte only. */
    private fun rangedGet(console: String, url: String): SourceHealth {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", "Mozilla/5.0")
            .header("Range", "bytes=0-0")
            .build()
        val call: Call = client.newCall(request)
        return try {
            call.execute().use { resp ->
                resp.body?.close()
                val code = resp.code
                val alive = code in 200..399
                Log.i(TAG, "GET(ranged) $url -> $code")
                SourceHealth(console, url, alive, "$code")
            }
        } catch (e: IOException) {
            if (call.isCanceled()) throw kotlinx.coroutines.CancellationException("rangedGet cancelled")
            val msg = e.message?.take(80) ?: e.javaClass.simpleName
            SourceHealth(console, url, alive = false, detail = msg)
        } catch (e: Exception) {
            if (call.isCanceled()) throw kotlinx.coroutines.CancellationException("rangedGet cancelled")
            val msg = e.message?.take(80) ?: e.javaClass.simpleName
            SourceHealth(console, url, alive = false, detail = msg)
        } finally {
            call.cancel()
        }
    }
}
