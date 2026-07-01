package io.github.mayusi.emuhelper.data.source

// Phase 2 (Windows port): RemoteSource now lives in :shared commonMain so the desktop app reuses the
// IA networking + the laned download driver byte-for-byte. The ONLY former Android couplings —
// android.util.Log, BuildConfig, AuthStore, Hilt — are now multiplatform seams:
//   • android.util.Log  -> io.github.mayusi.emuhelper.platform.Log (installed per-platform)
//   • BuildConfig.DEBUG -> the injected [appInfo].debug
//   • AuthStore         -> the narrow [AuthCredentials] seam (android AuthStore implements it)
//   • @Inject/@Singleton-> plain constructor injection (Android wires it in its Hilt @Module; desktop
//                          builds it by hand). The download LOGIC is unchanged.
import io.github.mayusi.emuhelper.platform.Log
import io.github.mayusi.emuhelper.platform.AppInfo
import io.github.mayusi.emuhelper.platform.AuthCredentials
import io.github.mayusi.emuhelper.data.model.GameFile
import io.github.mayusi.emuhelper.di.PersistentCookieJar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.math.min

class RemoteSource(
    private val okHttpClient: OkHttpClient,
    private val cookieJar: PersistentCookieJar,
    private val authStore: AuthCredentials,
    private val appInfo: AppInfo = AppInfo.UNKNOWN,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * SESSION-EXPIRY RECOVERY (silent single-flight re-auth). A long overnight batch can outlive the
     * IA auth session; the datanodes then 401 the next ranged GET and the chunk would fail with no
     * recovery. This coordinator collapses the many concurrent 401s of a laned download into ONE
     * re-login (with the saved credentials) and lets each affected request retry exactly once per
     * re-login generation, so a transient session expiry never surfaces as a download failure. The
     * login POST goes through the SHARED cookie jar, so a single success immediately fixes every other
     * in-flight request. See [withReauthRetry] for how request paths consume it, and ReauthCoordinator
     * for the single-flight + retry-once + no-infinite-loop logic (unit-tested without network).
     */
    private val reauth = ReauthCoordinator(
        doLogin = {
            val email = authStore.savedEmailNow()
            val password = authStore.getSavedPassword()
            if (email.isBlank() || password.isBlank()) false
            else login(email, password) is LoginResult.Success
        },
        credentialsAvailable = {
            authStore.savedEmailNow().isNotBlank() && authStore.getSavedPassword().isNotBlank()
        }
    )

    /**
     * Marker so a 401 (or auth-expiry 403) thrown deep inside a request path can be recognised by
     * [withReauthRetry] and trigger the silent re-auth+retry, while any OTHER IOException keeps its
     * existing behaviour untouched (stall/error budgets, partial-resume, etc.). Carrying the HTTP
     * [code] lets the wrapper apply the 401-always / 403-only-on-auth-hint policy in one place.
     */
    internal class AuthExpiredException(val code: Int) :
        IOException("HTTP $code (auth session expired)")

    /** Clear, user-facing message when re-auth can't proceed (no creds) or the re-login itself fails. */
    private val sessionExpiredMessage = "Session expired — sign in again."

    /**
     * Wrap a single download request [block] so that, if it throws [AuthExpiredException] (HTTP 401, or
     * an auth-expiry 403), we SILENTLY re-authenticate once via the single-flight [reauth] coordinator
     * and retry the block. Composition guarantees:
     *
     *  - A successful re-auth + retry is INVISIBLE to the caller: [block] returns normally, so the
     *    chunk's error/stall budget is never touched (the 401 never reaches the chunk's `catch`).
     *  - At most ONE retry per re-login generation: the coordinator's generation counter prevents an
     *    infinite 401 -> login -> 401 loop. If the resource still 401s under a fresh session, we
     *    rethrow the auth failure (a real error) so the normal error budget governs the outcome.
     *  - No saved creds OR the re-login fails -> we throw a plain IOException with [sessionExpiredMessage]
     *    so the failure is clear and final (no retry).
     *
     * This adds NO download connections: the login POST is a separate, permit-free request issued by
     * the coordinator, so the 24-connection cap and all governors are untouched.
     *
     * INLINE so a non-local `return` inside [block] (e.g. the laned racer's done-check bail) still
     * returns from the ENCLOSING download function, preserving the existing racer/stall control flow.
     */
    private suspend inline fun <T> withReauthRetry(block: () -> T): T {
        // Record the generation we START at; a 401 we then hit is only "ours to retry" if no newer
        // login already happened (handled inside the coordinator) AND we haven't already retried in
        // this generation (tracked by lastRetriedGen below).
        var lastRetriedGen = ReauthCoordinator.NEVER_RETRIED
        var observedGen = reauth.currentGeneration()
        while (true) {
            try {
                return block()
            } catch (e: AuthExpiredException) {
                // Retry-once gate: if we've already retried in the current generation, the fresh
                // session still can't read this -> genuine failure (do not loop).
                if (!reauth.shouldAttemptReauth(lastRetriedGen)) {
                    throw IOException(sessionExpiredMessage)
                }
                when (val outcome = reauth.requestReauth(observedGen)) {
                    is ReauthCoordinator.Outcome.Retry -> {
                        // Re-auth succeeded (ours or a concurrent caller's). Record the generation we
                        // are now retrying under so a repeat 401 in the SAME generation fails fast.
                        lastRetriedGen = outcome.generation
                        observedGen = outcome.generation
                        // loop -> retry block() once on the fresh session
                    }
                    ReauthCoordinator.Outcome.NoCredentials,
                    ReauthCoordinator.Outcome.Failed -> {
                        throw IOException(sessionExpiredMessage)
                    }
                }
            }
        }
    }

    /**
     * Bounded cache of parsed metadata JSON roots, keyed by identifier.
     * Eliminates redundant network + parse work when mirrorUrls(), fetchFileList(), and
     * fetchFileSize() are called for the same identifier during a batch (e.g. mirrorUrls
     * is called once per file, so a 30-file batch would otherwise hit the endpoint 30×).
     *
     * FIX #12: this previously used a ConcurrentHashMap that grew unbounded for the whole
     * process lifetime — every distinct item the user ever scanned kept its full metadata
     * JSON (potentially MBs each) pinned in memory. Now it's an access-order LinkedHashMap
     * capped at [METADATA_CACHE_MAX] entries with LRU eviction, wrapped in a tiny
     * thread-safe accessor that preserves the get / putIfAbsent semantics callers rely on.
     */
    private val metadataCache = BoundedMetadataCache(METADATA_CACHE_MAX)

    /**
     * BATCH-WIDE SETUP ELISION (v0.9): per-IA-item cache of the resolved+ranked host prefixes, probe
     * rates, and range support. Resolving + ranking + range-probing the mirrors is IDENTICAL for every
     * file of one item (same datacenters, same speed order, same range support — only the trailing
     * path differs), so we do it ONCE per identifier and reuse it for the rest of the batch. This
     * removes ~(2N+1) round-trips + N×256 KB of probe transfer per subsequent file and adds NO
     * connections (strictly fewer), so the 24-cap is untouched. TTL-bounded so a stale entry can't
     * outlive the conditions it measured; a host that dies mid-batch is evicted from the ranking.
     */
    private val hostResolutionCache = HostResolutionCache()

    /**
     * Synchronized LRU cache. accessOrder=true means each get()/put() moves the entry to
     * the most-recently-used end, so the eldest (least-recently-used) entry is evicted once
     * size exceeds [maxEntries]. All access is synchronized on the instance — the maps are
     * tiny (<=20 small references) and lookups are O(1), so contention is negligible.
     */
    private class BoundedMetadataCache(private val maxEntries: Int) {
        private val map = object :
            LinkedHashMap<String, kotlinx.serialization.json.JsonObject>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, kotlinx.serialization.json.JsonObject>?
            ): Boolean = size > maxEntries
        }

        @Synchronized
        operator fun get(key: String): kotlinx.serialization.json.JsonObject? = map[key]

        /**
         * Mirrors ConcurrentHashMap.putIfAbsent: stores [value] only if [key] is absent and
         * returns the PREVIOUS value (non-null when another caller already cached one), or
         * null if this call inserted. Callers use `putIfAbsent(k, v) ?: v` to always end up
         * with the winning instance.
         */
        @Synchronized
        fun putIfAbsent(
            key: String,
            value: kotlinx.serialization.json.JsonObject
        ): kotlinx.serialization.json.JsonObject? {
            val existing = map[key]
            if (existing != null) return existing
            map[key] = value
            return null
        }
    }

    companion object {
        private val SKIP_EXTS = setOf(".xml", ".json", ".sqlite", ".txt", ".md", ".torrent", ".log", ".csv", ".pdf", ".jpg", ".png", ".gif", ".ico")
        private const val MIN_FILE_SIZE = 102400L
        private const val MAX_ATTEMPTS = 5
        /** FIX #12: hard cap on cached metadata roots (LRU eviction beyond this). */
        private const val METADATA_CACHE_MAX = 20
        /** RESUME (v0.8): the sidecar manifest filename is `<part>.manifest`. */
        private const val MANIFEST_SUFFIX = ".manifest"
        /** RESUME: throttle manifest writes to at most one per this interval (cheap, not per-byte). */
        private const val MANIFEST_SAVE_INTERVAL_MS = 750L
        // Browser-like UA. The configured source host occasionally serves different
        // content to non-browser UAs; this pattern maximises compatibility.
        private const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    fun isLoggedIn(): Boolean = cookieJar.hasCookies()

    suspend fun login(email: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        try {
            // The source host uses a "cookie-acceptance" handshake: the FIRST POST
            // sets a test cookie (and possibly a CSRF token) but just re-serves the
            // login form; only a SECOND POST — once the test cookie is present — actually
            // authenticates. That's the classic "fails first time, works on retry".
            // So we prime the cookies (GET + a throwaway POST), then POST credentials,
            // and retry up to 3x until the session cookie (logged-in-sig) appears.

            // Prime: GET the login page so any initial Set-Cookie lands in the jar.
            try {
                okHttpClient.newCall(
                    Request.Builder().url("https://archive.org/account/login")
                        .header("User-Agent", USER_AGENT).get().build()
                ).execute().use { it.body?.close() }
            } catch (e: Exception) {
                Log.w("EmuHelper", "login: priming GET failed, continuing", e)
            }

            fun postCreds(): Pair<Int, String> {
                val formBody = FormBody.Builder()
                    .add("username", email)
                    .add("password", password)
                    .add("submit-by-login", "1")
                    .add("remember", "1")
                    .build()
                okHttpClient.newCall(
                    Request.Builder().url("https://archive.org/account/login")
                        .header("User-Agent", USER_AGENT)
                        .header("Referer", "https://archive.org/account/login")
                        .header("Origin", "https://archive.org")
                        .post(formBody).build()
                ).execute().use { resp ->
                    return resp.code to (resp.body?.string() ?: "")
                }
            }

            var lastCode = 0
            // The SUCCESS oracle is the auth cookie appearing (hasCookies()). The first
            // POST is the cookie-acceptance round (sets test-cookie, no auth yet) and
            // re-serves the login HTML — which may contain the word "Invalid" in validation
            // markup, so we must NOT treat loose HTML text as a failure. Only an explicit
            // JSON error ({"status":"error"}) or a clear bad-credentials JSON message is
            // a real failure. We always run the full handshake up to 4 times.
            for (attempt in 0 until 4) {
                val (code, body) = postCreds()
                lastCode = code
                if (appInfo.debug) { Log.i("EmuHelper", "login attempt $attempt: code=$code hasCookies=${cookieJar.hasCookies()}") }

                if (cookieJar.hasCookies()) {
                    return@withContext LoginResult.Success
                }
                // Only stop early on a DEFINITIVE bad-credentials signal (JSON, or the
                // specific error phrases from the source host — not the generic word "Invalid").
                val definiteFail =
                    body.contains("\"status\":\"error\"", ignoreCase = true) ||
                    body.contains("incorrect username or password", ignoreCase = true) ||
                    body.contains("no account found", ignoreCase = true) ||
                    body.contains("your password is incorrect", ignoreCase = true) ||
                    body.contains("could not find an account", ignoreCase = true)
                if (definiteFail) {
                    return@withContext LoginResult.Failed("Invalid email or password.")
                }
                // Otherwise it's the cookie-acceptance round — loop and retry so the
                // user only has to press Login ONCE.
                kotlinx.coroutines.delay(150)
            }
            // Exhausted retries without an auth cookie and without a clear error.
            if (cookieJar.hasCookies()) LoginResult.Success
            else LoginResult.Failed("Couldn't sign in (HTTP $lastCode). Check your details and try again.")
        } catch (e: IOException) {
            LoginResult.Failed("Network error: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            LoginResult.Failed("${e.javaClass.simpleName}: ${e.message ?: "unknown error"}")
        }
    }

    fun getIdentifier(url: String): String {
        // Matches both /details/<id> and /download/<id>[/subpath...] paths
        val m = Regex("archive\\.org/(?:details|download)/([^/?#]+)").find(url)
        return m?.groupValues?.get(1)?.trim()
            ?: url.trim().trimEnd('/').substringAfterLast('/')
    }

    /**
     * Builds the canonical download URL for a file inside a remote item.
     *
     * [relativeName] is the file's `name` from the metadata, which may include a
     * subdirectory path (e.g. "subdir/Filename.chd"). We must percent-encode each
     * path SEGMENT but keep the "/" separators, like `quote(name, safe="/")`.
     * Using java.net.URLEncoder here is wrong — it encodes "/" as %2F and "+"
     * handling differs, which breaks every file that lives in a subdirectory.
     */
    fun buildDownloadUrl(identifier: String, relativeName: String): String {
        val encodedPath = relativeName.split('/').joinToString("/") { encodeSegment(it) }
        return "https://archive.org/download/$identifier/$encodedPath"
    }

    /**
     * Fetch (or return cached) parsed metadata JSON root for [identifier].
     * The metadata endpoint is public and the payload is stable within a process lifetime,
     * so caching aggressively eliminates repeated network + parse overhead during a batch
     * where mirrorUrls() is called once per file for the same identifier.
     */
    private suspend fun fetchMetadata(identifier: String): kotlinx.serialization.json.JsonObject? {
        metadataCache[identifier]?.let { return it }
        return try {
            val req = Request.Builder().url("https://archive.org/metadata/$identifier")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return null
                val root = json.parseToJsonElement(body).jsonObject
                // putIfAbsent so a concurrent caller's result wins safely; either is fine.
                metadataCache.putIfAbsent(identifier, root) ?: root
            }
        } catch (e: Exception) {
            Log.w("EmuHelper", "fetchMetadata failed for $identifier", e)
            null
        }
    }

    /**
     * All mirror URLs that serve this file. The source host's metadata exposes several
     * hosts per item — the primary `download` endpoint plus direct `d1`/`d2`
     * datanodes and any `workable` CDN nodes. Returning them all lets the downloader
     * spread segments across hosts and fail a slow/broken node over to another.
     *
     * The canonical download URL is always first (it redirects to a live node and
     * is the safest default).
     */
    suspend fun mirrorUrls(identifier: String, relativeName: String): List<String> = withContext(Dispatchers.IO) {
        val encodedPath = relativeName.split('/').joinToString("/") { encodeSegment(it) }
        val result = LinkedHashSet<String>()
        result.add("https://archive.org/download/$identifier/$encodedPath")
        try {
            val root = fetchMetadata(identifier) ?: return@withContext result.toList()
            val dir = root["dir"]?.jsonPrimitive?.contentOrNull
            fun add(host: String?, d: String?) {
                if (!host.isNullOrBlank() && !d.isNullOrBlank()) {
                    result.add("https://$host${d.trimEnd('/')}/$encodedPath")
                }
            }
            add(root["d1"]?.jsonPrimitive?.contentOrNull, dir)
            add(root["d2"]?.jsonPrimitive?.contentOrNull, dir)
            // workable / alternate CDN nodes carry their own dir
            root["alternate_locations"]?.jsonObject?.get("workable")?.let { wk ->
                runCatching {
                    wk.jsonArray.forEach { node ->
                        val o = node.jsonObject
                        add(o["server"]?.jsonPrimitive?.contentOrNull, o["dir"]?.jsonPrimitive?.contentOrNull)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("EmuHelper", "mirrorUrls failed for $identifier", e)
        }
        result.toList()
    }

    /**
     * CONNECTION PRE-WARMING (v0.9) — best-effort, cancellable warm-up of an item's per-mirror h2
     * sockets BEFORE its first download, so chunk 1 skips the TCP+TLS+h2 handshake (the OkHttp pool
     * keeps connections warm for 5 min — see AppModule). Call when the user scans an item / lands on
     * the download-confirm screen.
     *
     * It resolves the item's mirror hosts for [relativeName], then [decidePrewarm] gates it: at most
     * 3 hosts (one per datacenter), and NEVER on a metered network when [wifiOnly] is on. Each warm-up
     * is a trivial bytes=0-0 GET — it acquires NO download permit (pre-Semaphore, so it can never push
     * the live connection count over the 24-cap) and any failure is swallowed (warming is pure upside).
     *
     * NOTE: the heavier per-item resolve+rank probe that the download itself runs ALSO warms these
     * sockets (one action, two wins — see downloadFileSegmented's cache MISS path); this method is the
     * EARLIER, lighter touch for the confirm-screen moment before a download is even started.
     */
    suspend fun prewarmItem(
        identifier: String,
        relativeName: String,
        wifiOnly: Boolean,
        isMetered: Boolean,
        isCancelled: () -> Boolean = { false }
    ): Unit = withContext(Dispatchers.IO) {
        try {
            val mirrors = mirrorUrls(identifier, relativeName)
            // Resolve redirects to the real datanodes (deduped) so we warm the ACTUAL sockets used.
            val resolved = mirrors.mapNotNull { if (isCancelled()) null else resolveFinalUrl(it) ?: it }
                .distinct()
            val decision = decidePrewarm(resolved, wifiOnly = wifiOnly, isMetered = isMetered)
            if (!decision.warm) return@withContext
            coroutineScope {
                decision.hosts.map { host ->
                    async {
                        if (isCancelled()) return@async
                        runCatching {
                            val req = Request.Builder().url(host)
                                .header("User-Agent", USER_AGENT)
                                .header("Accept-Encoding", "identity")
                                .header("Range", "bytes=0-0") // trivial; only to open/keep the socket warm
                                .header("Referer", "https://archive.org/")
                                .build()
                            okHttpClient.newCall(req).execute().use { it.body?.close() }
                        }
                        Unit
                    }
                }.awaitAll()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("EmuHelper", "prewarmItem($identifier) failed (best-effort, ignored)", e)
        }
    }

    private fun encodeSegment(segment: String): String {
        val sb = StringBuilder(segment.length + 16)
        for (b in segment.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            // RFC 3986 "unreserved" characters safe to leave unencoded in URL paths.
            val safe = c.toChar().let { ch ->
                ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                    ch == '-' || ch == '_' || ch == '.' || ch == '~'
            }
            if (safe) sb.append(c.toChar())
            else sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0x0F])
        }
        return sb.toString()
    }

    /**
     * Fetches the list of game files for a remote source identifier.
     * Returns an empty list when the item is valid but contains no usable game files.
     * Throws on network/parse failures so the caller can surface them.
     */
    suspend fun fetchFileList(iaUrl: String): List<GameFile> = withContext(Dispatchers.IO) {
        val identifier = getIdentifier(iaUrl)
        // Use the shared metadata cache to avoid re-fetching when multiple sources share
        // the same identifier, and so subsequent mirrorUrls/fetchFileSize calls are free.
        // SESSION-EXPIRY RECOVERY: wrap the metadata fetch so a 401 (restricted item whose session
        // expired) silently re-authenticates once and retries instead of failing the scan.
        val root = metadataCache[identifier] ?: withReauthRetry {
            val request = Request.Builder()
                .url("https://archive.org/metadata/$identifier")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                // A 401 (or auth-expiry 403) means the session lapsed mid-batch -> trigger re-auth.
                if (ReauthCoordinator.isAuthExpiry(response.code, authExpiryHint = true)) {
                    throw AuthExpiredException(response.code)
                }
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} for $identifier")
                }
                val body = response.body?.string()
                    ?: throw IOException("Empty body for $identifier")
                if (!body.trimStart().startsWith("{")) {
                    Log.w("EmuHelper", "Non-JSON metadata for $identifier: ${body.take(200)}")
                    throw IOException("Non-JSON metadata for $identifier")
                }
                val parsed = json.parseToJsonElement(body).jsonObject
                metadataCache.putIfAbsent(identifier, parsed) ?: parsed
            }
        }

        val files = root["files"]?.jsonArray
            ?: throw IOException("Item '$identifier' has no files (may be dead or restricted)")

        val kept = files.mapNotNull { f ->
            val obj = f.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (name.endsWith("/")) return@mapNotNull null
            val ext = File(name).extension.lowercase()
            if (".$ext" in SKIP_EXTS) return@mapNotNull null
            val size = obj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            if (size < MIN_FILE_SIZE) return@mapNotNull null
            // Source metadata exposes a per-file md5 hex string; carry it through so the
            // download engine can verify integrity after a multi-segment download. Absent
            // or blank means "unverified" downstream (back-compat, no behaviour change).
            val md5 = obj["md5"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: ""
            GameFile(
                name = name,
                filename = File(name).name,
                size = size,
                identifier = identifier,
                sourceUrl = iaUrl,
                md5 = md5
            )
        }
        Log.i("EmuHelper", "scan/$identifier  rawFiles=${files.size} kept=${kept.size}")
        kept
    }

    /**
     * Multi-connection download into a LOCAL file using parallel HTTP Range requests.
     *
     * The source host may throttle a single TCP stream; pulling N ranges in parallel
     * multiplies throughput dramatically on big files. We write to a real local File
     * via RandomAccessFile (random-access seek) — SAF content URIs can't seek
     * reliably, so the caller downloads to app cache and then copies the finished
     * file to the user's chosen folder in one sequential pass.
     *
     * Falls back to single-stream if the server won't honour Range or size is unknown.
     *
     * @param onProgress called ~periodically with (bytesDownloadedTotal, bytesPerSecond).
     * @return total bytes written.
     */
    /**
     * Probe each resolved host with a small timed Range GET and rank them
     * fastest-first. Hosts that error, return a rate-limit stub, or are far slower
     * than the best are dropped. Returns a weighted pool where faster hosts appear
     * more often, so segments get distributed toward the fast nodes.
     *
     * CDN nodes are often rate-limited while datanodes are faster; naive even
     * splitting lets a slow node bottleneck the whole file, so we weight by speed.
     */
    private suspend fun rankHosts(
        resolvedHosts: List<String>,
        // FIX B: optional sink for the REAL measured probe rate (bytes/ms) per host. The adaptive
        // engine uses this to seed its EWMA scoreboard in CORRECT units (bytes/ms) instead of the
        // old bogus "slot count × 100" seed. Default null -> the static path is unaffected and the
        // ranking output is byte-for-byte identical to before.
        probeRatesOut: MutableMap<String, Double>? = null
    ): List<String> = withContext(Dispatchers.IO) {
        if (resolvedHosts.size <= 1) return@withContext resolvedHosts
        data class Probe(val url: String, val bytesPerMs: Double)
        val probes = coroutineScope {
            resolvedHosts.map { host ->
                async {
                    try {
                        val req = Request.Builder().url(host)
                            .header("User-Agent", USER_AGENT)
                            .header("Accept-Encoding", "identity")
                            .header("Range", "bytes=0-262143") // 256 KB
                            .header("Referer", "https://archive.org/")
                            .build()
                        val t0 = System.currentTimeMillis()
                        okHttpClient.newCall(req).execute().use { resp ->
                            if (resp.code != 206) return@async Probe(host, 0.0)
                            val bytes = resp.body?.bytes()?.size ?: 0
                            val ms = (System.currentTimeMillis() - t0).coerceAtLeast(1)
                            // Reject rate-limit stubs (tiny bodies for a 256KB request).
                            if (bytes < 100_000) Probe(host, 0.0) else Probe(host, bytes.toDouble() / ms)
                        }
                    } catch (e: Exception) {
                        Probe(host, 0.0)
                    }
                }
            }.awaitAll()
        }
        // FIX B: surface the real measured rates (bytes/ms) for the adaptive seed, if requested.
        if (probeRatesOut != null) {
            for (p in probes) if (p.bytesPerMs > 0.0) probeRatesOut[p.url] = p.bytesPerMs
        }
        val good = probes.filter { it.bytesPerMs > 0.0 }.sortedByDescending { it.bytesPerMs }
        if (good.isEmpty()) return@withContext resolvedHosts
        val best = good.first().bytesPerMs
        // Keep hosts within 4x of the best; weight ~ relative speed (1..4 slots each).
        val pool = ArrayList<String>()
        for (p in good) {
            if (p.bytesPerMs * 4 < best) continue // drop nodes >4x slower (rate-limited)
            val weight = ((p.bytesPerMs / best) * 4).toInt().coerceIn(1, 4)
            repeat(weight) { pool.add(p.url) }
        }
        Log.i("EmuHelper", "rankHosts: " + good.joinToString { "${it.url.substringAfter("//").substringBefore('/')}=${"%.0f".format(it.bytesPerMs)}B/ms (ranked)" })
        pool.ifEmpty { listOf(good.first().url) }
    }

    // PUBLIC again (Phase 2 Windows port): this is now in :shared and [DownloadManager] lives in a
    // SEPARATE module (:app), so the driver must be public to be callable across the boundary. Its
    // scheduler params ([MirrorScheduler], [FileDemand]) are PUBLIC in :shared now (they were made
    // public when the engine moved in Phase 1a), so a public signature is legal again. (It had been
    // made `internal` only while everything shared one module.)
    suspend fun downloadFileSegmented(
        candidateUrls: List<String>,
        expectedSize: Long,
        destFile: File,
        segments: Int,
        onProgress: suspend (Long, Double) -> Unit,
        isCancelled: () -> Boolean,
        // ADAPTIVE ENGINE (default-OFF). When false the EXACT static path below runs unchanged.
        adaptive: Boolean = false,
        // Shared global connection budget (the DownloadManager's Semaphore(24)). When null we
        // fall back to a per-file internal Semaphore(segments) for back-compat / unit tests.
        connectionBudget: Semaphore? = null,
        // MULTI-FILE BATCH SCHEDULER (default null = today's per-file behaviour, so the static path
        // and existing unit tests are byte-for-byte unchanged). When a [scheduler] AND [fileId] are
        // provided, the adaptive path uses the BATCH-LEVEL lane assignment (distinct mirrors per
        // concurrent file) instead of this file's own [planLanes]. [liveDemands] supplies the
        // currently-active file demands so the runner can re-consult the scheduler between chunks
        // (rebalancing when a peer file finishes). The scheduler only decides WHICH host each runner
        // pins; the global 24-cap is still enforced by [connectionBudget].
        scheduler: MirrorScheduler? = null,
        fileId: String? = null,
        liveDemands: (() -> List<FileDemand>)? = null,
        // BATCH-WIDE SETUP ELISION (v0.9): the IA identifier + this file's relative path. When the
        // identifier is non-blank, host resolution + ranking + range probing is cached per-item and
        // REUSED across every file of the batch (skipping ~(2N+1) round-trips + N×256 KB of probing
        // per subsequent file). Defaulted to blank/"" so existing callers and all unit tests take the
        // unchanged full-probe path (no caching when the identifier is unknown — the safe fallback).
        identifier: String = "",
        relativeName: String = "",
        // INCREMENTAL MD5 (v0.9): an optional accumulator the ADAPTIVE path feeds on each chunk DONE so
        // the file is hashed (in order) DURING download, eliminating the post-download full-file
        // re-read. Only the adaptive path drives it; the static/single-stream paths ignore it (the
        // caller then runs the authoritative full pass). On a RESUMED download the adaptive path does
        // NOT feed it (pre-seeded chunks are unreported), so digestIfComplete() returns null and the
        // caller falls back to the full pass. Default null -> unchanged for every existing caller/test.
        incrementalMd5: IncrementalMd5Accumulator? = null
    ): Long = withContext(Dispatchers.IO) {
        // The current file's encoded path (same encoding mirrorUrls/buildDownloadUrl use). Used both to
        // synthesise per-host URLs from cached prefixes on a HIT and to derive prefixes on a MISS.
        val encodedPath = if (relativeName.isNotBlank())
            relativeName.split('/').joinToString("/") { encodeSegment(it) } else ""

        // ---- BATCH-WIDE SETUP ELISION: try the per-item cache FIRST. -------------------------------
        // On a HIT we have the item's ranked host PREFIXES already (resolved + speed-ranked + range
        // status), so we synthesise THIS file's per-host URLs by appending its encoded path and SKIP
        // resolveFinalUrl + rankHosts + supportsRange entirely — the big small/medium-batch win.
        val cached = if (identifier.isNotBlank() && encodedPath.isNotBlank())
            hostResolutionCache.get(identifier) else null

        val resolvedHosts: List<String>
        val hosts: List<String>
        val rangeOk: Boolean
        val probeRates: MutableMap<String, Double>?

        if (cached != null) {
            // HIT: rebuild this file's URLs from the cached prefixes (weighted pool order preserved).
            hosts = cached.hostPrefixes.map { it + encodedPath }
            resolvedHosts = cached.distinctPrefixes.map { it + encodedPath }
            // Seed the adaptive EWMA from the cached probe rates, re-keyed onto THIS file's URLs.
            probeRates = if (adaptive) {
                val m = HashMap<String, Double>()
                cached.hostPrefixes.distinct().forEach { prefix ->
                    cached.probeRates[prefix]?.let { r -> m[prefix + encodedPath] = r }
                }
                m
            } else null
            rangeOk = segments > 1 && expectedSize > 0 && hosts.isNotEmpty() && cached.rangeOk
            Log.i("EmuHelper", "segDL: CACHE HIT id=$identifier poolSize=${hosts.size} rangeOk=$rangeOk adaptive=$adaptive")
            if (!rangeOk) {
                Log.i("EmuHelper", "segDL: FALLBACK single-stream (cached)")
                return@withContext singleStreamTo((hosts.firstOrNull() ?: resolvedHosts.first()), expectedSize, destFile, onProgress, isCancelled)
            }
        } else {
            // MISS: do the one-time per-item probes, then POPULATE the cache for the rest of the batch.
            // Resolve each candidate's redirect once to its real node, dedup.
            resolvedHosts = candidateUrls.map { resolveFinalUrl(it) ?: it }.distinct()
            // FIX B: when adaptive, capture the REAL probe rates (bytes/ms) to seed the EWMA in correct
            // units. For the static path probeRates stays null and ranking is byte-for-byte unchanged.
            probeRates = if (adaptive) HashMap() else null
            // Probe + rank: weighted pool favouring the fastest nodes, slow nodes dropped. This 256 KB
            // probe per host ALSO warms the per-mirror h2 sockets (CONNECTION PRE-WARMING folded in —
            // one action, two wins), so chunk 1 skips the TCP+TLS+h2 handshake.
            hosts = rankHosts(resolvedHosts, probeRates).ifEmpty { resolvedHosts }
            // SMALL-FILE FAST PATH (shared by both engines): no range support, single connection,
            // or <= 1 segment / <1 chunk -> stream straight through. Don't rank, don't chunk.
            rangeOk = segments > 1 && expectedSize > 0 && hosts.isNotEmpty() && supportsRange(hosts.first())
            // POPULATE the cache (only when keyable): store prefixes = resolved URLs with this file's
            // encoded path stripped, so later files append their OWN path. range support + speed rank
            // are HOST properties, safe to reuse; the per-chunk Content-Range guard + final MD5 still
            // catch any drift. Skipped entirely when not keyable -> unchanged full-probe behaviour.
            if (identifier.isNotBlank() && encodedPath.isNotBlank()) {
                val pool = hosts.map { it.stripEncodedSuffix(encodedPath) }
                val distinct = resolvedHosts.map { it.stripEncodedSuffix(encodedPath) }
                // Re-key probe rates onto prefixes so a later file can re-key them onto its own URL.
                val ratesByPrefix = HashMap<String, Double>()
                probeRates?.forEach { (url, r) -> ratesByPrefix[url.stripEncodedSuffix(encodedPath)] = r }
                hostResolutionCache.put(identifier, pool, distinct, ratesByPrefix, rangeOk)
            }
            Log.i("EmuHelper", "segDL: size=$expectedSize segs=$segments poolSize=${hosts.size} distinct=${hosts.distinct().size} rangeOk=$rangeOk adaptive=$adaptive")
            if (!rangeOk) {
                Log.i("EmuHelper", "segDL: FALLBACK single-stream")
                return@withContext singleStreamTo((hosts.firstOrNull() ?: resolvedHosts.first()), expectedSize, destFile, onProgress, isCancelled)
            }
        }

        // ====================================================================
        // ADAPTIVE PATH (chunk-queue work-stealing). Wholly additive and gated:
        // only runs when the feature flag is on. The static path below is byte-
        // for-byte the original behaviour and is the zero-risk fallback.
        // ====================================================================
        if (adaptive) {
            // Small file ≈ 1 chunk -> reuse the single-stream fast path (don't chunk/rank).
            if (expectedSize < AdaptiveEngine.CHUNK_SIZE) {
                Log.i("EmuHelper", "segDL: adaptive small-file fast path")
                return@withContext singleStreamTo(hosts.first(), expectedSize, destFile, onProgress, isCancelled)
            }
            return@withContext downloadAdaptive(
                hosts = hosts,
                expectedSize = expectedSize,
                destFile = destFile,
                workerHint = segments,
                connectionBudget = connectionBudget ?: Semaphore(segments.coerceAtLeast(1)),
                probeRates = probeRates ?: emptyMap(),
                onProgress = onProgress,
                isCancelled = isCancelled,
                scheduler = scheduler,
                fileId = fileId,
                liveDemands = liveDemands,
                // BATCH-WIDE SETUP ELISION: when a host dies mid-batch, evict its PREFIX from the
                // cached ranking so subsequent files of this item don't reuse the dead node. Blank
                // identifier / empty path -> no-op (nothing was cached). The prefix is the dead host
                // URL with THIS file's encoded path stripped — exactly the key the cache stores.
                onHostEvicted = { deadHostUrl ->
                    if (identifier.isNotBlank() && encodedPath.isNotBlank()) {
                        hostResolutionCache.evictHost(identifier, deadHostUrl.stripEncodedSuffix(encodedPath))
                    }
                },
                incrementalMd5 = incrementalMd5
            )
        }

        // Distinct fast hosts, fastest-first, for per-segment failover.
        val distinctHosts = hosts.distinct()

        destFile.parentFile?.mkdirs()
        // Pre-size the file so each segment can seek to its offset.
        RandomAccessFile(destFile, "rw").use { it.setLength(expectedSize) }

        val segSize = expectedSize / segments
        val ranges = (0 until segments).map { i ->
            val start = i * segSize
            val end = if (i == segments - 1) expectedSize - 1 else (start + segSize - 1)
            start to end
        }

        val downloadedTotal = java.util.concurrent.atomic.AtomicLong(0)
        val startTime = System.currentTimeMillis()
        var lastReport = startTime
        val reportLock = Any()

        // One coroutine per range. Initial host comes from the weighted pool (fast
        // nodes get more segments); on failure the segment migrates to the next
        // distinct host (fastest-first) so a degrading node can't stall the file.
        coroutineScope {
            ranges.mapIndexed { idx, (start, end) ->
                async {
                    // Allocate the read buffer once per segment, OUTSIDE the retry loop,
                    // so it is reused across attempts rather than re-allocated each time.
                    val buf = ByteArray(256 * 1024)
                    var attempt = 0
                    while (attempt < MAX_ATTEMPTS) {
                        val host = if (attempt == 0) hosts[idx % hosts.size]
                            else distinctHosts[attempt % distinctHosts.size]
                        try {
                            // SESSION-EXPIRY RECOVERY: wrap the ranged GET so a 401 (auth session
                            // expired mid-batch) silently re-authenticates once and retries the SAME
                            // range request, instead of consuming this segment's attempt budget. The
                            // absolute-offset write is idempotent, so a retried range is byte-safe.
                            withReauthRetry {
                            val req = Request.Builder().url(host)
                                .header("User-Agent", USER_AGENT)
                                .header("Accept-Encoding", "identity")
                                .header("Accept", "*/*")
                                .header("Referer", "https://archive.org/")
                                .header("Range", "bytes=$start-$end")
                                .build()
                            okHttpClient.newCall(req).execute().use { resp ->
                                // 401 (or auth-expiry 403) -> trigger silent re-auth+retry, not a
                                // plain transport error that would burn an attempt.
                                if (ReauthCoordinator.isAuthExpiry(resp.code, authExpiryHint = true)) {
                                    throw AuthExpiredException(resp.code)
                                }
                                if (resp.code != 206) throw IOException("No range (HTTP ${resp.code})")
                                // Guard against stale metadata: the file was pre-sized to
                                // expectedSize and segment offsets were sliced from it. If the
                                // server's real total differs, those offsets are wrong and we'd
                                // silently write corrupt data (the caller's >=99% check can still
                                // pass). Content-Range looks like "bytes start-end/total". Only
                                // abort on a CLEAR, parseable mismatch — many servers omit the
                                // header, so absent/unparseable means fall back to old behavior.
                                resp.header("Content-Range")?.let { cr ->
                                    val serverTotal = cr.substringAfterLast('/', "").trim().toLongOrNull()
                                    if (serverTotal != null && serverTotal > 0 && serverTotal != expectedSize) {
                                        throw IOException(
                                            "Server file size changed; expected $expectedSize but server reports $serverTotal"
                                        )
                                    }
                                }
                                val body = resp.body ?: throw IOException("empty body")
                                // Each coroutine opens its own handle and seeks to its slice.
                                RandomAccessFile(destFile, "rw").use { raf ->
                                    raf.seek(start)
                                    body.byteStream().use { input ->
                                        var read: Int
                                        while (input.read(buf).also { read = it } != -1) {
                                            if (isCancelled()) throw CancellationException()
                                            raf.write(buf, 0, read)
                                            val tot = downloadedTotal.addAndGet(read.toLong())
                                            val now = System.currentTimeMillis()
                                            // One coarse aggregate report ~3x/sec.
                                            var doReport = false
                                            synchronized(reportLock) {
                                                if (now - lastReport >= 350) { lastReport = now; doReport = true }
                                            }
                                            if (doReport) {
                                                val secs = (now - startTime).coerceAtLeast(1) / 1000.0
                                                onProgress(tot, tot / secs)
                                            }
                                        }
                                    }
                                }
                            }
                            } // end withReauthRetry (static ranged GET)
                            return@async
                        } catch (c: CancellationException) {
                            throw c
                        } catch (e: Exception) {
                            attempt++
                            if (attempt >= MAX_ATTEMPTS) throw e
                            kotlinx.coroutines.delay(min(1000L * (1L shl (attempt - 1)), 8000L))
                        }
                    }
                }
            }.awaitAll()
        }
        val total = downloadedTotal.get()
        // DIAG static bench: log in the same tag as the adaptive path so one grep compares both.
        // segments == the number of parallel connections used (one coroutine per range).
        val staticWallMs = (System.currentTimeMillis() - startTime).coerceAtLeast(1)
        val staticMb = total.toDouble() / (1024.0 * 1024.0)
        val staticMbps = staticMb / (staticWallMs / 1000.0)
        Log.i(
            "EmuHelper-ADAPTIVE-BENCH",
            "static: ${"%.1f".format(staticMb)}MB in ${"%.1f".format(staticWallMs / 1000.0)}s = " +
                "${"%.2f".format(staticMbps)}MB/s, segments=$segments peakConns=$segments"
        )
        onProgress(total, 0.0)
        total
    }

    /**
     * ADAPTIVE (chunk-queue work-stealing) download path. Only reached when the feature flag
     * is on AND the file is big enough to chunk. Splits [0, expectedSize) into 8 MB chunks held
     * in a [ChunkQueue]; spins up [workerHint] worker coroutines that each loop poll -> pick host
     * -> ranged GET -> write at absolute offset -> markDone, requeuing the whole chunk on
     * failure/stall. Structured concurrency tears down all workers/Calls on cancellation.
     *
     * Correctness contract (identical guarantees to the static path):
     *  - The chunk partition covers [0,size) with no gaps/overlaps; the file is pre-sized.
     *  - A chunk is marked DONE only after its full range is written and the received byte count
     *    matches its length; a short read requeues the WHOLE chunk (idempotent overwrite).
     *  - Every worker acquires the shared [connectionBudget] before opening a socket and releases
     *    it in a finally, so the global 24-connection thermal cap holds for any worker count.
     *  - On exit we assert every chunk is done; a chunk that exhausts its REAL-error budget
     *    (MAX_ERROR_ATTEMPTS) enters the queue's terminal FAILED state and the download throws
     *    (Fix D). Stalls (soft/hard) never fail the download — they only migrate the chunk.
     */
    private suspend fun downloadAdaptive(
        hosts: List<String>,
        expectedSize: Long,
        destFile: File,
        workerHint: Int,
        connectionBudget: Semaphore,
        // v3: formerly the EWMA seed source for the (now-retired) per-chunk HostScoreboard. The
        // laned engine pins hosts and does NOT score per chunk, so this is unused — kept only so the
        // static-path call site needn't change. Suppress the unused warning.
        @Suppress("UNUSED_PARAMETER") probeRates: Map<String, Double>,
        onProgress: suspend (Long, Double) -> Unit,
        isCancelled: () -> Boolean,
        // MULTI-FILE BATCH SCHEDULER (default null = single-file per-file planLanes, unchanged). When
        // present alongside [fileId] and [liveDemands], the lane topology comes from the batch-level
        // [MirrorScheduler.assign] (distinct mirrors per concurrent file) and is RE-CONSULTED between
        // chunks so a freed mirror (peer file finished) flows to this file. The scheduler only picks
        // WHICH host each runner pins; the 24-cap stays enforced by [connectionBudget].
        scheduler: MirrorScheduler? = null,
        fileId: String? = null,
        liveDemands: (() -> List<FileDemand>)? = null,
        // BATCH-WIDE SETUP ELISION (v0.9): invoked with the FULL host URL when a lane is declared dead
        // (its error streak exhausted — a mirror went down mid-download), so the caller can evict that
        // host from the per-item resolution cache and stop subsequent batch files reusing it. NO-OP by
        // default so existing callers / unit tests are unaffected.
        onHostEvicted: (deadHostUrl: String) -> Unit = {},
        // INCREMENTAL MD5 (v0.9): fed on each chunk DONE (in order, exactly once per chunk) so the
        // file is hashed DURING download. NOT fed on a RESUMED download (pre-seeded chunks are
        // unreported), so the accumulator can never claim a partial digest as complete. Default null.
        incrementalMd5: IncrementalMd5Accumulator? = null
    ): Long {
        val distinctHosts = hosts.distinct()
        destFile.parentFile?.mkdirs()

        val chunks = partitionChunks(expectedSize)

        // ---- PARTIAL-BYTE RESUME (v0.8) ----------------------------------------------------------
        // BEFORE pre-sizing, try to resume from a persisted manifest sitting next to the .part. The
        // manifest records which chunk indices a PRIOR interrupted run finished, plus the file's
        // expectedSize+chunkSize so we can validate it. We trust it ONLY when the recorded
        // size/chunking match AND the existing .part is already at least the full pre-sized length
        // (so seek-writes into it are valid). On ANY mismatch we discard it and start fresh — the
        // safe fallback. The end-of-download MD5 verify (in DownloadManager) is the final correctness
        // gate regardless, so a bad resume can NEVER ship a corrupt file.
        val manifestFile = File(destFile.parentFile, destFile.name + MANIFEST_SUFFIX)
        val existingPartLength = if (destFile.exists()) destFile.length() else 0L
        val resumedDone: Set<Int> = run {
            val raw = runCatching { if (manifestFile.exists()) manifestFile.readText() else null }
                .getOrNull()
            val validated = ResumeManifest.parseAndValidate(
                raw = raw,
                expectedSize = expectedSize,
                chunkSize = AdaptiveEngine.CHUNK_SIZE,
                partLength = existingPartLength
            )
            if (validated != null && validated.isNotEmpty()) {
                Log.i(
                    "EmuHelper",
                    "resume: ${validated.size}/${chunks.size} chunks already done for ${destFile.name}"
                )
                validated
            } else {
                // No trustworthy manifest -> start fresh; remove any stale manifest so we don't keep
                // re-reading a mismatched one.
                if (validated == null && manifestFile.exists()) runCatching { manifestFile.delete() }
                emptySet()
            }
        }

        // INCREMENTAL MD5 (v0.9): only feed the accumulator on a FRESH (non-resumed) download. A
        // resumed download's pre-seeded done chunks are never reported to it, so feeding it would leave
        // a permanent gap and it would (correctly) never claim completeness — but nulling it here makes
        // that explicit and avoids any wasted reads. With this null, digestIfComplete() stays null on
        // resume and DownloadManager runs the authoritative full pass. (decideMd5Strategy also gates
        // this at the call site — belt and braces.)
        val liveMd5: IncrementalMd5Accumulator? = if (resumedDone.isEmpty()) incrementalMd5 else null

        // Pre-size the .part so each worker can seek to any absolute offset (kept from static path).
        // setLength to expectedSize is a no-op when the .part is already full (the resume case), and
        // creates/extends it on a fresh download — so this is safe for both paths.
        RandomAccessFile(destFile, "rw").use { it.setLength(expectedSize) }

        // Construct the queue, pre-seeding the resumed done-set so ONLY the missing chunks are
        // fetched. A fresh download passes emptySet -> every chunk is queued, unchanged behaviour.
        val queue = ChunkQueue(chunks, preDone = resumedDone)

        // Persist the done-set to the manifest. Called on chunk completion (THROTTLED — at most once
        // per ~750ms via [lastManifestSaveMs]) and once at the very end, never per byte, so it's
        // cheap. Writes the compact single-line manifest atomically-ish (write to a temp then rename)
        // so an app-kill mid-write can't leave a half-written manifest.
        val lastManifestSaveMs = java.util.concurrent.atomic.AtomicLong(0L)
        val saveManifest: (force: Boolean) -> Unit = { force ->
            val now = System.currentTimeMillis()
            val prev = lastManifestSaveMs.get()
            if (force || now - prev >= MANIFEST_SAVE_INTERVAL_MS) {
                if (force || lastManifestSaveMs.compareAndSet(prev, now)) {
                    runCatching {
                        val line = ResumeManifest.serialize(
                            expectedSize, AdaptiveEngine.CHUNK_SIZE, queue.doneIndices()
                        )
                        val tmp = File(manifestFile.parentFile, manifestFile.name + ".tmp")
                        tmp.writeText(line)
                        // Rename over the real manifest (best-effort atomic). Fall back to a direct
                        // write if rename fails (e.g. odd filesystem) so we still persist progress.
                        if (!tmp.renameTo(manifestFile)) {
                            manifestFile.writeText(line)
                            runCatching { tmp.delete() }
                        }
                    }
                }
            }
        }

        // v3 RETIRED the per-chunk HostScoreboard.pickHost() re-pick. The laned engine PINS each
        // runner to one host (see below), so there is no per-chunk host selection to score: warm h2
        // reuse comes from the pin, not from a live scoreboard. Host failover is handled coarsely by
        // the liveHosts set (a dead lane is dropped), not by per-chunk EWMA weighting. [probeRates]
        // (the old EWMA seed source) is therefore unused now — kept in the signature only so the
        // static-path call site needn't change.

        // The CURRENT, decaying soft-stall baseline. A ring buffer of the last N
        // completed-chunk throughputs across ALL hosts. The soft-stall decision compares a chunk's
        // sustained rate against the MEDIAN of these real recent rates (not a never-decaying peak),
        // and is DISABLED until enough samples exist (the min-sample guardrail).
        val recentRates = RecentRateWindow()

        // Progress: confirmedBytes counts only fully-completed chunks (monotonic — a requeue can
        // never make it go backwards or over-count). It is what we REPORT, keeping the progress
        // bar monotonic. A separate liveBytes feeds the windowed speed number only.
        //
        // RESUME: pre-seed confirmedBytes with the bytes of the chunks already done from the manifest
        // so the progress bar starts where the prior run left off (not at 0). The final completion
        // check asserts confirmedBytes == expectedSize, and every done chunk's length is counted
        // exactly once (resumed-done here + freshly-completed below = all chunks), so the invariant
        // still holds exactly.
        val resumedBytes = resumedDone.sumOf { chunks[it].length }
        val confirmedBytes = java.util.concurrent.atomic.AtomicLong(resumedBytes)
        val liveBytes = java.util.concurrent.atomic.AtomicLong(0)
        val startTime = System.currentTimeMillis()
        var lastReport = startTime
        val reportLock = Any()

        // v3 LANED TOPOLOGY. The durable resource is a small set of WARM, host-PINNED stream
        // runners — NOT a big over-provisioned pool that re-picks the host per chunk. [planLanes]
        // assigns ~2 streams per mirror (the measured sweet spot), spread across the (2-3)
        // independent IA datacenters, never exceeding ~4/host (past that IA sheds load) and never
        // exceeding the global budget. Each resulting runner PINS one host for the file's whole
        // duration, so OkHttp reuses the SAME warm h2 connection for every chunk that runner pulls
        // (the connection pool keep-alive — see AppModule's widened ConnectionPool — outlives the
        // brief gaps between chunks). That reuse is what kills the per-chunk slow-start tax the
        // v1/v2 per-chunk-host-repick engine paid (measured 8x median-vs-peak gap).
        //
        // workerHint (segmentsPerFile) is a FLOOR for the budget the file may use, but the actual
        // permit cap is still the shared connectionBudget Semaphore (24): every runner AND every
        // racer acquire()s a permit before opening a socket and releases it in a finally, so the
        // global cap holds regardless of how many runners exist. We size the lane plan to the
        // smaller of (the shared budget ceiling) and (chunk count) so we never spawn more runners
        // than there is work for; the runner FLOOR keeps small files responsive.
        val laneBudget = workerHint
            .coerceAtLeast(1)
            .coerceAtLeast(minOf(chunks.size, AdaptiveEngine.MAX_ADAPTIVE_WORKERS))
            .coerceIn(1, chunks.size)

        // MULTI-FILE BATCH SCHEDULER seam. When a [scheduler] is wired (multi-file batch), the lane
        // topology for THIS file comes from the batch-level assignment ([MirrorScheduler.assign]) so
        // concurrent files pin DISTINCT mirrors (file A on datacenter-6, file B on datacenter-8) —
        // each at its full ~2 MB/s instead of splitting one mirror. When the scheduler is null (the
        // single-file path and all unit tests), this is byte-identical to the old planLanes call.
        //
        // [planLanesNow] is RE-QUERYABLE: runners call it between chunks so that when a peer file
        // finishes, the freed mirror flows back to this file on the next chunk (the scheduler returns
        // a wider plan once fewer files are active). It's a pure read of the scheduler + the live
        // demand snapshot — no permits minted; the 24-cap is still the Semaphore's job.
        val planLanesNow: () -> List<Lane> = {
            val sched = scheduler
            val fid = fileId
            val demandsSupplier = liveDemands
            if (sched != null && fid != null && demandsSupplier != null) {
                val demands = demandsSupplier()
                // If we're the only active file (peers finished), fall back to the full single-file
                // plan over all our mirrors (the scheduler does this internally too, but guard here).
                val plan = sched.assign(demands).firstOrNull { it.fileId == fid }?.lanes
                if (plan.isNullOrEmpty()) planLanes(distinctHosts, laneBudget) else plan
            } else {
                planLanes(distinctHosts, laneBudget)
            }
        }

        // Plan the warm-lane topology: one Lane per host with its streams-per-host count.
        val lanes = planLanesNow().ifEmpty {
            // Degenerate fallback (no usable host) — single lane on whatever host we have.
            listOf(Lane(distinctHosts.first(), 1))
        }
        // Flatten lanes into one PINNED-HOST runner per stream. Each runner carries the host it is
        // pinned to; it never re-picks per chunk (warm h2 reuse). Multiple runners can pin the same
        // host (e.g. 2 streams/host).
        val laneRunners: List<String> = lanes.flatMap { lane -> List(lane.streams) { lane.host } }
        val workerCount = laneRunners.size
        // Live mirror set for failover: a host that exhausts its lane error budget is removed here
        // so its pinned runners re-target to a SURVIVING host on their next chunk (redistributing
        // the dead lane's stream budget to survivors without exceeding the global cap — the
        // Semaphore is unchanged). Seeded with every planned host.
        val liveHosts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply {
            addAll(distinctHosts)
        }
        // Per-host consecutive-error tally for lane death (mirror dies mid-download). A host that
        // racks up this many real transport errors across its lane is declared dead and removed
        // from [liveHosts]; its in-flight chunks are already requeued by the normal error path, so
        // survivors finish them. Reset on any success on that host.
        val hostErrorStreak = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
        // A host is declared dead after this many CONSECUTIVE real errors (no intervening success).
        // Generous so a single transient blip never kills a mirror, but a genuinely-down datacenter
        // is dropped before it wastes the whole error budget.
        val laneDeathThreshold = AdaptiveEngine.MAX_ERROR_ATTEMPTS
        // Pick a still-live host for a runner whose pinned host died, or fall back to the pinned host
        // if somehow none survive (the queue's error budget then governs final failure). Prefer a
        // host DIFFERENT from [avoid] so a racer spreads across datacenters.
        val pickLiveHost: (pinned: String, avoid: String?) -> String = { pinned, avoid ->
            if (pinned in liveHosts && pinned != avoid) {
                pinned
            } else {
                val live = liveHosts.toList()
                live.firstOrNull { it != avoid }
                    ?: live.firstOrNull()
                    ?: pinned
            }
        }
        // BATCH-SCHEDULER RE-TARGET. Decide the host a primary runner should pull its NEXT chunk
        // from, consulting the live scheduler plan between chunks. If the runner's [pinnedHost] is
        // still both ASSIGNED to this file by the scheduler AND live, keep it (warm h2 reuse). Else
        // re-pin to a host that is currently assigned-to-this-file AND live (so a peer file finishing
        // hands this file the freed mirror), falling back to the plain live-host failover when the
        // scheduler offers nothing usable. When no scheduler is wired this is exactly
        // pickLiveHost(pinnedHost, null) — the single-file path is unchanged.
        val effectiveHostFor: (pinnedHost: String) -> String = { pinned ->
            if (scheduler == null) {
                pickLiveHost(pinned, null)
            } else {
                val assigned = planLanesNow().map { it.host }
                when {
                    pinned in assigned && pinned in liveHosts -> pinned
                    else -> assigned.firstOrNull { it in liveHosts }
                        ?: pickLiveHost(pinned, null)
                }
            }
        }
        // Record the outcome of a chunk attempt on [host] for lane-failover bookkeeping. A success
        // resets the streak and re-confirms the host live; a real error bumps the streak and, past
        // the threshold, declares the lane dead (removed from liveHosts) so its runners re-target.
        val onHostOutcome: (host: String, ok: Boolean) -> Unit = { host, ok ->
            if (ok) {
                hostErrorStreak[host]?.set(0)
                liveHosts.add(host)
            } else {
                val streak = hostErrorStreak.computeIfAbsent(host) {
                    java.util.concurrent.atomic.AtomicInteger(0)
                }.incrementAndGet()
                // Only kill a lane while at least one OTHER host survives — never strand the file.
                if (streak >= laneDeathThreshold && liveHosts.size > 1) {
                    liveHosts.remove(host)
                    // BATCH-WIDE SETUP ELISION: drop this dead host from the per-item resolution cache
                    // so subsequent files in the batch don't reuse a node that just died mid-download.
                    onHostEvicted(host)
                    Log.w("EmuHelper", "lane died: $host (streak=$streak) — redistributing to survivors")
                }
            }
        }

        // v2 BENCH stats (cheap, aggregated at completion — no per-chunk spam). Concurrent permit
        // tracking lets us log the PEAK simultaneous connections actually used; race counters let
        // the maintainer see whether tail-racing fired and won on a real IA download.
        val liveConns = java.util.concurrent.atomic.AtomicInteger(0)
        val peakConns = java.util.concurrent.atomic.AtomicInteger(0)
        val racesStarted = java.util.concurrent.atomic.AtomicInteger(0)
        val racesWon = java.util.concurrent.atomic.AtomicInteger(0)
        val onConnAcquired: () -> Unit = {
            val n = liveConns.incrementAndGet()
            // Lock-free monotonic max.
            var peak = peakConns.get()
            while (n > peak && !peakConns.compareAndSet(peak, n)) peak = peakConns.get()
        }
        val onConnReleased: () -> Unit = { liveConns.decrementAndGet() }

        // ---- DIAGNOSTIC BENCH COLLECTORS (instrumentation-only, zero behavior change) ----
        // (1) Protocol collector: counts how many chunks were served over each HTTP protocol
        //     (e.g. "h2", "http/1.1"). Many workers write concurrently -> ConcurrentHashMap.
        val protocolCounts = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

        // (2) Per-host byte+ms accumulators for MB/s breakdown. Value is a 2-element LongArray
        //     [totalBytes, totalMs]. We use a ConcurrentHashMap for the outer map (concurrent
        //     insertions from multiple workers) and AtomicLong pairs for each host's counters
        //     so workers accumulate without locking each other. Each host entry is a 2-element
        //     AtomicLongArray: [0]=totalBytes [1]=totalMs.
        val hostStats = java.util.concurrent.ConcurrentHashMap<String,
            Array<java.util.concurrent.atomic.AtomicLong>>()

        val onChunkCompleted: (host: String, bytes: Long, elapsedMs: Long, protocol: String) -> Unit =
            { host, bytes, elapsedMs, protocol ->
                // Protocol counts (answers: H2 vs HTTP/1.1?).
                // computeIfAbsent is the atomic ConcurrentHashMap primitive — safe under concurrent
                // writes from many workers; Kotlin's getOrPut extension is NOT atomic here.
                protocolCounts.computeIfAbsent(protocol) {
                    java.util.concurrent.atomic.AtomicInteger(0)
                }.incrementAndGet()
                // Per-host accumulator (answers A: per-mirror ceiling?).
                // computeIfAbsent ensures each host's AtomicLong pair is created exactly once.
                val entry = hostStats.computeIfAbsent(host) {
                    arrayOf(
                        java.util.concurrent.atomic.AtomicLong(0L),
                        java.util.concurrent.atomic.AtomicLong(0L)
                    )
                }
                entry[0].addAndGet(bytes)
                entry[1].addAndGet(elapsedMs)
            }

        val onReport: suspend () -> Unit = {
            // ~3x/sec aggregate report, identical shape & cadence to the static path.
            val now = System.currentTimeMillis()
            var doReport = false
            synchronized(reportLock) {
                if (now - lastReport >= 350) { lastReport = now; doReport = true }
            }
            if (doReport) {
                val secs = (now - startTime).coerceAtLeast(1) / 1000.0
                // Report the MONOTONIC confirmedBytes total; speed from live bytes.
                onProgress(confirmedBytes.get(), liveBytes.get() / secs)
            }
        }

        coroutineScope {
            laneRunners.mapIndexed { runnerIdx, pinnedHost ->
                async {
                    val buf = ByteArray(256 * 1024)
                    // Each runner is PINNED to one host for the file's whole duration: it pulls
                    // every chunk from `pinnedHost`, so OkHttp serves all of them off the SAME warm
                    // h2 connection (no per-chunk re-pick, no slow-start tax). It loops, stealing
                    // chunks until the queue is drained AND nothing is in flight (so a requeued
                    // chunk from a stalled/failed peer lane is still picked up).
                    while (queue.shouldKeepWorking()) {
                        if (isCancelled()) throw CancellationException()
                        val chunk = queue.poll()
                        if (chunk == null) {
                            // No fresh chunk to start. v2/v3: in the TAIL (queue drained, only a few
                            // chunks still in-flight) an idle runner RACES a slow in-flight chunk on
                            // a SECOND, DIFFERENT host instead of busy-waiting — first to finish the
                            // range wins, the loser bails (see downloadChunkAttempt's done-check).
                            // pickRaceTarget returns null unless the strict tail-gate holds, so this
                            // never fires early or on small files. The racer spreads across
                            // datacenters: it pins a live host different from this runner's own host.
                            val raceChunk = queue.pickRaceTarget()
                            if (raceChunk != null) {
                                val raceHost = pickLiveHost(pinnedHost, pinnedHost)
                                racesStarted.incrementAndGet()
                                downloadChunkAttempt(
                                    chunk = raceChunk,
                                    pinnedHost = raceHost,
                                    queue = queue,
                                    recentRates = recentRates,
                                    destFile = destFile,
                                    expectedSize = expectedSize,
                                    buf = buf,
                                    connectionBudget = connectionBudget,
                                    confirmedBytes = confirmedBytes,
                                    liveBytes = liveBytes,
                                    isCancelled = isCancelled,
                                    onReport = onReport,
                                    isRace = true,
                                    onConnAcquired = onConnAcquired,
                                    onConnReleased = onConnReleased,
                                    onRaceWon = { racesWon.incrementAndGet() },
                                    onChunkCompleted = onChunkCompleted,
                                    onHostOutcome = onHostOutcome,
                                    onChunkDone = { saveManifest(false) },
                                    incrementalMd5 = liveMd5
                                )
                            } else {
                                // Nothing to claim right now but peers hold in-flight chunks that
                                // may be requeued; yield briefly rather than busy-spinning.
                                kotlinx.coroutines.delay(25)
                            }
                            continue
                        }
                        // FAILOVER + BATCH RE-TARGET: if this runner's pinned host has been declared
                        // dead (its lane exhausted its error budget — a mirror went down mid-download)
                        // OR the batch scheduler has re-assigned mirrors (a peer file finished and
                        // freed a datacenter), re-target to the right surviving host for this chunk.
                        // This redistributes stream budget onto the correct live mirrors WITHOUT
                        // exceeding the global cap (the shared Semaphore is unchanged). While the
                        // pinned host stays assigned + live this is a no-op and the runner keeps
                        // reusing its own warm connection.
                        val effectiveHost = effectiveHostFor(pinnedHost)
                        downloadChunkAttempt(
                            chunk = chunk,
                            pinnedHost = effectiveHost,
                            queue = queue,
                            recentRates = recentRates,
                            destFile = destFile,
                            expectedSize = expectedSize,
                            buf = buf,
                            connectionBudget = connectionBudget,
                            confirmedBytes = confirmedBytes,
                            liveBytes = liveBytes,
                            isCancelled = isCancelled,
                            onReport = onReport,
                            isRace = false,
                            onConnAcquired = onConnAcquired,
                            onConnReleased = onConnReleased,
                            onRaceWon = { /* primary path is never a race win */ },
                            onChunkCompleted = onChunkCompleted,
                            onHostOutcome = onHostOutcome,
                            onChunkDone = { saveManifest(false) },
                            incrementalMd5 = liveMd5
                        )
                    }
                    @Suppress("UNUSED_EXPRESSION") runnerIdx  // keep index in scope for clarity/debug
                }
            }.awaitAll()
        }

        // COMPLETION GATE (Fix D — self-healing, definite terminal conditions):
        // Workers exit only when no chunk is queued and none is in flight, so by here every chunk
        // is in a TERMINAL state: done or failed. The download fails iff any chunk hit the terminal
        // FAILED state (exhausted its real-error budget) — a definite condition, not a race on a
        // thrown exception. Otherwise every chunk is done and we confirm the byte total.
        if (queue.anyFailed()) {
            throw IOException(
                "Adaptive download failed: ${queue.failedIndices().size} chunk(s) exhausted the " +
                    "error budget (indices ${queue.failedIndices().sorted()})"
            )
        }
        if (!queue.allDone()) {
            // Should be impossible given the worker exit condition, but keep the hard guard.
            throw IOException("Adaptive download incomplete: ${queue.remaining()} chunk(s) unfinished")
        }
        val total = confirmedBytes.get()
        // Sanity: confirmedBytes must equal expectedSize on success (sum of all chunk lengths ==
        // expectedSize), so the caller's `total >= expected*99/100` completion check passes.
        if (total != expectedSize) {
            throw IOException("Adaptive download size mismatch: confirmed $total != expected $expectedSize")
        }
        // RESUME: the file is fully assembled — the manifest has done its job. Delete it so a later
        // copy/extract failure or a re-run doesn't trip over a stale "all done" manifest. The .part
        // is the source of truth from here; the MD5 verify in DownloadManager is the final gate.
        runCatching { if (manifestFile.exists()) manifestFile.delete() }
        // v2 HONEST BENCH LOG (aggregate, once at completion — cheap, no per-chunk spam). Lets the
        // maintainer compare adaptive-on vs adaptive-off MB/s on a real device from logcat, and see
        // whether over-provisioning actually opened more connections and whether tail-racing fired
        // and won. Tag is grep-friendly. wall-clock includes warm-up so it's the user-visible time.
        val wallMs = (System.currentTimeMillis() - startTime).coerceAtLeast(1)
        val mb = total.toDouble() / (1024.0 * 1024.0)
        val mbps = mb / (wallMs / 1000.0)

        // DIAG (1): build protocol summary string, e.g. "h2×12 http/1.1×3"
        val protocolSummary = if (protocolCounts.isEmpty()) "unknown"
        else protocolCounts.entries
            .sortedByDescending { it.value.get() }
            .joinToString(" ") { "${it.key}×${it.value.get()}" }

        // v3: surface the lane plan (host=streams …) so the maintainer can confirm spread-across-
        // mirrors at a glance, e.g. "lanes=[ia6=2 ia8=2]". peakConns should be SMALL (~Σ streams,
        // ~4) — proof warm reuse is working and we're NOT opening a fresh socket per chunk.
        val laneSummary = lanes.joinToString(" ") { l ->
            "${l.host.hostLabel()}=${l.streams}"
        }
        Log.i(
            "EmuHelper-ADAPTIVE-BENCH",
            "adaptive: ${"%.1f".format(mb)}MB in ${"%.1f".format(wallMs / 1000.0)}s = " +
                "${"%.2f".format(mbps)}MB/s, lanes=[$laneSummary] streams=$workerCount " +
                "peakConns=${peakConns.get()} " +
                "races=${racesStarted.get()}/${racesWon.get()} (started/won) " +
                "protocols=[$protocolSummary]"
        )

        // DIAG (2): per-host MB/s breakdown — answers: are all mirrors at a similar ceiling?
        // Format: "host1=X.XMB/s(Y.YMB) host2=... distinctHosts=K"
        if (hostStats.isNotEmpty()) {
            val perHostStr = hostStats.entries
                .sortedByDescending { it.value[0].get() }
                .joinToString(" ") { (host, counters) ->
                    val hostBytes = counters[0].get()
                    val hostMs = counters[1].get().coerceAtLeast(1)
                    val hostMb = hostBytes / (1024.0 * 1024.0)
                    val hostMbps = hostMb / (hostMs / 1000.0)
                    val label = host.substringAfter("//").substringBefore('/')
                    "$label=${"%.2f".format(hostMbps)}MB/s(${"%.1f".format(hostMb)}MB)"
                }
            Log.i(
                "EmuHelper-ADAPTIVE-BENCH",
                "per-host: $perHostStr distinctHosts=${hostStats.size}"
            )
        }

        // DIAG (3): per-chunk rate median vs peak — answers: is slow-start hurting each chunk?
        // Uses the RecentRateWindow ring buffer that already records every completed chunk's rate.
        val chunkSamples = recentRates.snapshotSamples()
        if (chunkSamples.isNotEmpty()) {
            val sorted = chunkSamples.copyOf().also { it.sort() }
            val medianBytesPerMs = if (sorted.size % 2 == 1) sorted[sorted.size / 2]
                else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
            val peakBytesPerMs = sorted.last()
            val medianMbps = medianBytesPerMs * 1000.0 / (1024.0 * 1024.0)
            val peakMbps = peakBytesPerMs * 1000.0 / (1024.0 * 1024.0)
            Log.i(
                "EmuHelper-ADAPTIVE-BENCH",
                "per-chunk: medianMB/s=${"%.2f".format(medianMbps)} " +
                    "peakMB/s=${"%.2f".format(peakMbps)} " +
                    "samples=${sorted.size} " +
                    "(median<<peak => slow-start signature per chunk)"
            )
        }
        onProgress(total, 0.0)
        return total
    }

    /**
     * Download ONE chunk attempt: pick a host, ranged GET, write at absolute offset, verify the
     * full byte count, and mark the chunk DONE. The work-stealing model is "requeue whole on
     * stall": a single failed attempt does NOT loop here — instead the WHOLE chunk is handed back
     * to the queue so ANY worker can re-claim it, and this returns normally so the current worker
     * immediately pulls its next chunk.
     *
     * BUDGET SEPARATION (Fix A): a STALL (soft or hard) is NOT an error. Stalls go through
     * [ChunkQueue.requeueStall] (own large budget) and can NEVER fail the download — they just
     * migrate the chunk to a different worker/host. Only REAL transport errors (non-206, short
     * read, IOException, timeout) go through [ChunkQueue.recordErrorOrFail], which consumes the
     * per-chunk error budget ([AdaptiveEngine.MAX_ERROR_ATTEMPTS]) and, when exhausted, moves the
     * chunk to the terminal FAILED state and rethrows so the whole download fails.
     *
     * SELF-HEALING (Fix D): a `reconciled` guard ensures the polled chunk is reconciled EXACTLY
     * ONCE on every exit path. If we somehow leave without having marked it done or requeued it,
     * the finally force-requeues it so it can never be orphaned (which would leak an in-flight slot
     * and wedge the workers forever).
     *
     * Correctness: writes are idempotent absolute-offset overwrites, so a requeued chunk simply
     * overwrites the same range. The chunk is marked DONE only after the received byte count
     * equals the chunk length exactly (a short read requeues — never a partial done).
     */
    private suspend fun downloadChunkAttempt(
        chunk: Chunk,
        // v3 LANED: the host this attempt is PINNED to. For a primary it is the runner's lane host
        // (re-targeted to a survivor if the lane died); for a racer it is a live host DIFFERENT from
        // the primary's. Because it is the SAME host for every chunk a runner pulls, OkHttp reuses
        // the runner's warm h2 connection — no per-chunk re-pick, no slow-start tax. There is NO
        // HostScoreboard anymore: warm reuse comes from the pin, failover from the liveHosts set.
        pinnedHost: String,
        queue: ChunkQueue,
        recentRates: RecentRateWindow,
        destFile: File,
        expectedSize: Long,
        buf: ByteArray,
        connectionBudget: Semaphore,
        confirmedBytes: java.util.concurrent.atomic.AtomicLong,
        liveBytes: java.util.concurrent.atomic.AtomicLong,
        isCancelled: () -> Boolean,
        onReport: suspend () -> Unit,
        // v2 TAIL-RACING: when true this is a RACER on an already-in-flight tail chunk, not the
        // primary owner. A racer (a) acquires the global permit non-blockingly (tryAcquire — never
        // block a permit a fresh chunk could use; bails if none free), (b) checks queue.isDone
        // before every write and bails the instant the primary (or another racer) finishes the
        // range, (c) NEVER touches the error/stall budgets or requeues/force-requeues the chunk —
        // it is purely additive, so a racer failure can never fail the download (the primary still
        // owns the chunk), and (d) pairs pickRaceTarget with endRace in finally. Race correctness:
        // both the primary and the racer write the SAME absolute [start,end] range with IDENTICAL
        // server bytes, so last-write-wins is byte-safe and whoever markDone()s first wins.
        isRace: Boolean = false,
        // v2 bench hooks: count a connection permit while held (for peak-connections), fired only
        // once the permit is actually acquired and once on release.
        onConnAcquired: () -> Unit = {},
        onConnReleased: () -> Unit = {},
        // v2: invoked iff THIS attempt was a race that WON (its markDone transitioned the chunk).
        onRaceWon: () -> Unit = {},
        // DIAG bench: called once per successfully-completed chunk (primary or winning racer)
        // to accumulate per-host bytes+ms and HTTP protocol stats. NO-OP by default so all
        // existing callers (unit tests) are unaffected. Thread-safe: lambda body uses
        // ConcurrentHashMap + AtomicLong — no additional locking needed here.
        onChunkCompleted: (host: String, bytes: Long, elapsedMs: Long, protocol: String) -> Unit = { _, _, _, _ -> },
        // v3 LANE FAILOVER: report this attempt's outcome on [pinnedHost] (true=success, false=real
        // error) so the caller can declare a dead lane and redistribute. NO-OP for primaries that
        // don't care (and for racers, which never affect lane health). Stalls do NOT call this — a
        // stall is not a lane-death signal, only a migration.
        onHostOutcome: (host: String, ok: Boolean) -> Unit = { _, _ -> },
        // RESUME (v0.8): invoked exactly once when THIS attempt's markDone transitioned the chunk to
        // DONE, so the caller can persist the (throttled) resume manifest. NO-OP by default so unit
        // tests are unaffected.
        onChunkDone: () -> Unit = {},
        // INCREMENTAL MD5 (v0.9): fed with this chunk's index inside the SAME markDone-winner block as
        // [onChunkDone], so it is called EXACTLY ONCE per chunk (the markDone winner) and never by a
        // losing racer — no double-feed under racing. The accumulator reads the chunk's bytes from the
        // committed .part (already fully written before markDone) and only folds contiguous prefixes.
        // Default null -> no-op for unit tests / the resume path.
        incrementalMd5: IncrementalMd5Accumulator? = null
    ) {
        if (isCancelled()) throw CancellationException()
        // v2 racer: if the chunk already finished between pickRaceTarget and here, there's nothing
        // to race — release the racer slot and bail (no permit acquired, no budget touched).
        if (isRace && queue.isDone(chunk.index)) {
            queue.endRace(chunk)
            return
        }
        // v3: the host is PINNED (passed in), not re-picked per chunk. This is the single change
        // that makes OkHttp reuse the warm h2 connection for every chunk on this runner's lane.
        val host = pinnedHost
        var hostReleased = false  // retained for symmetry with the finally guard below (no-op now)
        // Fix D: set true once the chunk is reconciled (markDone OR requeued/failed). If still
        // false in the finally, the chunk leaked in-flight and is force-requeued. A RACER never
        // owns reconciliation (the primary does), so it starts and stays "reconciled" — its finally
        // must never force-requeue a chunk the primary still owns.
        var reconciled = isRace
        var call: okhttp3.Call? = null
        var connAcquired = false
        var bytesThisChunk = 0L
        // DIAG: HTTP protocol observed on THIS chunk's response (captured inside the use{} block,
        // read after it closes). Empty string = no response received yet (stall/error before 206).
        var chunkProtocol = ""
        val chunkStart = System.currentTimeMillis()
        var lastReadAt = chunkStart
        // Soft-stall sustain counter: how many consecutive measurement windows this chunk has read
        // below the baseline bar. Resets to 0 whenever it reads at/above the bar (Fix B — sustained).
        var consecutiveSlowWindows = 0
        var lastSoftCheck = chunkStart
        // Acquire a GLOBAL connection slot BEFORE opening the socket; release in finally. This is
        // what makes the 24-connection thermal cap robust to any (variable) runner count. A RACER
        // uses tryAcquire so it never blocks a permit a fresh chunk could use and never pushes the
        // total over the cap; if no permit is free it abandons the race immediately.
        if (isRace) {
            if (!connectionBudget.tryAcquire()) {
                // No free permit — don't block. The primary still owns the chunk; just give up the
                // race.
                queue.endRace(chunk)
                return
            }
        } else {
            connectionBudget.acquire()
        }
        connAcquired = true
        onConnAcquired()
        try {
            // SESSION-EXPIRY RECOVERY: wrap the ranged GET so a 401 (the IA datanode rejecting a stale
            // session mid-batch) triggers a SILENT single-flight re-auth + ONE retry of this chunk's
            // GET, instead of consuming the chunk's real-error budget. A 401 is rejected at the
            // response-status check BEFORE any body bytes are read, so a retry always restarts this
            // chunk cleanly from zero bytes — bytesThisChunk is reset on each (re)attempt below so a
            // re-auth retry can never double-count, and liveBytes only ever grows on real 206 reads.
            // The re-auth login is a separate permit-free POST, so the 24-cap is untouched while this
            // chunk's permit is held. If re-auth has no saved creds or fails, withReauthRetry rethrows
            // a clear IOException which falls through to the normal error-budget catch below (a genuine
            // failure that KEEPS the .part for resume).
            withReauthRetry {
            bytesThisChunk = 0L
            val req = Request.Builder().url(host)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Encoding", "identity")
                .header("Accept", "*/*")
                .header("Referer", "https://archive.org/")
                .header("Range", "bytes=${chunk.start}-${chunk.end}")
                .build()
            call = okHttpClient.newCall(req)
            call.execute().use { resp ->
                // 401 (or auth-expiry 403) -> silent re-auth+retry; NOT a real transport error.
                if (ReauthCoordinator.isAuthExpiry(resp.code, authExpiryHint = true)) {
                    throw AuthExpiredException(resp.code)
                }
                if (resp.code != 206) throw IOException("No range (HTTP ${resp.code})")
                // DIAG: capture the negotiated HTTP protocol (h2, http/1.1, etc.) for the bench log.
                // resp.protocol is the okhttp3.Protocol enum; toString() gives the canonical lowercase name.
                chunkProtocol = resp.protocol.toString()
                // Guard against stale metadata: if the server's real total differs from the size
                // we pre-sized/partitioned against, our offsets are wrong — abort. Same
                // CLEAR-mismatch-only policy as the static path (absent header => proceed).
                resp.header("Content-Range")?.let { cr ->
                    val serverTotal = cr.substringAfterLast('/', "").trim().toLongOrNull()
                    if (serverTotal != null && serverTotal > 0 && serverTotal != expectedSize) {
                        throw IOException(
                            "Server file size changed; expected $expectedSize but server reports $serverTotal"
                        )
                    }
                }
                val body = resp.body ?: throw IOException("empty body")
                // Open our own handle and seek to this chunk's absolute offset (idempotent).
                RandomAccessFile(destFile, "rw").use { raf ->
                    raf.seek(chunk.start)
                    body.byteStream().use { input ->
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            if (isCancelled()) throw CancellationException()
                            // v2 RACE done-check: if the chunk has already been completed (by the
                            // primary, or by another racer) bail BEFORE writing — avoid wasted
                            // writes and let the loser stop promptly. Both attempts write identical
                            // bytes to the same offset, so a stray late write would be harmless
                            // (last-write-wins), but checking `done` first avoids the work entirely.
                            // Only racers check this: the primary owns the chunk and must run to its
                            // own markDone (its own markDone is the no-op if a racer beat it).
                            if (isRace && queue.isDone(chunk.index)) {
                                hostReleased = true
                                return  // finally cancels the Call, releases permit + race slot
                            }
                            if (read > 0) {
                                raf.write(buf, 0, read)
                                bytesThisChunk += read
                                liveBytes.addAndGet(read.toLong())
                                lastReadAt = System.currentTimeMillis()
                            }
                            onReport()

                            val now = System.currentTimeMillis()
                            // HARD STALL (unchanged, unambiguous, safe): zero bytes for
                            // STALL_TIMEOUT_MS -> bail and requeue AS A STALL (Fix A), not an error.
                            if (now - lastReadAt >= AdaptiveEngine.STALL_TIMEOUT_MS) {
                                throw StallException("hard stall on ${host.hostLabel()}", soft = false)
                            }
                            // SOFT STALL (Fix B — real, sustained, guarded). Evaluate at most once
                            // per measurement window (~350ms) so "consecutive windows" is meaningful
                            // and cheap. A chunk migrates only when ALL hold:
                            //   - past TCP warm-up (MIN_SAMPLE_MS),
                            //   - its rate is below SOFT_STALL_FRACTION × the CURRENT recent-rate
                            //     baseline (median of recent completed chunks, not a stale peak),
                            //   - it has stayed below that bar for SOFT_STALL_WINDOWS consecutive
                            //     windows (not a single jittery sample),
                            //   - the baseline is trustworthy (>= SOFT_STALL_MIN_SAMPLES; otherwise
                            //     soft-stall is DISABLED and we never migrate — the safe fallback),
                            //   - there is unclaimed work to migrate to (else migration is pointless).
                            val elapsed = now - chunkStart
                            if (elapsed >= AdaptiveEngine.MIN_SAMPLE_MS &&
                                now - lastSoftCheck >= 350L
                            ) {
                                lastSoftCheck = now
                                val rate = bytesThisChunk.toDouble() / elapsed.coerceAtLeast(1)
                                // Count consecutive sub-bar windows (no-op when soft-stall disabled
                                // by the min-sample guardrail — isBelowBar returns false then).
                                consecutiveSlowWindows =
                                    if (recentRates.isBelowBar(rate)) consecutiveSlowWindows + 1 else 0
                                if (queue.unclaimed() > 0 &&
                                    recentRates.shouldSoftStall(rate, elapsed, consecutiveSlowWindows)
                                ) {
                                    throw StallException("soft stall on ${host.hostLabel()}", soft = true)
                                }
                            }
                        }
                    }
                }
            }
            } // end withReauthRetry (laned ranged GET)
            // FULL-RANGE VERIFICATION: only mark done when the received byte count matches the
            // chunk length EXACTLY. A short read requeues the WHOLE chunk (no partial done).
            // (A racer that read its full range but lost the race to the primary still lands here
            // and its markDone is simply a no-op — last-write-wins, idempotent.)
            if (bytesThisChunk != chunk.length) {
                if (isRace) {
                    // A short-read RACER never errors/requeues the chunk (the primary owns it). Just
                    // give up the race; the primary (or another racer) will complete the range.
                    hostReleased = true
                    return
                }
                throw IOException("Short chunk ${chunk.index}: got $bytesThisChunk of ${chunk.length}")
            }
            // Success: record the observed rate, reset the lane's error streak, mark done, count
            // bytes. A primary success re-confirms its pinned host as a healthy lane (failover).
            val ms = (System.currentTimeMillis() - chunkStart).coerceAtLeast(1)
            val observedRate = bytesThisChunk.toDouble() / ms
            hostReleased = true
            if (!isRace) onHostOutcome(host, true)   // v3: lane is healthy — reset its error streak
            // Feed the CURRENT recent-rate baseline with this real completed-chunk rate.
            recentRates.record(observedRate)
            // markDone is the single atomic winner-decider under the chunk's lock: it returns true
            // for EXACTLY ONE caller (the first to finish the range — primary or racer), false for
            // the loser. Only the winner counts the bytes, so confirmedBytes can never double-count
            // a raced chunk.
            if (queue.markDone(chunk)) {
                confirmedBytes.addAndGet(chunk.length)
                if (isRace) onRaceWon()   // v2: this racer beat the primary — count the win.
                // RESUME: persist the (throttled) done-set so an app-kill resumes from here. Fired
                // only on a real DONE transition (exactly once per chunk), never per byte.
                onChunkDone()
                // INCREMENTAL MD5: fold this freshly-completed chunk into the in-order digest. Fires in
                // the SAME exactly-once markDone-winner block, so each chunk is reported once. The
                // accumulator reads from the committed .part (this chunk's full range is already
                // written above) and only advances when the contiguous prefix extends.
                incrementalMd5?.onChunkDone(chunk.index)
            }
            // DIAG: accumulate per-host stats and HTTP protocol for the bench log.
            // Called for EVERY successful chunk attempt (primary or winning racer) so the
            // per-host bytes/ms totals are accurate even when racing is active.
            // chunkProtocol is set right after the 206 check; if empty (shouldn't happen on
            // this path) we fall back to "unknown" to avoid a blank entry.
            onChunkCompleted(
                host,
                bytesThisChunk,
                (System.currentTimeMillis() - chunkStart).coerceAtLeast(1),
                chunkProtocol.ifEmpty { "unknown" }
            )
            reconciled = true  // Fix D: chunk reached a terminal-done state on this path.
            onReport()
        } catch (c: CancellationException) {
            // Structured cancellation: requeue is unnecessary (the whole scope is being torn down).
            // Re-throw so the caller deletes the .part. The finally must NOT force-requeue here, so
            // mark reconciled — the scope teardown is authoritative. (A cancellation is NOT a lane
            // error: don't touch the failover streak.)
            hostReleased = true
            reconciled = true
            throw c
        } catch (s: StallException) {
            // STALL (Fix A + C): NOT an error. It never consumes the error budget, never fails the
            // download, and is NOT a lane-death signal — the chunk is just requeued for another
            // runner/host. (No scoreboard anymore: a stall doesn't demote/cool a pinned host; the
            // soft-stall sustain logic on the next runner governs migration. We deliberately do NOT
            // bump the lane error streak for a stall — only real transport errors kill a lane.)
            hostReleased = true
            // v2: a RACER never requeues — the primary owns the chunk and is still running. A racer
            // stalling on its second host just abandons the race (the primary completes the chunk).
            if (!isRace) {
                queue.requeueStall(chunk)        // own large budget; cannot fail the download
                reconciled = true                // Fix D: chunk reconciled (requeued).
            }
            // No exponential backoff for a stall — the point is to migrate PROMPTLY to a peer/host.
            @Suppress("UNUSED_EXPRESSION") s     // keep the binding referenced (soft/hard distinction
                                                 // is logged in the message); no per-host action now.
        } catch (e: Exception) {
            // REAL transport error (non-206, short read, IOException, timeout): consume the per-chunk
            // ERROR budget. When exhausted, the chunk enters the terminal FAILED state and we rethrow
            // so the whole download fails — same terminal semantics as the static per-segment loop,
            // but ONLY for genuine errors. v3: ALSO report the error to the lane-failover bookkeeping
            // so a host that racks up consecutive errors is declared dead and its runners re-target.
            hostReleased = true
            // v2: a RACER never consumes the error budget and never fails the download — it is
            // purely additive. On any racer error we just abandon the race; the primary still owns
            // the chunk and its own error budget governs whether the download ultimately fails. A
            // racer also does NOT affect lane health (only primaries on their pinned lane do).
            if (isRace) {
                // reconciled stays true (a racer never owns reconciliation) — nothing to requeue.
                return
            }
            onHostOutcome(host, false)           // v3: bump this lane's consecutive-error streak
            val exhausted = queue.recordErrorOrFail(chunk, AdaptiveEngine.MAX_ERROR_ATTEMPTS)
            reconciled = true                    // Fix D: chunk reconciled (requeued or failed).
            if (exhausted) throw e
            // Backoff before the chunk is retried by some worker (same constants as static path).
            val nextTry = queue.errorAttemptsOf(chunk.index)
            kotlinx.coroutines.delay(min(1000L * (1L shl ((nextTry - 1).coerceAtLeast(0))), 8000L))
        } finally {
            // Cancel the in-flight Call so a stalled socket is torn down promptly. For a losing
            // racer this tears down the duplicate connection so it stops pulling bytes immediately.
            runCatching { call?.cancel() }
            // Always release the global connection slot we actually acquired (a racer that failed
            // tryAcquire returned before this point and never reaches here). Pairing acquire with
            // release in finally is THE invariant that keeps total permits <= the cap (24) across
            // all runners AND racers.
            if (connAcquired) {
                connectionBudget.release()
                onConnReleased()
            }
            @Suppress("UNUSED_EXPRESSION") hostReleased  // retained for readability; no scoreboard now
            // v2: release the racer slot on EVERY racer exit path (won, lost, errored, cancelled).
            // A no-op for the primary. Pairs with pickRaceTarget's racer-count increment so racer
            // accounting can never leak.
            if (isRace) queue.endRace(chunk)
            // Fix D — SELF-HEALING completion gate: if the chunk was neither marked done nor
            // requeued/failed on ANY exit path (e.g. an unexpected throw before reconciliation),
            // force it back onto the queue so it can never be orphaned and leak an in-flight slot.
            // A racer never owns reconciliation (reconciled starts true for racers), so this never
            // force-requeues a chunk the primary still owns.
            if (!reconciled) queue.forceRequeue(chunk)
        }
    }

    /** Compact host label for logs (scheme/host only). */
    private fun String.hostLabel(): String = substringAfter("//").substringBefore('/')

    /**
     * BATCH-WIDE SETUP ELISION (v0.9): strip the trailing [encodedPath] from a resolved host URL to
     * get its reusable PREFIX (scheme://host + item dir). Within one IA item every file's resolved URL
     * ends with the file's own encoded path; the prefix before it is identical across files. If the
     * URL doesn't end with [encodedPath] (a server that rewrote the path on redirect), we keep the URL
     * verbatim — appending a path to it would be wrong, so the safe choice is to not strip, which the
     * cache reuse then re-appends a path to; the per-chunk Content-Range guard + final MD5 still catch
     * any resulting mismatch and the normal failover absorbs it. (Empty [encodedPath] -> no stripping.)
     */
    private fun String.stripEncodedSuffix(encodedPath: String): String =
        if (encodedPath.isNotEmpty() && endsWith(encodedPath)) substring(0, length - encodedPath.length)
        else this

    /**
     * Internal marker for a detected STALL (vs. a real transport error). A stall is always
     * recoverable and NEVER consumes the per-chunk error budget (Fix A): it only migrates the
     * chunk. [soft] distinguishes a soft stall (slower than the current baseline — host is merely
     * de-preferred, NOT cooled; Fix C) from a hard stall (zero bytes for the timeout — host cooled).
     */
    private class StallException(message: String, val soft: Boolean) : IOException(message)

    /** Follow a redirect once and return the final CDN URL (or null on failure). */
    private fun resolveFinalUrl(url: String): String? = try {
        val req = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Encoding", "identity")
            .header("Range", "bytes=0-0") // tiny request; we only want the final URL
            .header("Referer", "https://archive.org/")
            .build()
        okHttpClient.newCall(req).execute().use { resp ->
            resp.request.url.toString()
        }
    } catch (e: Exception) {
        Log.w("EmuHelper", "resolveFinalUrl failed for ${url.take(80)}", e)
        null
    }

    /** Does the server return 206 for a tiny Range probe? */
    private fun supportsRange(url: String): Boolean = try {
        val req = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Encoding", "identity")
            .header("Range", "bytes=0-0")
            .header("Referer", "https://archive.org/")
            .build()
        okHttpClient.newCall(req).execute().use { it.code == 206 }
    } catch (e: Exception) {
        Log.w("EmuHelper", "range probe failed for ${url.take(80)}", e)
        false
    }

    /** Single-connection fallback writing sequentially to a local file. */
    private suspend fun singleStreamTo(
        url: String,
        expectedSize: Long,
        destFile: File,
        onProgress: suspend (Long, Double) -> Unit,
        isCancelled: () -> Boolean
    ): Long {
        destFile.parentFile?.mkdirs()
        val req = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Encoding", "identity")
            .header("Accept", "*/*")
            .header("Referer", "https://archive.org/")
            .build()
        var downloaded = 0L
        val start = System.currentTimeMillis()
        var lastReport = start
        // SESSION-EXPIRY RECOVERY: a 401 on the single-stream fallback (session expired mid-batch)
        // silently re-authenticates once and retries. The 401 is rejected before any body bytes, and
        // FileOutputStream(destFile) truncates on (re)open, so a retry restarts the file cleanly;
        // `downloaded` is reset per attempt so progress can't double-count.
        withReauthRetry {
        downloaded = 0L
        okHttpClient.newCall(req).execute().use { resp ->
            if (ReauthCoordinator.isAuthExpiry(resp.code, authExpiryHint = true)) {
                throw AuthExpiredException(resp.code)
            }
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("empty body")
            java.io.BufferedOutputStream(java.io.FileOutputStream(destFile), 256 * 1024).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(256 * 1024)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        if (isCancelled()) throw CancellationException()
                        out.write(buf, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 350) {
                            lastReport = now
                            val secs = (now - start).coerceAtLeast(1) / 1000.0
                            onProgress(downloaded, downloaded / secs)
                        }
                    }
                    out.flush()
                }
            }
        }
        } // end withReauthRetry (single-stream fallback)
        onProgress(downloaded, 0.0)
        return downloaded
    }

    suspend fun fetchFileSize(identifier: String, filename: String): Long = withContext(Dispatchers.IO) {
        try {
            // Use cached metadata to avoid a redundant network round-trip.
            val data = fetchMetadata(identifier) ?: return@withContext 0L
            val files = data["files"]?.jsonArray ?: return@withContext 0L
            val target = File(filename).name.lowercase()
            for (f in files) {
                val obj = f.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: continue
                val cand = File(name).name.lowercase()
                if (cand == target) {
                    return@withContext obj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                }
            }
            0L
        } catch (e: Exception) {
            Log.w("EmuHelper", "fetchFileSize($identifier/$filename) failed", e)
            0L
        }
    }
}

sealed class LoginResult {
    data object Success : LoginResult()
    data class Failed(val message: String) : LoginResult()
}
