package io.github.mayusi.emuhelper.data.source.root

import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional ROOT bridge for AYN / Retroid handhelds that ship the vendor "PServerBinder" service —
 * a binder that runs a shell command as root. This is the FOUNDATION: a clean, stable public API
 * (executeShell / isAvailable / availableNow) that later features build on. The dangerous part —
 * deciding *which* commands may run — lives entirely in [PServerCommandGuard]; every command funnels
 * through [transact], which guard-checks BEFORE touching the binder, so nothing reaches root without
 * passing the default-deny allow-list.
 *
 * Detection model (deliberately tiny, and NOT based on any OS security-policy mode): root is
 * "available" iff a no-op `true` command transacts successfully and returns status 0. If the binder
 * is absent, or the transact throws / returns the UNKNOWN_TRANSACTION sentinel, root is simply
 * unavailable and the whole feature stays silently inert. The app behaves 100% normally on devices
 * without PServer.
 *
 * Crash-safety: every reflection/transact is wrapped; any failure degrades to null/false. This class
 * can never crash the app.
 */
@Singleton
class PServerBridge @Inject constructor(
    @ApplicationContext private val context: Context
) {

    // Memoized availability. @Volatile so a value published by the probing coroutine is visible to
    // sync readers (availableNow) and other coroutines without a lock. null = not yet probed.
    @Volatile
    private var cachedAvailable: Boolean? = null

    init {
        // Register this app's RUNTIME package (which carries an applicationId suffix on non-release
        // builds, e.g. ".debug") so the guard accepts this app's own private dirs as a staging write
        // root. Without this the fast-path `cp` — whose source is the app cache file under
        // /data/data/<runtime-pkg> — would be wrongly BLOCKED on every non-release install.
        PServerCommandGuard.registerOwnPackage(context.packageName)
    }

    /** Reflectively fetch the PServer binder. Returns null on ANY failure (service absent, a
     *  denied lookup, reflection error, wrong device). Never throws. */
    private fun binder(): IBinder? = runCatching {
        Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, PSERVER_SERVICE) as? IBinder
    }.getOrNull()

    /**
     * The single funnel through which EVERY command reaches root. Guard-check FIRST: a denied command
     * NEVER touches the binder — it returns (-1, "BLOCKED: <reason>"). Only an allowed command is
     * written to the parcel and transacted, using the EXACT proven PServer wire format:
     *   - writeStringArray([command, "1"])  — the "1" is the root flag; NO interface token (an
     *     interface token corrupts PServer's raw readStringArray).
     *   - transact(0, ...)  — code 0 = SHELL_COMMAND slot. A false return = UNKNOWN_TRANSACTION
     *     (wire mismatch / not actually PServer) -> treated as failure (-1).
     *   - reply.createByteArray() decoded as UTF-8; the literal "null" means empty output.
     * Both parcels are always recycled.
     */
    private fun transact(binder: IBinder, command: String): Pair<Int, String> {
        when (val verdict = PServerCommandGuard.inspect(command)) {
            is PServerCommandGuard.Verdict.Deny -> return -1 to "BLOCKED: ${verdict.reason}"
            is PServerCommandGuard.Verdict.Allow -> { /* fall through to transact */ }
        }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeStringArray(arrayOf(command, ROOT_FLAG))
            val ok = binder.transact(SHELL_COMMAND_CODE, data, reply, 0)
            if (!ok) {
                -1 to "" // UNKNOWN_TRANSACTION — not a live PServer wire
            } else {
                val output = reply.createByteArray()
                    ?.toString(Charsets.UTF_8)
                    ?.trim()
                    ?.let { if (it == "null") "" else it }
                    .orEmpty()
                0 to output
            }
        } catch (_: Throwable) {
            // Any binder/parcel failure -> blocked/failed, never a crash.
            -1 to ""
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /**
     * Run a shell command as root, off the main thread. Returns null if there is no PServer binder
     * at all (root unsupported on this device). Otherwise returns (status, output): status 0 = ok,
     * status -1 = blocked-by-guard or transact failure. The command is guard-validated inside
     * [transact] before anything reaches root.
     */
    suspend fun executeShell(command: String): Pair<Int, String>? = withContext(Dispatchers.IO) {
        val b = binder() ?: return@withContext null
        transact(b, command)
    }

    /**
     * THE PROBE — the only detection. Memoized: a cached non-null result is returned immediately.
     * Otherwise: get the binder; if absent, cache false. If present, run a no-op `true` and require
     * (result != null && status == 0). Cache and return. No system-policy probing, no Build
     * gymnastics — the `true` transact is the real test.
     *
     * A cheap early-out: if the manufacturer is clearly NOT an AYN/Retroid-class device AND there is
     * no binder, skip and cache false. The `true` probe still gates the positive case, so an
     * unexpected vendor that happens to ship PServer is still detected via the binder presence.
     */
    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        cachedAvailable?.let { return@withContext it }

        val b = binder()
        if (b == null) {
            // No service. (The manufacturer hint is purely an early-out comment; the binder being
            // null is itself the decisive negative here.)
            cachedAvailable = false
            return@withContext false
        }

        val result = runCatching { transact(b, PROBE_COMMAND) }.getOrNull()
        val ok = result != null && result.first == 0
        cachedAvailable = ok
        ok
    }

    /** Synchronous cached read of availability. false if the probe has never run. Never blocks. */
    fun availableNow(): Boolean = cachedAvailable == true

    /** Reset the memoized availability so the next [isAvailable] re-probes. */
    fun invalidateCache() {
        cachedAvailable = null
    }

    /** Best-effort manufacturer hint (informational only; not used as a gate). */
    @Suppress("unused")
    private fun looksLikeSupportedVendor(): Boolean {
        val m = Build.MANUFACTURER.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()
        return listOf("ayn", "retroid", "gpd", "anbernic").any { m.contains(it) || brand.contains(it) }
    }

    companion object {
        private const val PSERVER_SERVICE = "PServerBinder"
        private const val SHELL_COMMAND_CODE = 0 // PServer's SHELL_COMMAND transaction slot
        private const val ROOT_FLAG = "1"        // second string-array element = run-as-root flag
        private const val PROBE_COMMAND = "true" // no-op used solely to detect a live PServer
    }
}
