package io.github.mayusi.emuhelper.desktop

import io.github.mayusi.emuhelper.platform.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Desktop at-rest encryption for the two secrets [FileAuthCredentials] and [FileCookieStore] persist
 * to disk (the Internet Archive password + session cookies). This is the desktop analogue of the
 * Android side's AES-256/Keystore-backed EncryptedSharedPreferences — desktop must not be weaker, so
 * plaintext-on-disk is not acceptable for these two values on ANY supported OS.
 *
 * Per-OS scheme (chosen at [encrypt] time; [decrypt] dispatches on the stored MARKER, NOT the current
 * OS, so a value survives even if the environment changed since it was written):
 *
 *   • Windows — DPAPI (marker "dpapi:"). `CryptProtectData`/`CryptUnprotectData` (Win32 Crypt32),
 *     scope = CURRENT_USER, via jna-platform's [com.sun.jna.platform.win32.Crypt32Util]. The blob can
 *     only be decrypted by the same Windows user on the same machine. UNCHANGED from the original.
 *
 *   • Linux — layered, best-available:
 *       Tier 1 (marker "lsec:") — OS keyring via the freedesktop Secret Service. We store a random
 *         32-byte AES master key IN the keyring (once, under a fixed schema) by shelling out to the
 *         `secret-tool` CLI (part of libsecret; standard on GNOME / KDE / SteamOS-KDE = KWallet),
 *         then AES-256-GCM-encrypt the actual secret with that key. Only ONE keyring entry is used
 *         regardless of how many secrets are stored, and the public [encrypt]/[decrypt] shape stays
 *         "String -> String". Present on SteamOS Desktop mode; falls through when unavailable.
 *       Tier 2 (marker "lkey:") — app-key AES-256-GCM fallback. When the keyring / secret-tool /
 *         D-Bus session is unavailable (e.g. SteamOS Game Mode may have no session bus), we generate
 *         a random 32-byte key in a 0600-perm file under the app config dir (~/.emuhelper/.seckey) and
 *         AES-256-GCM with it. Weaker than the keyring (the key is on disk, owner-readable) but far
 *         better than plaintext — a casual reader of credentials.properties can't recover the secret.
 *         Documented trade-off.
 *       Tier 3 — plaintext fallback (no marker) if BOTH of the above fail. Never throws.
 *
 *   • macOS / other — no keyring wiring here; falls to the Tier-2 app-key AES path (still better than
 *     plaintext), then Tier-3 plaintext. (A dedicated Keychain path could be added later.)
 *
 * Every non-plaintext value is a MARKER-prefixed, Base64 payload. An unmarked value is legacy
 * plaintext (written by an older EmuHelper build, or every tier failed at write time) — read as-is
 * once (the migration path), then re-persisted in the best available encrypted form on the next save.
 *
 * Defensive by design: this object NEVER throws out of [encrypt] or [decrypt]. [encrypt] degrades to
 * a weaker tier and ultimately to returning the plaintext unchanged (unmarked) with a warning logged,
 * so the caller always gets a working value. [decrypt] returns "" for a marked value it cannot decrypt
 * on this machine (keyring entry gone, keyfile missing, or a foreign-OS blob) — same contract as the
 * DPAPI path: forces a clean re-login rather than crashing or handing back garbage.
 */
internal object DpapiSecret {
    private const val TAG = "DpapiSecret"

    /** Prefix marking a Windows DPAPI-encrypted Base64 ciphertext. UNCHANGED. */
    private const val MARKER_DPAPI = "dpapi:"

    /** Prefix marking a Linux Secret-Service (Tier 1) value: AES-256-GCM under the keyring master key. */
    private const val MARKER_LSEC = "lsec:"

    /** Prefix marking an app-key (Tier 2) value: AES-256-GCM under the on-disk 0600 keyfile. */
    private const val MARKER_LKEY = "lkey:"

    // ---- AES-GCM parameters (JDK-built-in crypto; no dependency) --------------------------------
    private const val AES = "AES"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val AES_KEY_BYTES = 32   // 256-bit key
    private const val GCM_IV_BYTES = 12    // 96-bit IV (recommended for GCM)
    private const val GCM_TAG_BITS = 128   // 128-bit auth tag

