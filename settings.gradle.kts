pluginManagement {
    repositories {
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("com\\.google.*"); includeGroupByRegex("androidx.*") } }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "EmuHelper"
include(":app")
// :shared — Kotlin Multiplatform module holding the portable download engine core (commonMain),
// with android + jvm/desktop targets. :app consumes the android target; the jvm target proves the
// engine is desktop/Windows-ready. Foundation for the eventual desktop port.
include(":shared")
// :desktopApp — headless JVM app (Phase 2 of the Windows port). Depends on :shared's jvm target and
// proves a REAL Internet Archive download completes on Windows via the SHARED engine + RemoteSource,
// with a desktop FileSink (plain java.io.File), a file-backed cookie/auth store, and a stderr logger.
// No UI yet (that's Phase 3); this module is the end-to-end proof that the download stack is portable.
include(":desktopApp")
