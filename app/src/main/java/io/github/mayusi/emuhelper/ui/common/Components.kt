package io.github.mayusi.emuhelper.ui.common

import androidx.compose.ui.graphics.Color
import java.io.File

fun formatSize(bytes: Long): String {
    var n = bytes.toDouble()
    for (unit in listOf("B", "KB", "MB", "GB")) {
        if (n < 1024.0) return "%.1f %s".format(n, unit)
        n /= 1024.0
    }
    return "%.1f TB".format(n)
}

fun formatSpeed(bytesPerSec: Double): String {
    val mbps = bytesPerSec / 1048576.0
    return if (mbps > 0.1) "%.1f MB/s".format(mbps) else "--"
}

fun formatEta(seconds: Double): String {
    if (seconds <= 0) return "--"
    val s = seconds.toLong()
    return if (s < 3600) "%dm %ds".format(s / 60, s % 60)
    else "%dh %dm".format(s / 3600, (s % 3600) / 60)
}

fun cleanGameName(filename: String): String {
    var name = File(filename).name
    name = Regex("\\.(chd|iso|rvz|nsp|xci|cia|nds|z64|zip|smc|sfc|gba|gbc|nes|gen|md|bin|cue|pbp|cso|wbfs|wad|3ds|rom|7z|wud|wux|gcm|gcz)\$", RegexOption.IGNORE_CASE).replace(name, "")
    name = Regex("^\\[NKit\\]\\s*", RegexOption.IGNORE_CASE).replace(name, "")
    name = Regex("^\\[RVZ\\]\\s*", RegexOption.IGNORE_CASE).replace(name, "")
    name = Regex("^\\[CHD\\]\\s*", RegexOption.IGNORE_CASE).replace(name, "")
    name = Regex("^\\[Redump\\]\\s*", RegexOption.IGNORE_CASE).replace(name, "")
    name = Regex("^\\[No-Intro\\]\\s*", RegexOption.IGNORE_CASE).replace(name, "")
    name = Regex("^\\[TOSEC\\]\\s*", RegexOption.IGNORE_CASE).replace(name, "")
    name = Regex("[_]+").replace(name, " ")
    name = Regex("\\s+").replace(name, " ")
    return name.trim()
}

fun getBaseGameName(name: String): String {
    var base = Regex("\\s+(I{2,}|IV|V|X|2|3|4|5|6|7|8|9|10)\\s*$", RegexOption.IGNORE_CASE).replace(name.trim(), "")
    base = Regex("\\s+Part\\s+\\d+\\s*$", RegexOption.IGNORE_CASE).replace(base, "")
    base = Regex("\\s+Episode\\s+\\d+\\s*$", RegexOption.IGNORE_CASE).replace(base, "")
    return base.trim()
}

/** Coarse region bucket parsed from a ROM filename's tags. */
enum class Region { USA, EUR, JPN, OTHER }

fun detectRegion(filename: String): Region {
    val s = filename.lowercase()
    return when {
        Regex("\\b(usa|\\(u\\)|world|ntsc-u)\\b").containsMatchIn(s) -> Region.USA
        Regex("\\b(europe|eur|\\(e\\)|pal|uk|australia)\\b").containsMatchIn(s) -> Region.EUR
        Regex("\\b(japan|jpn|jap|\\(j\\)|ntsc-j)\\b").containsMatchIn(s) -> Region.JPN
        else -> Region.OTHER
    }
}

fun getConsoleColor(consoleKey: String): Color {
    return when (consoleKey) {
        "ps1" -> Color(0xFF89b4fa)
        "ps2" -> Color(0xFFcba6f7)
        "psp" -> Color(0xFFf5c2e7)
        "wii", "wiiu", "3ds", "ds", "gcn", "gamecube" -> Color(0xFF94e2d5)
        "snes" -> Color(0xFFfab387)
        "genesis" -> Color(0xFFf9e2af)
        "dreamcast" -> Color(0xFF74c7ec)
        "saturn" -> Color(0xFF89dceb)
        "arcade" -> Color(0xFFf38ba8)
        "vita" -> Color(0xFFf5c2e7)
        else -> Color(0xFF6c7086)
    }
}