    private val secureRandom = SecureRandom()

    // ---- OS detection (once) --------------------------------------------------------------------
    private val osName: String = System.getProperty("os.name").orEmpty()

    private val isWindows: Boolean = osName.startsWith("Windows", ignoreCase = true)

    private val isLinux: Boolean = osName.contains("linux", ignoreCase = true)

    /**
     * True once we've confirmed jna-platform's Crypt32 mapping actually loads on this machine.
     * Checked lazily (not just [isWindows]) because the native Crypt32 mapping could still fail to
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

    // =============================================================================================
    // Public API — signatures IDENTICAL to the original (callers untouched).
    // =============================================================================================

    /**
     * Encrypts [plaintext] and returns a MARKER-prefixed, Base64 string suitable for writing to disk.
     * Windows -> DPAPI ("dpapi:"). Linux -> keyring AES-GCM ("lsec:"), else app-key AES-GCM ("lkey:").
     * Other OSes -> app-key AES-GCM ("lkey:"). If every tier fails, returns [plaintext] UNCHANGED (no
     * marker) with a warning — callers must never crash or lose the value because encryption wasn't
     * possible. Empty input short-circuits to "".
     */
    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return plaintext

        // Windows: DPAPI, exactly as before.
        if (isWindows) {
            if (!dpapiAvailable) return plaintext
            return try {
                val cipherBytes = com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(
                    plaintext.toByteArray(Charsets.UTF_8)
                )
                MARKER_DPAPI + Base64.getEncoder().encodeToString(cipherBytes)
            } catch (t: Throwable) {
                Log.w(TAG, "DPAPI encrypt failed, storing value as plaintext", t)
                plaintext
            }
        }

        // Linux: try the OS keyring (Tier 1) first.
        if (isLinux) {
            val keyringKey = try {
                secretServiceMasterKey()
            } catch (t: Throwable) {
                Log.w(TAG, "Secret Service unavailable, falling back to app-key encryption", t)
                null
            }
            if (keyringKey != null) {
                try {
                    return MARKER_LSEC + aesGcmEncryptToBase64(plaintext, keyringKey)
                } catch (t: Throwable) {
                    Log.w(TAG, "Keyring AES-GCM encrypt failed, falling back to app-key encryption", t)
                }
            }
        }

        // Tier 2 (Linux keyring absent, or macOS/other): app-key AES-GCM with the on-disk 0600 keyfile.
        try {
            val appKey = appKeyfileKey()
            if (appKey != null) {
                return MARKER_LKEY + aesGcmEncryptToBase64(plaintext, appKey)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "App-key AES encrypt failed, storing value as plaintext", t)
        }

