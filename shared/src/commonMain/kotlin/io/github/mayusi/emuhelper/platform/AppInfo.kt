package io.github.mayusi.emuhelper.platform

/**
 * Per-platform build facts the portable download stack needs, replacing the Android-only
 * `io.github.mayusi.emuhelper.BuildConfig` reference inside RemoteSource. Android supplies this from
 * its generated BuildConfig; desktop supplies a literal. Kept tiny on purpose — RemoteSource only
 * gated some verbose logging on [debug].
 */
data class AppInfo(
    /** Marketing version string, e.g. "0.9.1". */
    val versionName: String,
    /** True in debug/dev builds — used only to gate extra-verbose download logging. */
    val debug: Boolean,
) {
    companion object {
        /** Safe default for unit tests / unconfigured runtimes. */
        val UNKNOWN = AppInfo(versionName = "0.0.0", debug = false)
    }
}
