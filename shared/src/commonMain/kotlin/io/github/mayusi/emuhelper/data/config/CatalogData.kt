package io.github.mayusi.emuhelper.data.config

/**
 * Immutable snapshot of the catalog data (IA source links, console metadata, colours, display order).
 *
 * This is the data class that flows through the remote-catalog pipeline. The baked-in
 * literals live in [Catalog.BAKED_IN]; the live value (initially == baked-in, replaced after
 * a successful remote merge) is held in [Catalog._currentData].
 */
data class CatalogData(
    val iaLinks: Map<String, List<String>>,
    val consoles: Map<String, Catalog.ConsoleDesc>,
    val consoleColors: Map<String, Long>,
    val displayOrder: List<String>
) {
    /** Folder name for a console key, e.g. "snes" -> "SNES". Falls back to cleaned key. */
    fun folderForConsole(consoleKey: String): String =
        consoles[consoleKey]?.folder ?: consoleKey.ifBlank { "Other" }.uppercase()

    /**
     * Best-effort reverse lookup: which console key owns a given source identifier.
     * Used only for older saved lists whose games predate the stored `console` field.
     */
    fun consoleForIdentifier(identifier: String): String? {
        if (identifier.isBlank()) return null
        for ((key, urls) in iaLinks) {
            if (urls.any {
                    it.contains("/download/$identifier/") ||
                    it.contains("/download/$identifier") ||
                    it.contains("/$identifier/")
                }) {
                return key
            }
        }
        return null
    }
}
