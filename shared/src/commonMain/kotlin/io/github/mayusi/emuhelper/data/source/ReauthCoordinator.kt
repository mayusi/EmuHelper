package io.github.mayusi.emuhelper.data.source

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * SINGLE-FLIGHT SILENT RE-AUTH (session-expiry recovery).
 *
 * Background: during a long overnight batch the Internet Archive auth session can expire mid-download.
 * The datanodes then return HTTP 401 (or, for some restricted items, a 403) on the very next ranged
 * GET, and the affected chunks/requests would otherwise be marked "failed" with no recovery. A
 * multi-hour batch must instead SILENTLY re-authenticate with the saved credentials and retry, so the
 * user never sees a spurious failure.
 *
 * Why a coordinator: a laned download keeps up to ~24 requests in flight. When the session expires,
 * MANY of them hit 401 at almost the same instant. Without coordination each one would fire its own
 * login POST — a thundering herd of redundant logins against archive.org. This collapses all
 * concurrent 401s into EXACTLY ONE login per "generation": the first caller performs the re-login
 * under a [Mutex]; every other caller that arrives while a re-login is in progress simply waits for it
 * and then proceeds on the freshly-restored cookie jar.
 *
 * Generation + retry-once: every successful re-login bumps a monotonically increasing [generation]
 * counter. A request records the generation it last retried under; it is only permitted ONE retry per
 * generation. So the sequence is:
 *   1. request 401s at generation G  -> [requestReauth] re-logs in, bumps to G+1, request retries.
 *   2. if it 401s AGAIN but the generation is still G+1 (no newer login happened) -> it has already
 *      consumed its retry for this generation, so [shouldRetryAfter401] returns false and the request
 *      genuinely fails. This is what prevents an infinite 401 -> login -> 401 loop when the saved
 *      password is simply wrong for the now-restricted item, or the item is truly forbidden.
 *
 * The login itself is delegated through [doLogin] (a suspend lambda returning true on success) and the
 * credential presence is decided by [credentialsAvailable]. Both are injected so the whole decision
 * unit is testable with zero network — see ReauthCoordinatorTest.
 *
 * Thread-safety: all generation reads/writes and the single-flight gate happen under [mutex]; the
 * login network call also runs under the same lock so it is strictly serialized (only one in flight),
 * which is exactly the single-flight guarantee. The lock is held only for the brief login POST, never
 * for the multi-MB chunk transfers, so it does not serialize the download itself.
 */
/* portable: was internal — public so the :shared RemoteSource + the :app unit test both resolve it across the module boundary (Phase 2 Windows port). */
class ReauthCoordinator(
    /** Performs the actual re-login (e.g. RemoteSource.login(savedEmail, savedPassword) == Success). */
    private val doLogin: suspend () -> Boolean,
    /** True when there are non-blank saved credentials to re-login with. */
    private val credentialsAvailable: suspend () -> Boolean
) {
    private val mutex = Mutex()

    /**
     * Bumped once per SUCCESSFUL re-login. Starts at 0. A caller passes the generation it observed
     * before its failed request; if the current generation is already NEWER, some other caller has
     * re-authenticated in the meantime and this caller should just retry WITHOUT triggering its own
     * login (the cookie jar is already fixed).
     */
    @Volatile private var generation: Long = 0L

    /** The generation a fresh caller should record before its first attempt. */
    fun currentGeneration(): Long = generation

    /**
     * Outcome of a re-auth request, returned to the failing caller so it knows whether to retry.
     *  - [Retry] : a valid (current) session now exists — re-run the failed request ONCE. [generation]
     *    is the generation the caller must record so a subsequent 401 in the SAME generation fails fast.
     *  - [NoCredentials] : nothing saved to log in with — propagate the original failure with a clear
     *    "sign in again" message. No login was attempted.
     *  - [Failed] : credentials existed but the re-login itself failed (bad password / network) —
     *    propagate the failure.
     */
    sealed class Outcome {
        data class Retry(val generation: Long) : Outcome()
        data object NoCredentials : Outcome()
        data object Failed : Outcome()
    }

    /**
     * Single-flight re-auth entry point, called by a request that just got a 401/403-auth.
     *
     * [observedGeneration] is the generation the caller recorded before its failed attempt (from
     * [currentGeneration] when it started, or the value from the previous [Outcome.Retry]). Behaviour:
     *
     *  - If the current [generation] is ALREADY newer than [observedGeneration], another concurrent
     *    caller re-authenticated after this caller started — the session is fresh, so we DON'T log in
     *    again; we just tell the caller to retry under the new generation (single-flight: their login
     *    covered us too).
     *  - Otherwise this caller is the first to notice the expiry for this generation. Under the lock
     *    we re-check (double-checked) in case a login completed while we waited, then attempt exactly
     *    one login. On success we bump the generation and return [Outcome.Retry]; the waiters that
     *    queued behind the lock will see the bumped generation and skip their own login.
     *
     * Returns [Outcome.NoCredentials] (no login attempted) when [credentialsAvailable] is false, and
     * [Outcome.Failed] when the login was attempted but did not succeed.
     */
    suspend fun requestReauth(observedGeneration: Long): Outcome {
        // Fast path WITHOUT the lock: someone already re-authenticated after we started -> just retry.
        if (generation > observedGeneration) {
            return Outcome.Retry(generation)
        }
        mutex.withLock {
            // Double-check under the lock: a login may have completed while we were queued. If the
            // generation advanced past what we observed, that login is ours to reuse — retry, no new
            // login. THIS is how N concurrent 401s collapse to ONE login: only the first through the
            // lock at a given generation actually calls doLogin; the rest take this early return.
            if (generation > observedGeneration) {
                return Outcome.Retry(generation)
            }
            // We hold the lock and the generation hasn't advanced -> we are the single flight.
            if (!credentialsAvailable()) {
                return Outcome.NoCredentials
            }
            val ok = doLogin()
            return if (ok) {
                generation += 1
                Outcome.Retry(generation)
            } else {
                Outcome.Failed
            }
        }
    }

    /**
     * Pure retry-once gate used at the moment a request decides whether a 401 it just received is
     * worth attempting a re-auth for. A request may attempt re-auth only if it has NOT already retried
     * in the CURRENT generation (i.e. [lastRetriedGeneration] is older than [currentGeneration], or it
     * never retried = -1). Once it has retried under generation G and the generation is still G, a
     * further 401 means the fresh session still can't read this resource -> stop (no infinite loop).
     */
    fun shouldAttemptReauth(lastRetriedGeneration: Long): Boolean =
        lastRetriedGeneration < generation || lastRetriedGeneration < 0L

    companion object {
        /** Sentinel "this request has never retried after a 401". */
        const val NEVER_RETRIED = -1L

        /**
         * Is [code] an auth-expiry status that silent re-auth should handle? 401 always; 403 only when
         * it carries an auth-expiry signal (the IA datanodes sometimes 403 a stale session rather than
         * 401). A plain 403 on a genuinely forbidden item is NOT retried here — that would loop — so we
         * gate the 403 case on [authExpiryHint].
         */
        fun isAuthExpiry(code: Int, authExpiryHint: Boolean): Boolean =
            code == 401 || (code == 403 && authExpiryHint)
    }
}
