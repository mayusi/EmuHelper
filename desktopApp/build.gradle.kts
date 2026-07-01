// =====================================================================================
// :desktopApp — Compose Desktop GUI for the EmuHelper Windows port (Phase 3a).
//
// Phase 2 proved the SHARED download stack (:shared engine + RemoteSource + PersistentCookieJar)
// downloads a real Internet Archive file on Windows through desktop platform seams (a file-backed
// cookie/auth store, a stderr logger, a plain java.io.File sink). Phase 3a turns that headless proof
// into a REAL Compose Desktop window with a working core flow:
//
//     browse consoles (from the shared Catalog) → scan a console → pick files → download to a folder,
//
// with a live progress UI wired to the same proven engine path. The old headless proof is still
// runnable via `runHeadlessProof(...)` (kept for CI / regression), but the app's entry point is now
// a Compose `application { Window { ... } }`.
//
// Depends on :shared — Gradle resolves :shared's `jvm` target variant from its KMP metadata, so this
// module links the exact same RemoteSource / engine / Catalog classes the JVM unit tests exercise.
//
// Since Kotlin 2.0 the Compose compiler ships as the `org.jetbrains.kotlin.plugin.compose` Gradle
// plugin, which Compose Multiplatform REQUIRES you to apply alongside `org.jetbrains.compose`
// (the latter no longer bundles the compiler). Both are pinned once apply-false at the root and
// applied here WITHOUT a version — same one-version-on-the-classpath pattern as :shared / :app.
// =====================================================================================
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    // Applied WITHOUT a version (pinned once apply-false at the root) — same pattern as :shared.
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    // Phase 3b: the desktop history store persists as JSON via @Serializable, so the desktop module
    // needs the kotlinx-serialization COMPILER plugin (the runtime lib alone doesn't generate the
    // serializer()). Same id-without-version pattern as :shared.
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvmToolchain(17)
}

sourceSets {
    // Explicit: the Compose Multiplatform desktop plugin's source-set wiring does not always chain
    // the default Java/Kotlin "test depends on main output" relationship the way a plain kotlin.jvm
    // module would, so DesktopArchiveTest (in src/test/kotlin) cannot otherwise resolve DesktopArchive
    // (in src/main/kotlin). Wiring it explicitly here is harmless even if the default relationship
    // turns out to already exist.
    test {
        compileClasspath += main.get().output
        runtimeClasspath += main.get().output
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // Windows DPAPI access (Crypt32Util.cryptProtectData/cryptUnprotectData) for at-rest encryption
    // of the saved Internet Archive password + cookies — see DesktopSecurePrefs.kt. jna-platform
    // bundles the Crypt32 mapping so no manual JNA interface declaration is needed.
    implementation(libs.jna.platform)

    // Compose Desktop — the full desktop runtime (Skia-backed UI, window, Material3, swing interop).
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    // Coroutines <-> Compose bridge (Dispatchers.Swing for UI-thread hops when needed).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    // Plain JUnit4 test source set (kotlin("test") maps to JUnit4 on the JVM, same pattern as
    // :shared's commonTest) — added for DesktopArchiveTest, which exercises the zip-slip guard's
    // pure path-safety function.
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        // Compose entry point (an `application { Window { ... } }`). See Main.kt.
        mainClass = "io.github.mayusi.emuhelper.desktop.MainKt"

        nativeDistributions {
            // Phase 4 packaging: a real Windows .msi installer (plus Dmg/Deb kept for the other OSes,
            // harmless on Windows — they simply aren't produced there). TargetFormat.Msi REQUIRES the
            // WiX Toolset (v3) on the build machine. WiX is NOT installed on the dev box here, so the
            // .msi is built in CI (windows-latest, which installs WiX) — see .github/workflows/release.yml.
            // Locally, `:desktopApp:createDistributable` (an app image + bundled JRE, no WiX needed)
            // proves this config is valid.
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)

            packageName = "EmuHelper"
            // MAJOR.MINOR.BUILD — each component must be in the WiX-legal range (0-255 / 0-65535).
            // Keep loosely in sync with the Android app versionName (:app is at 0.9.1); the desktop
            // port ships as 1.0.0. Bump this alongside a tagged desktop release.
            packageVersion = "1.0.0"

            // Public metadata surfaced by the installer / Add-or-Remove-Programs.
            description = "EmuHelper — desktop companion for the EmuHelper download engine"
            vendor = "mayusi"

            // If/when a real branded icon is added, drop an EmuHelper.ico in desktopApp/src/main/
            // resources (or a dedicated icons dir) and wire it here, e.g.:
            //     iconFile.set(project.file("src/main/resources/EmuHelper.ico"))
            // Until then the Compose default Windows icon is bundled automatically — not a blocker.

            // RELEASE-only ProGuard pass (createDistributable/debug does NOT run ProGuard — only
            // createReleaseDistributable / packageReleaseMsi do, which is why this was missed until
            // the CI .msi job exercised it). Without configurationFiles here, ProGuard's default
            // config treats the 83 "unresolved reference" notes from OkHttp's optional Android/TLS-
            // provider platform probing (org.conscrypt/org.bouncycastle/org.openjsse/android.*) and
            // JNA's non-Windows platform extras as WARNINGS, which abort the build ("Please correct
            // the above warnings first"). proguard-desktop.pro silences exactly those with -dontwarn
            // and adds -keep rules so JNA (Crypt32Util/DPAPI reflection) and kotlinx-serialization's
            // generated $$serializer classes survive shrinking/obfuscation intact. See that file's
            // header comment for the full rationale.
            buildTypes.release.proguard {
                configurationFiles.from(project.file("proguard-desktop.pro"))
            }

            windows {
                // ── CONSTANT upgrade UUID ─────────────────────────────────────────────────────
                // This GUID identifies the product line across versions. WiX uses it so a newer
                // .msi UPGRADES an existing install in place instead of installing side-by-side.
                //   *** NEVER CHANGE THIS VALUE. ***
                // Changing it would make future installers unable to see/replace older installs,
                // leaving users with two copies of EmuHelper. Generated once for Phase 4.
                upgradeUuid = "e381761c-5a11-4835-b867-53c29eb0eac1"

                // Start-menu folder the shortcut lands in.
                menuGroup = "EmuHelper"

                // Per-user install → lands under %LOCALAPPDATA%, needs NO admin rights / UAC prompt.
                // Matches the project's "debug-signed alpha, low-friction install" convention.
                perUserInstall = true

                // Let the user pick the install directory during setup.
                dirChooser = true

                // Create Start-menu + desktop shortcuts.
                shortcut = true
                menu = true

                // GUI app → no console window attached at launch.
                console = false
            }
        }
    }
}
