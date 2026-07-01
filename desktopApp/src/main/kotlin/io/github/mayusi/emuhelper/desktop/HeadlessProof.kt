package io.github.mayusi.emuhelper.desktop

import io.github.mayusi.emuhelper.data.source.RemoteSource
import io.github.mayusi.emuhelper.di.PersistentCookieJar
import io.github.mayusi.emuhelper.platform.AppInfo
import io.github.mayusi.emuhelper.platform.Log
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest

/**
 * The original Phase-2 HEADLESS proof, kept for CI / regression: download a REAL Internet Archive file
 * on Windows entirely through the SHARED stack (the :shared engine + RemoteSource + PersistentCookieJar)
 * wired to the desktop platform seams. No UI. `Main` runs this when launched with `--headless`.
 *
 * This is the exact same flow the Phase-3a GUI uses under the hood (fetchFileList → mirrorUrls →
 * downloadFileSegmented to a plain java.io.File), so a green headless run is also a smoke test of the
 * engine path the window drives.
 *
 * Target: `testmp3testfile` — a long-standing PUBLIC (non-auth) IA item. We pick its smallest content
 * file (~110 KB) so the proof is fast + deterministic. Artifacts go to a temp dir and are deleted.
 */
private const val ITEM_URL = "https://archive.org/details/testmp3testfile"

/** Runs the headless proof end-to-end. Returns true on PASS. Prints a PASS/FAIL line. */
fun runHeadlessProof(): Boolean {
    Log.install(DesktopLog)

    val workDir = File(System.getProperty("java.io.tmpdir"), "emuhelper_desktop_proof").apply { mkdirs() }
    val cookieStore = FileCookieStore(File(workDir, "cookies.txt"))
    val authCreds = FileAuthCredentials(File(workDir, "credentials.properties"))

    val cookieJar = PersistentCookieJar(cookieStore)
    val client = desktopOkHttpClient(cookieJar)
    val remote = RemoteSource(
        okHttpClient = client,
        cookieJar = cookieJar,
        authStore = authCreds,
        appInfo = AppInfo(versionName = "desktop-phase3a-headless", debug = true),
    )

    val ok = runBlocking { runProof(remote, workDir) }

    try {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    } catch (_: Exception) {}

    return ok
}

private suspend fun runProof(remote: RemoteSource, workDir: File): Boolean {
    println("=== EmuHelper desktop headless download proof (Phase 2 regression) ===")
    println("Item: $ITEM_URL")

    val files = try {
        remote.fetchFileList(ITEM_URL)
    } catch (e: Exception) {
        println("FAIL: fetchFileList threw ${e.javaClass.simpleName}: ${e.message}")
        return false
    }
    if (files.isEmpty()) {
        println("FAIL: fetchFileList returned no files for the item.")
        return false
    }
    println("Scan: ${files.size} content file(s) found.")

    val target = files.filter { it.md5.isNotBlank() }.minByOrNull { it.size }
        ?: files.minByOrNull { it.size }!!
    println("Target file: '${target.name}'  size=${target.size}  md5=${target.md5.ifBlank { "(none)" }}")

    val mirrors = try {
        remote.mirrorUrls(target.identifier, target.name)
    } catch (e: Exception) {
        println("FAIL: mirrorUrls threw ${e.javaClass.simpleName}: ${e.message}")
        return false
    }
    val candidates = mirrors.ifEmpty { listOf(remote.buildDownloadUrl(target.identifier, target.name)) }
    println("Mirrors: ${candidates.size} candidate URL(s).")

    val destFile = File(workDir, target.filename)
    if (destFile.exists()) destFile.delete()

    val written = try {
        remote.downloadFileSegmented(
            candidateUrls = candidates,
            expectedSize = target.size,
            destFile = destFile,
            segments = 4,
            onProgress = { done, _ ->
                val pct = if (target.size > 0) (done * 100 / target.size) else 0
                print("\r  downloading… $done/${target.size} bytes (${pct}%)   ")
            },
            isCancelled = { false },
        )
    } catch (e: Exception) {
        println("\nFAIL: downloadFileSegmented threw ${e.javaClass.simpleName}: ${e.message}")
        e.printStackTrace()
        return false
    }
    println()

    if (!destFile.exists()) {
        println("FAIL: destination file does not exist after download.")
        return false
    }
    val actualSize = destFile.length()
    println("Downloaded: ${destFile.absolutePath}")
    println("  driver-returned bytes = $written")
    println("  on-disk size          = $actualSize  (expected ${target.size})")

    if (target.size > 0 && actualSize != target.size) {
        println("FAIL: size mismatch (got $actualSize, expected ${target.size}).")
        cleanup(destFile)
        return false
    }

    if (target.md5.isNotBlank()) {
        val actualMd5 = md5Of(destFile)
        val md5Ok = actualMd5.equals(target.md5, ignoreCase = true)
        println("  md5                   = $actualMd5  (expected ${target.md5})  ${if (md5Ok) "MATCH" else "MISMATCH"}")
        if (!md5Ok) { println("FAIL: MD5 mismatch."); cleanup(destFile); return false }
    } else {
        println("  md5                   = (item metadata had no md5 — size-only verification)")
    }

    cleanup(destFile)

    println()
    println("PASS: real Internet Archive file downloaded on Windows via the SHARED engine — " +
            "'${target.filename}' ($actualSize bytes${if (target.md5.isNotBlank()) ", MD5 verified" else ""}).")
    return true
}

private fun md5Of(file: File): String {
    val digest = MessageDigest.getInstance("MD5")
    file.inputStream().use { ins ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun cleanup(file: File) {
    try { if (file.exists()) file.delete() } catch (_: Exception) {}
}