        // Tier 3: never throw — hand back the plaintext (unmarked). Weakest, last-resort fallback.
        Log.w(TAG, "No at-rest encryption available on this host; storing value as plaintext")
        return plaintext
    }

    /**
     * Decrypts a value previously produced by [encrypt]. Dispatches on the MARKER prefix (NOT the
     * current OS), so a value written under one scheme is decrypted with that same scheme:
     *   - no marker    -> legacy/plaintext (or all tiers failed at write time); returned as-is (migration).
     *   - "dpapi:"     -> Base64-decode then DPAPI-unprotect (Windows only).
     *   - "lsec:"      -> AES-GCM-decrypt under the keyring master key.
     *   - "lkey:"      -> AES-GCM-decrypt under the on-disk app keyfile.
     * A marked value that cannot be decrypted on this machine (keyring entry gone, keyfile missing,
     * corrupt/foreign blob, or a Linux blob opened on Windows and vice-versa) logs a warning and
     * returns "" rather than throwing or handing back garbage — same contract as the DPAPI path,
     * forcing a clean re-login.
     */
    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return stored

        when {
            stored.startsWith(MARKER_DPAPI) -> {
                val b64 = stored.substring(MARKER_DPAPI.length)
                return try {
                    val cipherBytes = Base64.getDecoder().decode(b64)
                    val plainBytes = com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(cipherBytes)
                    String(plainBytes, Charsets.UTF_8)
                } catch (t: Throwable) {
                    Log.w(TAG, "DPAPI decrypt failed (unavailable, or blob is from another user/machine)", t)
                    ""
                }
            }

            stored.startsWith(MARKER_LSEC) -> {
                val payload = stored.substring(MARKER_LSEC.length)
                return try {
                    val key = secretServiceMasterKey()
                        ?: throw IllegalStateException("Secret Service master key unavailable")
                    aesGcmDecryptFromBase64(payload, key)
                } catch (t: Throwable) {
                    Log.w(TAG, "Keyring (lsec) decrypt failed (keyring entry gone, or foreign blob)", t)
                    ""
                }
            }

            stored.startsWith(MARKER_LKEY) -> {
                val payload = stored.substring(MARKER_LKEY.length)
                return try {
                    val key = appKeyfileKey(createIfMissing = false)
                        ?: throw IllegalStateException("App keyfile unavailable")
                    aesGcmDecryptFromBase64(payload, key)
                } catch (t: Throwable) {
                    Log.w(TAG, "App-key (lkey) decrypt failed (keyfile missing/changed, or corrupt blob)", t)
                    ""
                }
            }

            else -> return stored // legacy plaintext — migration path
        }
    }

    // =============================================================================================
    // AES-256-GCM helpers (pure JDK crypto — unit-testable on any OS via the internal entry points
    // below). IV is random per encrypt and PREPENDED to the ciphertext; the whole thing is Base64'd.
    // =============================================================================================

    /** AES-256-GCM encrypt [plaintext] with [key32] (32 bytes). Returns Base64(IV || ciphertext||tag). */
    private fun aesGcmEncryptToBase64(plaintext: String, key32: ByteArray): String {
        require(key32.size == AES_KEY_BYTES) { "AES key must be $AES_KEY_BYTES bytes" }
        val iv = ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key32, AES), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ct, 0, out, iv.size, ct.size)
        return Base64.getEncoder().encodeToString(out)
    }

    /** AES-256-GCM decrypt a Base64(IV || ciphertext||tag) produced by [aesGcmEncryptToBase64]. */
    private fun aesGcmDecryptFromBase64(b64: String, key32: ByteArray): String {
        require(key32.size == AES_KEY_BYTES) { "AES key must be $AES_KEY_BYTES bytes" }
        val all = Base64.getDecoder().decode(b64)
        require(all.size > GCM_IV_BYTES) { "ciphertext too short" }
        val iv = all.copyOfRange(0, GCM_IV_BYTES)
        val ct = all.copyOfRange(GCM_IV_BYTES, all.size)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key32, AES), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    // ---- Test seams: exercise the Tier-2 (app-key) AES-GCM path with an explicit key on ANY OS. ----
    // These are pure-JDK-crypto entry points so DpapiSecretTest can round-trip the "lkey:" scheme
    // without a keyring. They are NOT used by the production dispatch above (which resolves the key
    // itself); they exist purely so the crypto + marker logic is unit-testable off-Linux.

    /** Produce an "lkey:"-marked value from [plaintext] using an explicit [key32]. For tests. */
    internal fun encryptWithAppKeyForTest(plaintext: String, key32: ByteArray): String =
        if (plaintext.isEmpty()) plaintext else MARKER_LKEY + aesGcmEncryptToBase64(plaintext, key32)

    /** Decrypt an "lkey:"-marked value with an explicit [key32], never throwing. "" on failure. For tests. */
    internal fun decryptWithAppKeyForTest(stored: String, key32: ByteArray): String {
        if (stored.isEmpty()) return stored
        if (!stored.startsWith(MARKER_LKEY)) return stored
        return try {
            aesGcmDecryptFromBase64(stored.substring(MARKER_LKEY.length), key32)
        } catch (t: Throwable) {
            ""
        }
    }

    /** Generate a fresh random 32-byte AES-256 key. Exposed for tests. */
    internal fun newRandomKeyForTest(): ByteArray =
        ByteArray(AES_KEY_BYTES).also { secureRandom.nextBytes(it) }

    // =============================================================================================
    // Tier 1: Secret Service master key via the `secret-tool` CLI.
    // =============================================================================================
    //
    // We store ONE random 32-byte AES key (Base64) in the keyring under a fixed schema
    //   attributes: application=emuhelper, key=master
    // then AES-GCM every actual secret with it. First-run lookup misses -> we transparently CREATE
    // and store a fresh key. All calls have short timeouts and tolerate a missing binary / non-zero
    // exit / no D-Bus session (returns null -> caller falls to Tier 2). Never hangs, never throws
    // out to the public API (the caller wraps this in try/catch too).

    private const val ST_ATTR_APP = "emuhelper"
    private const val ST_ATTR_KEY = "master"
    private const val ST_TIMEOUT_SECONDS = 8L

    /** In-process cache so we don't shell out to secret-tool on every single encrypt/decrypt call. */
    @Volatile private var cachedKeyringKey: ByteArray? = null

    /**
     * Returns the 32-byte keyring master key, creating+storing it on first use. Returns null if the
     * keyring is unavailable (secret-tool missing, no D-Bus session, non-zero exit, timeout) so the
     * caller can fall through to Tier 2. Never blocks indefinitely.
     */
    private fun secretServiceMasterKey(): ByteArray? {
        cachedKeyringKey?.let { return it }
        synchronized(this) {
            cachedKeyringKey?.let { return it }

            // 1) Try to look up an existing key.
            val looked = secretToolLookup()
            if (looked != null) {
                val decoded = decodeKeyOrNull(looked)
                if (decoded != null) {
                    cachedKeyringKey = decoded
                    return decoded
                }
                // A malformed stored value — fall through and overwrite it with a fresh valid key.
                Log.w(TAG, "Keyring held a malformed master key; regenerating")
            }

            // 2) Missing (or malformed): generate a fresh key and store it. A store failure means the
            //    keyring genuinely isn't usable -> return null so we fall to Tier 2.
            val fresh = ByteArray(AES_KEY_BYTES).also { secureRandom.nextBytes(it) }
            val freshB64 = Base64.getEncoder().encodeToString(fresh)
            return if (secretToolStore(freshB64)) {
                cachedKeyringKey = fresh
                fresh
            } else {
                null
            }
        }
    }

    private fun decodeKeyOrNull(b64: String): ByteArray? = try {
        val k = Base64.getDecoder().decode(b64.trim())
        if (k.size == AES_KEY_BYTES) k else null
    } catch (t: Throwable) {
        null
    }

    /** `secret-tool lookup application emuhelper key master` -> stdout (the stored secret) or null. */
    private fun secretToolLookup(): String? {
        val out = runProcess(
            listOf("secret-tool", "lookup", "application", ST_ATTR_APP, "key", ST_ATTR_KEY),
            stdin = null
        ) ?: return null
        if (out.exitCode != 0) return null // non-zero on a miss (no such secret) or on any error
        val s = out.stdout.trim()
        return s.ifEmpty { null }
    }

    /**
     * `secret-tool store --label=EmuHelper application emuhelper key master` reading the secret from
     * stdin. Returns true on exit 0. secret-tool prompts for an unlock if the collection is locked;
     * in a headless/no-session context it fails fast (non-zero / no binary) rather than blocking here
     * because we cap the wait with a timeout.
     */
    private fun secretToolStore(secretB64: String): Boolean {
        val out = runProcess(
            listOf(
                "secret-tool", "store", "--label=EmuHelper IA secret key",
                "application", ST_ATTR_APP, "key", ST_ATTR_KEY
            ),
            stdin = secretB64
        ) ?: return false
        return out.exitCode == 0
    }

    private data class ProcResult(val exitCode: Int, val stdout: String)

    /**
     * Runs [command], optionally feeding [stdin], capturing stdout, with a hard [ST_TIMEOUT_SECONDS]
     * timeout. Returns null if the binary is missing (IOException) or the wait times out (process
     * destroyed) — both mean "keyring path not usable, fall through". Never throws.
     */
    private fun runProcess(command: List<String>, stdin: String?): ProcResult? {
        var process: Process? = null
        return try {
            process = ProcessBuilder(command)
                .redirectErrorStream(false)
                .start()
            // Feed stdin (the secret) then close it, so secret-tool stops reading.
            process.outputStream.use { os ->
                if (stdin != null) os.write(stdin.toByteArray(Charsets.UTF_8))
            }
            val finished = process.waitFor(ST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                Log.w(TAG, "secret-tool timed out after ${ST_TIMEOUT_SECONDS}s; treating keyring as unavailable")
                return null
            }
            val stdout = process.inputStream.readBytes().toString(Charsets.UTF_8)
            ProcResult(process.exitValue(), stdout)
        } catch (t: Throwable) {
            // IOException => binary not on PATH; anything else => treat keyring as unavailable.
            process?.destroyForcibly()
            null
        }
    }

    // =============================================================================================
    // Tier 2: app-key AES-GCM using a random key in a 0600-perm keyfile under the app config dir.
    // =============================================================================================
    //
    // Location: ~/.emuhelper/.seckey  (falls back to java.io.tmpdir/.emuhelper if $HOME is unset).
    // The key is a raw 32-byte AES-256 key, Base64-encoded in the file. On POSIX filesystems the file
    // is chmod 0600 and the parent dir 0700 so only the owner can read it. Better than plaintext (a
    // casual reader of credentials.properties can't decrypt the secret) but weaker than the keyring
    // (the key sits on disk next to the ciphertext) — this is the documented Tier-2 trade-off.

    private const val APP_DIR_NAME = ".emuhelper"
    private const val KEYFILE_NAME = ".seckey"

    /** In-process cache to avoid re-reading the keyfile on every call. */
    @Volatile private var cachedAppKey: ByteArray? = null

    private fun appConfigDir(): File {
        val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("java.io.tmpdir")
        return File(home, APP_DIR_NAME)
    }

    private fun keyfile(): File = File(appConfigDir(), KEYFILE_NAME)

    /**
     * Returns the 32-byte app key, reading the 0600 keyfile (and creating it on first use when
     * [createIfMissing] is true). Returns null if the key cannot be read/created (I/O failure) so the
     * caller can fall to Tier 3. [decrypt] passes createIfMissing=false: if the keyfile is gone the
     * "lkey:" blob is undecryptable and must return "" (not silently mint a new, wrong key).
     */
    private fun appKeyfileKey(createIfMissing: Boolean = true): ByteArray? {
        cachedAppKey?.let { return it }
        synchronized(this) {
            cachedAppKey?.let { return it }
            val kf = keyfile()

            // Read an existing key.
            if (kf.exists()) {
                val decoded = try {
                    decodeKeyOrNull(kf.readText())
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to read app keyfile", t)
                    null
                }
                if (decoded != null) {
                    cachedAppKey = decoded
                    return decoded
                }
                // Corrupt keyfile. If we may create, overwrite it; else give up (can't decrypt old blobs).
                if (!createIfMissing) return null
                Log.w(TAG, "App keyfile was corrupt; regenerating (existing lkey: blobs become undecryptable)")
            } else if (!createIfMissing) {
                return null
            }

            // Create a fresh key and persist it 0600.
            val fresh = ByteArray(AES_KEY_BYTES).also { secureRandom.nextBytes(it) }
            return if (writeKeyfile(kf, fresh)) {
                cachedAppKey = fresh
                fresh
            } else {
                null
            }
        }
    }

    /**
     * Writes [key32] (Base64) to [kf], creating the parent dir, and locks perms to owner-only
     * (dir 0700, file 0600) on POSIX filesystems. Returns true on success. On a non-POSIX FS the
     * setPosixFilePermissions calls are skipped/ignored (best-effort). Never throws.
     */
    private fun writeKeyfile(kf: File, key32: ByteArray): Boolean {
        return try {
            val dir = kf.parentFile
            if (dir != null && !dir.exists()) dir.mkdirs()
            // Lock down the directory first (owner rwx only) where the FS supports it.
            trySetPosixPerms(
                dir,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE
                )
            )
            kf.writeText(Base64.getEncoder().encodeToString(key32))
            // Lock down the keyfile itself (owner rw only).
            trySetPosixPerms(
                kf,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            )
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to write app keyfile", t)
            false
        }
    }

    private fun trySetPosixPerms(file: File?, perms: Set<PosixFilePermission>) {
        if (file == null) return
        try {
            Files.setPosixFilePermissions(file.toPath(), perms)
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystem (e.g. Windows) — nothing to do; best-effort.
        } catch (_: Throwable) {
            // Any other perm-set failure is non-fatal; the key is still written.
        }
    }
}
