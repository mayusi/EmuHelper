package io.github.mayusi.emuhelper.platform

/**
 * Tiny logging seam so the portable download stack (RemoteSource, the cookie jar) can log without
 * referencing `android.util.Log` — which only exists on the Android target. Each platform provides
 * an implementation:
 *
 *   • android  -> forwards to android.util.Log (see :app androidMain wiring / [Log.install]).
 *   • desktop  -> prints to stderr (see :desktopApp wiring).
 *
 * The default implementation is a no-op so unit tests and any unconfigured runtime stay silent
 * rather than crashing. [Log] is a thin static facade matching the `Log.w(tag, msg, e)` /
 * `Log.i(tag, msg)` shape the moved Android code already used, so the move was a near-mechanical
 * find/replace of `android.util.Log` -> `io.github.mayusi.emuhelper.platform.Log`.
 */
interface PlatformLog {
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String, t: Throwable? = null)
    fun e(tag: String, msg: String, t: Throwable? = null)
}

/**
 * Process-wide logging facade. Defaults to a silent no-op; a platform installs its real sink once
 * at startup via [install]. Kept as a plain `object` (no DI) because the moved download code logs
 * from deep, non-injected call sites and the original `android.util.Log` was likewise a static.
 */
object Log {
    @Volatile
    private var delegate: PlatformLog = NoOpLog

    /** Install the platform sink (android.util.Log on Android, stderr on desktop). Idempotent. */
    fun install(impl: PlatformLog) { delegate = impl }

    fun i(tag: String, msg: String) = delegate.i(tag, msg)
    fun w(tag: String, msg: String, t: Throwable? = null) = delegate.w(tag, msg, t)
    fun w(tag: String, msg: String) = delegate.w(tag, msg, null)
    fun e(tag: String, msg: String, t: Throwable? = null) = delegate.e(tag, msg, t)
    fun e(tag: String, msg: String) = delegate.e(tag, msg, null)

    private object NoOpLog : PlatformLog {
        override fun i(tag: String, msg: String) {}
        override fun w(tag: String, msg: String, t: Throwable?) {}
        override fun e(tag: String, msg: String, t: Throwable?) {}
    }
}
