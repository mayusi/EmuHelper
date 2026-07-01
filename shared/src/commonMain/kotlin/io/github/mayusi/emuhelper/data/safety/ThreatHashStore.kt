package io.github.mayusi.emuhelper.data.safety

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * SHA-256 KNOWN-BAD HASH MATCHING — OFFLINE-FIRST.
 *
 * Loads a BUNDLED threat list from :shared resources (`/threat_hashes.json`) and answers exact and
 * 8-char-prefix hash lookups. The bundled list ships EMPTY-but-valid: a real curated list can be
 * shipped in a later release the same way EmuHelper bundles its other JSON. If the resource is
 * absent/empty, [checkHash] simply reports NOT_APPLICABLE — it NEVER crashes and NEVER blocks a scan.
 *
 * SCOPE NOTE (deliberately stubbed for a later task): there is NO network here. A remote feed refresh
 * (à la PcHelper's periodic hash-feed pull) could be layered on later — fetch a signed JSON with the
 * same [ThreatList] shape and hand it to [fromList] — but this engine performs zero HTTP by design
 * (no mandatory network; offline scanning must always work).
 */
class ThreatHashStore private constructor(
    private val fullHashes: Set<String>,
    private val prefixes: Set<String>,
    /** True when a list (even an empty bundled one) was successfully parsed. */
    val loaded: Boolean,
) {

    /** Wire shape of the bundled/remote threat list. */
    @Serializable
    data class ThreatList(
        val version: Int = 1,
        val fullHashes: List<String> = emptyList(),
        val prefixes: List<String> = emptyList(),
    )

    /** Outcome of a hash lookup. */
    enum class Match { NOT_APPLICABLE, CLEAN, HIT }

    /** True if this store actually has any entries to match against. */
    val hasEntries: Boolean get() = fullHashes.isNotEmpty() || prefixes.isNotEmpty()

    /**
     * Check a lowercase-hex [sha256].
     *  - [Match.NOT_APPLICABLE] if the store is empty / not loaded (no basis to judge),
     *  - [Match.HIT] on an exact full-hash match OR an 8-char-prefix match,
     *  - [Match.CLEAN] otherwise.
     */
    fun checkHash(sha256: String?): Match {
        if (sha256.isNullOrBlank() || !hasEntries) return Match.NOT_APPLICABLE
        val h = sha256.lowercase()
        if (h in fullHashes) return Match.HIT
        if (h.length >= PREFIX_LEN) {
            val p = h.substring(0, PREFIX_LEN)
            if (p in prefixes) return Match.HIT
        }
        return Match.CLEAN
    }

    companion object {
        /** Prefix-match width (chars of lowercase hex). */
        const val PREFIX_LEN = 8

        /** Classpath location of the bundled list (commonMain/resources -> classpath root). */
        const val RESOURCE_PATH = "/threat_hashes.json"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Empty, always-safe store (used when nothing loads). checkHash -> NOT_APPLICABLE. */
        fun empty(): ThreatHashStore = ThreatHashStore(emptySet(), emptySet(), loaded = false)

        /** Build a store from an in-memory list (used by tests and by a future remote feed). */
        fun fromList(list: ThreatList): ThreatHashStore = ThreatHashStore(
            fullHashes = list.fullHashes.map { it.lowercase() }.toSet(),
            prefixes = list.prefixes.map { it.lowercase() }.toSet(),
            loaded = true,
        )

        /**
         * Load the BUNDLED list from resources. Never throws — a missing/corrupt resource yields
         * [empty]. Reads via the classloader so it works on both the Android and JVM targets.
         */
        fun loadBundled(): ThreatHashStore {
            return try {
                val stream = ThreatHashStore::class.java.getResourceAsStream(RESOURCE_PATH)
                    ?: ThreatHashStore::class.java.classLoader?.getResourceAsStream(RESOURCE_PATH)
                    ?: return empty()
                val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
                if (text.isBlank()) return empty()
                fromList(json.decodeFromString(ThreatList.serializer(), text))
            } catch (_: Throwable) {
                empty()
            }
        }
    }
}
