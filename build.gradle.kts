plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    // :shared multi-platform module (pure download engine — commonMain + android/jvm targets).
    // Declared apply-false at the root so each Kotlin/Android plugin version lands on the root build
    // classpath exactly once; the :shared module then applies them WITHOUT a version to avoid the
    // classpath clash you get if two org.jetbrains.kotlin.* plugins request a version in sibling modules.
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.library) apply false
    // :desktopApp — plain Kotlin/JVM. Same apply-false-at-root pattern so the kotlin-jvm plugin
    // shares the single pinned Kotlin version on the build classpath (no sibling-module clash).
    alias(libs.plugins.kotlin.jvm) apply false
    // Compose Multiplatform — Phase 3a of the Windows port turns :desktopApp into a real Compose
    // Desktop GUI. Declared apply-false here (one version on the classpath); :desktopApp applies it.
    alias(libs.plugins.compose.multiplatform) apply false
}
