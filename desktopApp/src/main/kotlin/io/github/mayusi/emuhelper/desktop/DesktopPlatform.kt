package io.github.mayusi.emuhelper.desktop

import io.github.mayusi.emuhelper.platform.AuthCredentials
import io.github.mayusi.emuhelper.platform.CookieStore
import io.github.mayusi.emuhelper.platform.PlatformLog
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Desktop (Windows/JVM) implementations of the Phase-2 platform seams. These are the desktop "actuals"
 * to the Android wiring in :app — same interfaces, no Android dependency:
 *
 *   • [DesktopLog]            -> the [PlatformLog] sink: prints to stderr (vs android.util.Log).
 *   • [FileCookieStore]       -> the [CookieStore]: one serialized cookie per line in a text file
 *                                under a config dir (vs EncryptedSharedPreferences). Each cookie line
 *                                is DPAPI-encrypted at rest (see [DpapiSecret]) — cookies are session
 *                                tokens and as sensitive as the password.
 *   • [FileAuthCredentials]   -> the [AuthCredentials]: email/password from a properties file (or env),
 *                                only needed for the silent re-auth path on auth-gated items. The
 *                                password is DPAPI-encrypted at rest (CURRENTUSER scope), matching
 *                                the AES-256/Keystore hardening Android's EncryptedSharedPreferences
 *                                gives the same secret — see [DpapiSecret].
 *   • [desktopOkHttpClient]   -> the same OkHttp tuning :app uses (warm pool, h2-first, the timeouts),
 *                                so the laned engine behaves identically on desktop.
 */

/** stderr logger — the desktop counterpart of android.util.Log. */
object DesktopLog : PlatformLog {
    override fun i(tag: String, msg: String) = System.err.println("I/$tag: $msg")
    override fun w(tag: String, msg: String, t: Throwable?) {
        System.err.println("W/$tag: $msg"); t?.printStackTrace()
    }
    override fun e(tag: String, msg: String, t: Throwable?) {
        System.err.println("E/$tag: $msg"); t?.printStackTrace()
    }
}

/**
 * File-backed cookie persistence: stores the jar's already-serialized cookie set as one line per
 * cookie in [file]. Best-effort — any I/O failure degrades to in-memory-only (cookies just don't
 * survive a restart), exactly like the Android store when secure storage is unavailable.
 *
 * Cookies are session tokens — as sensitive as the IA password — so each line is individually
 * DPAPI-encrypted at rest via [DpapiSecret] (same mechanism as [FileAuthCredentials]). A line
 * without the "dpapi:" marker is treated as legacy plaintext (pre-encryption builds, or DPAPI was
 * unavailable when it was written) and transparently migrated to encrypted form on the next [save].
 */
class FileCookieStore(private val file: File) : CookieStore {
    override fun load(): Set<String> = try {
        if (file.exists()) {
            file.readLines()
                .filter { it.isNotBlank() }
                .map { DpapiSecret.decrypt(it) }
                .filter { it.isNotBlank() }
                .toSet()
        } else emptySet()
    } catch (e: Exception) { emptySet() }

    override fun save(cookies: Set<String>) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(cookies.joinToString("\n") { DpapiSecret.encrypt(it) })
        } catch (e: Exception) { /* best-effort */ }
    }

    override fun clear() {
        try { if (file.exists()) file.delete() } catch (_: Exception) {}
    }
}

/**
 * File/env-backed credentials for the re-auth path. Reads `EMUHELPER_IA_EMAIL` / `EMUHELPER_IA_PASSWORD`
 * env vars first, then a `credentials.properties` file (keys `email` / `password`). Returns "" when
 * none — RemoteSource then simply skips silent re-auth (fine for the public, non-auth test item).
 *
 * The password (a real Internet Archive login secret) is DPAPI-encrypted at rest via [DpapiSecret] —
 * the on-disk value is the "dpapi:"-prefixed Base64 ciphertext, decryptable only by this Windows user
 * account on this machine. The email is not secret and is kept plaintext for easy inspection/migration.
 * A password line without the marker is legacy plaintext (written by an older build, or DPAPI was
 * unavailable at write time) — it's read as-is once, then transparently re-encrypted on the next
 * [save], so existing installs upgrade seamlessly with no user action.
 */
class FileAuthCredentials(private val propsFile: File) : AuthCredentials {
    // Mutable so a successful in-app login can persist the entered credentials for the silent
    // re-auth path (env vars still win as an override for CI / power users). Read lazily on first
    // access, then kept in sync by [save] / [clear].
    private var props: MutableMap<String, String> = readFile()

    private fun readFile(): MutableMap<String, String> = try {
        if (!propsFile.exists()) mutableMapOf()
        else propsFile.readLines()
            .mapNotNull { line ->
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#") || '=' !in t) null
                else t.substringBefore('=').trim() to t.substringAfter('=').trim()
            }.toMap().toMutableMap()
    } catch (e: Exception) { mutableMapOf() }

    override fun savedEmailNow(): String =
        System.getenv("EMUHELPER_IA_EMAIL") ?: props["email"]?.takeIf { it.isNotBlank() } ?: ""

    override suspend fun getSavedPassword(): String {
        System.getenv("EMUHELPER_IA_PASSWORD")?.let { return it }
        val stored = props["password"]?.takeIf { it.isNotBlank() } ?: return ""
        return DpapiSecret.decrypt(stored)
    }

    /**
     * Persist [email]/[password] to the properties file so the shared re-auth coordinator can
     * silently re-login after a session expires (the desktop analogue of the Android AuthStore's
     * saveCredentials). Best-effort: a write failure keeps the in-memory values for the session.
     * Pass [remember] = false to store only the email (password cleared) — matches Android's
     * "don't remember" path where the password is not retained. The password is DPAPI-encrypted
     * before it ever reaches [persist] — the plaintext password is never written to disk.
     */
    fun save(email: String, password: String, remember: Boolean) {
        props["email"] = email
        props["password"] = if (remember) DpapiSecret.encrypt(password) else ""
        props["remember"] = remember.toString()
        persist()
    }

    /** Whether the user asked to be remembered (drives desktop auto-login). */
    fun rememberMe(): Boolean = props["remember"]?.toBooleanStrictOrNull() ?: false

    /** Drop stored credentials on logout (email + password). */
    fun clear() {
        props = mutableMapOf()
        try { if (propsFile.exists()) propsFile.delete() } catch (_: Exception) {}
    }

    private fun persist() {
        try {
            propsFile.parentFile?.mkdirs()
            propsFile.writeText(
                buildString {
                    append("# EmuHelper desktop credentials (used only for silent re-login)\n")
                    props.forEach { (k, v) -> append("$k=$v\n") }
                }
            )
        } catch (_: Exception) { /* best-effort */ }
    }
}

/**
 * Builds the desktop OkHttpClient with the SAME tuning :app's AppModule uses, so the shared laned
 * engine gets identical warm-connection behaviour on Windows: raised per-host request cap, a wide
 * 5-minute keep-alive warm pool, h2-first protocols, and the same generous read timeout for big
 * ranged chunk transfers.
 */
fun desktopOkHttpClient(cookieJar: okhttp3.CookieJar): OkHttpClient {
    val dispatcher = okhttp3.Dispatcher().apply {
        maxRequests = 40
        maxRequestsPerHost = 32
    }
    return OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(cookieJar)
        .build()
}
