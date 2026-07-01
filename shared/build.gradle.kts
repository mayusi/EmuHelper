// =====================================================================================
// :shared — Kotlin Multiplatform module for EmuHelper's PORTABLE download engine core.
//
// PHASE 1a of the Windows port: this module holds the proven-pure engine logic
// (AdaptiveDownloadEngine.kt + MirrorScheduler.kt) in commonMain — no android.* deps, only
// kotlinx-coroutines-core. Two targets:
//   • androidTarget() — consumed by :app (the existing Android shell). No code change in :app:
//     the engine keeps its package io.github.mayusi.emuhelper.data.source, so every import in
//     RemoteSource/DownloadManager still resolves; the only change was internal -> public so the
//     types cross the module boundary.
//   • jvm()           — the desktop/Windows target. Proves the engine compiles + its full unit
//     suite runs on a plain JVM with NO Android SDK. Foundation for the eventual Compose-Desktop UI.
//
// Plugins are applied WITHOUT a version here — the versions are pinned once at the root build
// (apply false). Using alias() with a version in a sibling module would put two org.jetbrains.kotlin.*
// plugins on the build at the same version and clash with :app's Android Kotlin plugin classpath.
// =====================================================================================
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    // Phase 2: RemoteSource parses IA metadata with kotlinx-serialization-json. Applied WITHOUT a
    // version (same pattern as the other plugins) — the version is pinned once at the root build.
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvmToolchain(17)

    // Android target — what :app depends on.
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // Desktop / Windows JVM target — proves portability; runs the moved engine tests on a plain JVM.
    jvm()

    sourceSets {
        commonMain.dependencies {
            // The pure engine needs: coroutines core (Semaphore/Mutex/withLock).
            implementation(libs.kotlinx.coroutines.core)
            // Phase 2: the portable download STACK (RemoteSource + PersistentCookieJar) added to
            // commonMain needs OkHttp (the IA HTTP client + cookie jar — works on JVM desktop AND
            // Android) and kotlinx-serialization-json (IA metadata parsing). Both are JVM-flavoured
            // libraries; because :shared has ONLY JVM targets (androidTarget + jvm), commonMain can
            // depend on them and on JDK APIs (java.io.File, java.security.MessageDigest) directly.
            implementation(libs.okhttp)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            // kotlin("test") maps to JUnit4 on both the JVM and Android-unit-test runners — the same
            // assertEquals/assertTrue API the moved tests already use.
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "io.github.mayusi.emuhelper.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 29
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
