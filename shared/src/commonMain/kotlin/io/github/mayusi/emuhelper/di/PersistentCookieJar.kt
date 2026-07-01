package io.github.mayusi.emuhelper.di

import io.github.mayusi.emuhelper.platform.CookieStore
import io.github.mayusi.emuhelper.platform.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * Cookie jar with on-disk persistence so a logged-in session survives app
 * restarts (no re-login, no re-typing credentials).
 *
 * PORTABILITY (Phase 2 of the Windows port): all of the cookie LOGIC below — merge-by-name,
 * drop-expired sentinels, archive-host scoping, the logged-in StateFlow, the serialize/deserialize —
 * is pure Kotlin + OkHttp and now lives in :shared so the desktop app reuses it byte-for-byte. The
 * ONLY platform-specific piece is the backing store, injected as a [CookieStore]:
 *   • android  -> EncryptedSharedPreferences (a StringSet under one key)   [:app androidMain]
 *   • desktop  -> a plain file under the user config dir                    [:desktopApp]
 * The store only ever sees the already-serialized `Set<String>`, so it needs no cookie knowledge.
 *
 * The configured source host routes downloads through per-request CDN nodes.
 * Three things must hold:
 *
 *  1. Cookies set for the root domain must be sent to EVERY subdomain host,
 *     not just the exact host that set them. (CDN nodes need the auth cookies.)
 *  2. The login flow sets a cookie, then immediately "deletes" it in the same
 *     response by re-setting the same name with an expiry in 1970. We must MERGE
 *     by name and DROP expired cookies, otherwise the `=deleted` sentinels
 *     (and stale values from earlier requests) clobber the real auth cookies and
 *     the source host treats us as logged-out -> 401 on restricted items.
 *  3. The session cookies (logged-in-user/logged-in-sig) are persistent (multi-year
 *     expiry). Persisting them to disk lets us restore the session on a cold
 *     start instead of doing a fresh network login every launch.
 *
 * Stored flat (not per-host) keyed by cookie name, because all session
 * cookies share the same registrable domain.
 */
class PersistentCookieJar(private val store: CookieStore) : CookieJar {
    // name -> most recent non-expired cookie for the configured source domain
    private val cookies = ConcurrentHashMap<String, Cookie>()

    // Reactive login state so the UI can update the instant the (async, off-main)
    // disk restore finishes — otherwise an early synchronous isLoggedIn() reads the
    // still-empty store on cold start and the user looks signed-out until a manual
    // re-check. Starts false; flipped after loadFromDisk() and on every mutation.
    private val _loggedIn = MutableStateFlow(false)
    val loggedIn: StateFlow<Boolean> = _loggedIn
    // Recompute from the live store and publish. Idempotent + cheap: StateFlow conflates,
    // so this only emits when the computed value differs from the current one.
    private fun refreshLoggedIn() { _loggedIn.value = computeHasCookies() }

