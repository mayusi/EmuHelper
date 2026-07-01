package io.github.mayusi.emuhelper.platform

/**
 * The credentials the portable [io.github.mayusi.emuhelper.data.source.RemoteSource] needs for its
 * silent re-auth path (ReauthCoordinator): the saved email + password used to re-login when an IA
 * session expires mid-download.
 *
 * This is a NARROW seam — RemoteSource only ever reads the saved email + password — so the full
 * Android `AuthStore` (with its UI-facing reactive Flows + EncryptedSharedPreferences persistence)
 * stays in :app and simply implements this interface. Desktop provides a file-backed implementation.
 */
interface AuthCredentials {
    /** Saved email (synchronous; "" when none). */
    fun savedEmailNow(): String

    /** Saved password (suspending; "" when none). */
    suspend fun getSavedPassword(): String
}

/**
 * Persistence seam for the portable [io.github.mayusi.emuhelper.di.PersistentCookieJar]. The cookie
 * jar LOGIC (merge-by-name, drop-expired, archive-host scoping, the logged-in StateFlow) is pure
 * Kotlin + OkHttp and lives in :shared. Only the BACKING STORE differs per platform:
 *
 *   • android  -> EncryptedSharedPreferences (a StringSet under one key). See :app androidMain.
 *   • desktop  -> a plain file under the user config dir (one serialized cookie per line).
 *
 * Cookies are handed over already serialized to the jar's collision-proof string form, so the store
 * only has to persist/restore a `Set<String>` — it needs no knowledge of cookie structure.
 */
interface CookieStore {
    /** Load the persisted serialized-cookie set (empty when none / store unavailable). */
    fun load(): Set<String>

    /** Persist the serialized-cookie set (best-effort; a failure must not crash the caller). */
    fun save(cookies: Set<String>)

    /** Drop all persisted cookies (logout). */
    fun clear()

    /** A no-op store: cookies live only in memory for the session. Safe default for tests. */
    object InMemoryNone : CookieStore {
        override fun load(): Set<String> = emptySet()
        override fun save(cookies: Set<String>) {}
        override fun clear() {}
    }
}
