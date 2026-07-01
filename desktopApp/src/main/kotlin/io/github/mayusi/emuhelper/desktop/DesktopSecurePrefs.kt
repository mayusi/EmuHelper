package io.github.mayusi.emuhelper.desktop

import io.github.mayusi.emuhelper.platform.Log
import java.util.Base64

/**
 * Windows DPAPI-backed at-rest encryption for secrets [FileAuthCredentials] and [FileCookieStore]
 * persist to disk (the Internet Archive password + session cookies). This is the desktop analogue
 * of the Android side's AES-256/Keystore-backed EncryptedSharedPreferences — desktop must not be
 * weaker, so plaintext-on-disk is not acceptable for these two values.
 *
 * Mechanism: `CryptProtectData` / `CryptUnprotectData` (Win32 Crypt32), scope = CURRENT_USER (the
 * default — no `LocalMachine` flag is passed), via jna-platform's [com.sun.jna.platform.win32.Crypt32Util]
 * helper. The resulting blob can only be decrypted by the same Windows user account on the same
 * machine (DPAPI derives the key from the user's login credentials + machine-specific secret), which
 * is exactly the "only this Windows user can decrypt it" property we want.
 *
 * Every encrypted value stored on disk is prefixed with [MARKER] ("dpapi:") followed by the
 * Base64-encoded ciphertext, so [decrypt] can tell an encrypted value apart from a legacy plaintext
 * value written by an older EmuHelper build — enabling a seamless one-time migration (see
 * [FileAuthCredentials] / [FileCookieStore]: read once as plaintext, then the next [save] re-persists
 * it encrypted).
 *
 * Defensive by design: this object NEVER throws. [encrypt] falls back to returning the plaintext
 * unchanged (unmarked) when DPAPI/JNA is unavailable (non-Windows, missing native lib, etc.) — the
 * caller still gets a working value, just not hardened, and a warning is logged. [decrypt] falls
 * back to treating an unrecognized/undecryptable value as opaque plaintext rather than crashing
 * (e.g. a `credentials.properties` copied from another machine/user — DPAPI legitimately refuses
 * to unprotect data protected under a different user/machine).
 */
internal object DpapiSecret {
    private const val TAG = "DpapiSecret"

    /** Prefix marking a stored value as DPAPI-encrypted Base64 ciphertext (vs. legacy plaintext). */
    private const val MARKER = "dpapi:"

    private val isWindows: Boolean =
        System.getProperty("os.name")?.startsWith("Windows", ignoreCase = true) == true

    /**
     * True once we've confirmed jna-platform's Crypt32 mapping actually loads on this machine.
     * Checked lazily (not just `isWindows`) because the native Crypt32 mapping could still fail to
     * load even on Windows (e.g. a broken JNA temp-extraction dir) — [encrypt]/[decrypt] must
     * tolerate that too.
     */
    private val dpapiAvailable: Boolean by lazy {
        if (!isWindows) {
            false
        } else {
            try {
                // Touch the class to force JNA to resolve the native Crypt32 mapping now, so any
                // failure surfaces here (inside the try/catch) rather than lazily deep in encrypt().
                com.sun.jna.platform.win32.Crypt32Util::class.java
                true
            } catch (t: Throwable) {
                Log.w(TAG, "DPAPI/JNA unavailable, falling back to plaintext storage", t)
                false
            }
        }
    }

    /**
     * Encrypts [plaintext] with DPAPI (CURRENTUSER scope) and returns a [MARKER]-prefixed,
     * Base64-encoded ciphertext string suitable for writing to disk. If DPAPI is unavailable or the
     * native call fails for any reason, returns [plaintext] UNCHANGED (no marker) and logs a
     * warning — callers must never crash or lose the value because encryption wasn't possible.
     */
    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return plaintext
        if (!dpapiAvailable) return plaintext
        return try {
            val cipherBytes = com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(
                plaintext.toByteArray(Charsets.UTF_8)
            )
            MARKER + Base64.getEncoder().encodeToString(cipherBytes)
        } catch (t: Throwable) {
            Log.w(TAG, "DPAPI encrypt failed, storing value as plaintext", t)
            plaintext
        }
    }

    /**
     * Decrypts a value previously produced by [encrypt]. Dispatches on the [MARKER] prefix:
     *   - No marker           -> legacy/plaintext value (or DPAPI was unavailable when it was
     *                            written); returned as-is. This is the migration path.
     *   - [MARKER] prefix     -> Base64-decode then DPAPI-unprotect. If DPAPI fails (unavailable,
     *                            or the blob was protected under a different user/machine — DPAPI's
     *                            documented refusal behaviour), logs a warning and returns "" rather
     *                            than throwing or returning garbage/ciphertext to the caller.
     */
    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return stored
        if (!stored.startsWith(MARKER)) return stored // legacy plaintext — migration path
        val b64 = stored.substring(MARKER.length)
        return try {
            val cipherBytes = Base64.getDecoder().decode(b64)
            val plainBytes = com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(cipherBytes)
            String(plainBytes, Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "DPAPI decrypt failed (unavailable, or blob is from another user/machine)", t)
            ""
        }
    }
}
