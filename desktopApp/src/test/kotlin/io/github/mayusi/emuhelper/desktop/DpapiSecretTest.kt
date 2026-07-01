package io.github.mayusi.emuhelper.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [DpapiSecret] and its wiring into [FileAuthCredentials] / [FileCookieStore].
 * Guarded on `os.name` so the suite is meaningful on the real target (Windows, where DPAPI must
 * actually round-trip) but never fails CI/dev boxes running Linux/macOS — there it only asserts the
 * plaintext-fallback path is safe (no crash, no corruption) and that the marker/migration logic is
 * sound. All temp files live under `java.io.tmpdir` and are deleted in a `finally` block — nothing
 * touches the repo.
 */
class DpapiSecretTest {

    private val isWindows = System.getProperty("os.name")?.startsWith("Windows", ignoreCase = true) == true

    // ---- DpapiSecret round-trip ----------------------------------------------------------------

    @Test
    fun `encrypt then decrypt returns the original plaintext`() {
        val secret = "correct horse battery staple"
        val stored = DpapiSecret.encrypt(secret)
        val recovered = DpapiSecret.decrypt(stored)

        if (isWindows) {
            // On the real target, DPAPI must actually be used: the stored form must NOT equal the
            // plaintext (it's ciphertext) and must carry the marker, and it must decrypt back cleanly.
            assertTrue(stored.startsWith("dpapi:"), "encrypted value should carry the dpapi: marker")
            assertFalse(stored.contains(secret), "ciphertext must not contain the plaintext secret")
            assertEquals(secret, recovered)
        } else {
            // Non-Windows: DPAPI is unavailable, so encrypt() must fall back to plaintext rather than
            // crash, and decrypt() must hand it straight back (the migration/no-marker path).
            assertEquals(secret, stored)
            assertEquals(secret, recovered)
        }
    }

    @Test
    fun `empty string round-trips without marker or crash`() {
        val stored = DpapiSecret.encrypt("")
        assertEquals("", stored)
        assertEquals("", DpapiSecret.decrypt(""))
    }

    @Test
    fun `decrypt treats an unmarked legacy value as plaintext`() {
        // Simulates reading a credentials.properties written by a pre-DPAPI EmuHelper build.
        val legacyPlaintext = "my-old-plaintext-password"
        assertEquals(legacyPlaintext, DpapiSecret.decrypt(legacyPlaintext))
    }

    @Test
    fun `decrypt of a garbage dpapi-marked value does not throw and returns a safe fallback`() {
        // A corrupted or foreign-machine blob: must not throw, must not return garbage silently
        // mistaken for a real secret.
        val result = DpapiSecret.decrypt("dpapi:not-valid-base64-or-ciphertext!!")
        assertEquals("", result)
    }

    // ---- FileAuthCredentials wiring -------------------------------------------------------------

    @Test
    fun `FileAuthCredentials save then load recovers the password and never writes it in plaintext`() {
        val tempDir = createTempDirUnderSystemTemp("dpapi-creds")
        val propsFile = File(tempDir, "credentials.properties")
        try {
            val creds = FileAuthCredentials(propsFile)
            val password = "s3cr3t-ia-password"
            creds.save("user@example.com", password, remember = true)

            assertTrue(propsFile.exists())
            val onDisk = propsFile.readText()
            assertEquals("user@example.com", creds.savedEmailNow())

            if (isWindows) {
                assertFalse(onDisk.contains(password), "plaintext password must never hit disk on Windows")
                assertTrue(onDisk.contains("dpapi:"), "password line should carry the dpapi: marker")
            }

            // Round-trip via a fresh instance, simulating a process restart reading the same file.
            val reloaded = FileAuthCredentials(propsFile)
            kotlinx.coroutines.runBlocking {
                assertEquals(password, reloaded.getSavedPassword())
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `FileAuthCredentials migrates a legacy plaintext password file seamlessly`() {
        val tempDir = createTempDirUnderSystemTemp("dpapi-migrate")
        val propsFile = File(tempDir, "credentials.properties")
        try {
            // Hand-write a legacy (pre-DPAPI) properties file with a plaintext password.
            propsFile.writeText("email=legacy@example.com\npassword=legacy-plaintext-pw\nremember=true\n")

            val creds = FileAuthCredentials(propsFile)
            kotlinx.coroutines.runBlocking {
                assertEquals("legacy-plaintext-pw", creds.getSavedPassword())
            }

            // Re-saving (e.g. the next successful login) must upgrade the file to encrypted form.
            creds.save("legacy@example.com", "legacy-plaintext-pw", remember = true)
            val onDiskAfterResave = propsFile.readText()
            if (isWindows) {
                assertTrue(onDiskAfterResave.contains("dpapi:"), "resave should upgrade to the encrypted form")
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ---- FileCookieStore wiring ------------------------------------------------------------------

    @Test
    fun `FileCookieStore save then load recovers cookies and never writes them in plaintext`() {
        val tempDir = createTempDirUnderSystemTemp("dpapi-cookies")
        val cookieFile = File(tempDir, "cookies.txt")
        try {
            val store = FileCookieStore(cookieFile)
            val cookies = setOf("session=abc123; Domain=archive.org", "logged-in-sig=xyz789")
            store.save(cookies)

            val onDisk = cookieFile.readText()
            if (isWindows) {
                cookies.forEach { assertFalse(onDisk.contains(it), "cookie value must not appear in plaintext on disk") }
                assertTrue(onDisk.contains("dpapi:"))
            }

            val reloaded = FileCookieStore(cookieFile).load()
            assertEquals(cookies, reloaded)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun createTempDirUnderSystemTemp(prefix: String): File {
        val base = File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}")
        base.mkdirs()
        return base
    }
}
