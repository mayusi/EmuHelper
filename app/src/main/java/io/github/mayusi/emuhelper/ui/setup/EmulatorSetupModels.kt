package io.github.mayusi.emuhelper.ui.setup

import android.net.Uri

enum class Emulator(val displayName: String, val subtitle: String) {
    Eden("Eden", "Nintendo Switch emulator (Eden fork)"),
    Citron("Citron", "Nintendo Switch emulator (Citron fork)"),
    Sudachi("Sudachi", "Nintendo Switch emulator (Sudachi fork)");

    companion object {
        fun fromString(name: String): Emulator = values().firstOrNull {
            it.name.equals(name, ignoreCase = true)
        } ?: Eden
    }
}

data class StagedFile(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long
)

sealed class CopyState {
    object Idle : CopyState()
    data class Copying(val progress: Float) : CopyState()
    data class Done(val dest: String) : CopyState()
    data class Error(val msg: String) : CopyState()
}

fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}