    // An awaitable signal that the disk restore has finished. Callers that need to know the
    // persisted-session state can suspend on awaitRestored() instead of racing a blind timer.
    private val restored = CompletableDeferred<Unit>()
    private val restoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Restore the saved session off the main thread; the in-memory `cookies` map fills in
        // shortly after construction, then we publish the restored login state so the UI can react.
        // Complete `restored` at the very END so anyone awaiting it sees a fully-restored value.
        restoreScope.launch {
            try {
                loadFromDisk()
                refreshLoggedIn()
            } finally {
                restored.complete(Unit)
            }
        }
    }

    /**
     * Suspends until the initial on-disk cookie restore has completed (then [loggedIn] /
     * [hasCookies] reflect the persisted session). Returns immediately if the restore is
     * already done. Safe to call any number of times from any coroutine.
     */
    suspend fun awaitRestored() {
        restored.await()
    }

    /**
     * Force a re-evaluation of the login state from the live cookie store and re-publish it
     * to [loggedIn]. Conflated: emits only if the value actually changed.
     */
    fun publishLoginState() = refreshLoggedIn()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        var changed = false
        for (cookie in cookies) {
            if (cookie.expiresAt <= now) {
                if (this.cookies.remove(cookie.name) != null) changed = true
            } else {
                this.cookies[cookie.name] = cookie
                changed = true
            }
        }
        if (changed) { persistToDisk(); refreshLoggedIn() }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val host = url.host
        // Defense in depth: only send cookies to archive.org and its CDN subdomains.
        // This ensures session cookies never travel to github.com or any other host,
        // even if a misconfigured client instance has the cookie jar attached.
        val isArchiveHost = host == "archive.org" || host.endsWith(".archive.org")
        if (!isArchiveHost) return emptyList()

        val result = ArrayList<Cookie>(cookies.size)
        val expiredNames = ArrayList<String>()
        for ((name, cookie) in cookies) {
            if (cookie.expiresAt <= now) { expiredNames.add(name); continue }
            // Send a cookie if the request host matches its domain (or any subdomain of it).
            if (host == cookie.domain || host.endsWith("." + cookie.domain)) {
                result.add(cookie)
            }
        }
        if (expiredNames.isNotEmpty()) {
            expiredNames.forEach { cookies.remove(it) }
            persistToDisk()
            refreshLoggedIn()
        }
        return result
    }

    fun clear() {
        cookies.clear() // in-memory flips immediately so hasCookies() is correct now
        refreshLoggedIn()
        Thread {
            try { store.clear() } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
    }

    /**
     * True only when we hold the session cookies used by the configured source host
     * to prove a logged-in session, with a real (non-"deleted") value and not yet expired.
     */
    fun hasCookies(): Boolean = computeHasCookies()

    private fun computeHasCookies(): Boolean {
        val now = System.currentTimeMillis()
        // IMPORTANT: some hosts set an auth/CSRF token cookie even on a FAILED login,
        // so it must NOT be treated as proof of a session. The real session cookies
        // are `logged-in-sig` / `logged-in-user`, which appear ONLY after a successful
        // login. Require one of those.
        val authNames = listOf("logged-in-sig", "logged-in-user")
        return authNames.any { name ->
            val c = cookies[name]
            c != null && c.expiresAt > now && c.value.isNotBlank() && c.value != "deleted"
        }
    }

    // ---- persistence (delegated to the platform [CookieStore]) ------------------------------

    private fun persistToDisk() {
        try {
            val now = System.currentTimeMillis()
            val serialized = cookies.values
                .filter { it.expiresAt > now && it.persistent } // only durable cookies worth restoring
                .map { serialize(it) }
                .toSet()
            store.save(serialized)
        } catch (e: Exception) {
            Log.w("EmuHelper", "Persisting cookies failed", e)
        }
    }

    private fun loadFromDisk() {
        try {
            val now = System.currentTimeMillis()
            val saved = store.load()
            for (line in saved) {
                val c = deserialize(line) ?: continue
                if (c.expiresAt > now) cookies[c.name] = c
            }
        } catch (e: Exception) {
            Log.w("EmuHelper", "Loading cookies failed", e)
        }
    }

    /** Fields joined by SEP (cannot appear in a cookie):
     *  name / value / expiresAt / domain / path / secure / httpOnly / hostOnly */
    private fun serialize(c: Cookie): String = listOf(
        c.name, c.value, c.expiresAt.toString(), c.domain, c.path,
        c.secure.toString(), c.httpOnly.toString(), c.hostOnly.toString()
    ).joinToString(SEP)

    private fun deserialize(s: String): Cookie? {
        val p = s.split(SEP)
        if (p.size < 8) return null
        return try {
            val builder = Cookie.Builder()
                .name(p[0])
                .value(p[1])
                .expiresAt(p[2].toLong())
                .path(p[4])
            // hostOnly cookies use .hostOnlyDomain(); domain cookies use .domain()
            if (p[7].toBoolean()) builder.hostOnlyDomain(p[3]) else builder.domain(p[3])
            if (p[5].toBoolean()) builder.secure()
            if (p[6].toBoolean()) builder.httpOnly()
            builder.build()
        } catch (e: Exception) {
            Log.w("EmuHelper", "Cookie deserialize failed", e)
            null
        }
    }

    companion object {
        // Delimiter that cannot occur in a cookie field (name/value/domain/path
        // use [A-Za-z0-9._/=-]); this triple token is collision-proof.
        private const val SEP = "|~|"
    }
}
