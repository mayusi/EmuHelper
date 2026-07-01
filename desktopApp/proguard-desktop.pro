# =====================================================================================
# ProGuard rules for the :desktopApp Compose Desktop RELEASE distributable
# (compose.desktop.application.buildTypes.release.proguard — see desktopApp/build.gradle.kts).
#
# Compose Desktop only runs ProGuard on the RELEASE app image (createReleaseDistributable /
# packageReleaseMsi); the default createDistributable (debug) skips it entirely, which is why
# this wasn't caught until the release/CI .msi task was exercised.
#
# WHY THIS FILE EXISTS
# ---------------------
# ProGuard treats "83 unresolved references" as WARNINGS, and by default a Warning aborts the
# build ("Please correct the above warnings first") unless silenced with -dontwarn. All of the
# unresolved refs here are to OPTIONAL classes that legitimately do not exist on a desktop JVM
# classpath — they come from libraries (OkHttp, JNA) written to ALSO run on Android or to
# optionally support extra TLS providers that this app never bundles:
#
#   - OkHttp's Android/TLS-provider platform detection (okhttp3.internal.platform.*) references
#     android.* (Build, NetworkSecurityPolicy, util.Log, net.ssl.*, net.http.*), org.conscrypt.*,
#     org.bouncycastle.jsse.*, and org.openjsse.* — OkHttp probes for all of these at runtime via
#     reflection/Platform.get() and gracefully falls back to the plain desktop JDK TLS stack when
#     they're absent. None of them are on the classpath, by design (no Android runtime, no
#     Conscrypt/BouncyCastle/OpenJSSE artifacts bundled) — see the proguardReleaseJars log,
#     desktopApp/build/compose/logs/proguardReleaseJars/*-out.txt, for the exact 83-line list.
#   - JNA's optional platform-specific bits (Mac WindowUtils, X11 WindowUtils, the win32 COM
#     helpers) are for macOS/X11/COM features this app never touches (it only calls the Windows
#     Crypt32 DPAPI mapping via jna-platform's Crypt32Util) — kept -dontwarn defensively in case
#     a future jna-platform bump surfaces them as hard warnings instead of just reflection notes.
#
# None of this is a real problem: OkHttp/JNA are DESIGNED to probe-and-fallback. -dontwarn just
# tells ProGuard "these missing classes are expected, don't abort" — it does not affect what
# ships or how the app behaves at runtime.
#
# THE OTHER HALF: KEEP RULES
# ---------------------------
# -dontwarn only fixes the aborting build; it says nothing about SHRINKING/OBFUSCATION safety.
# JNA (Structure/Native/Callback) and kotlinx-serialization's generated $$serializer classes are
# both driven by reflection over field/method NAMES, which ProGuard's shrinker/obfuscator would
# otherwise happily rename or strip since it can't see those reflective call sites. If that
# happens the release .msi still BUILDS but CRASHES at runtime the first time it saves DPAPI
# credentials or reads/writes serialized settings/history/list JSON. The -keep rules below
# preserve exactly those reflection surfaces.
# =====================================================================================

# ---- -dontwarn: optional/missing platform classes (unresolved-reference warnings) ----

# OkHttp's Android platform-detection code paths — never present on a desktop JVM.
-dontwarn android.**

# OkHttp's optional extra TLS-provider integrations — none of these artifacts are bundled.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# JNA's non-Windows platform extras (macOS WindowUtils, X11, COM helpers) — this app only uses
# the Windows Crypt32 (DPAPI) mapping, but keep these silenced defensively.
-dontwarn com.sun.jna.**

# Common optional annotation package many libraries soft-reference (JSR-305 style annotations)
# that may not be on every classpath combination; silence defensively.
-dontwarn javax.annotation.**

# ---- -keep: JNA reflection safety (Crypt32Util / DPAPI credential encryption) ----
# JNA maps native functions and struct fields by REFLECTED name (Structure field order, Callback
# methods, Native.register). Shrinking/renaming any of this breaks DPAPI encryption/decryption
# of the saved Internet Archive password + cookies at runtime (DesktopSecurePrefs.kt).
-keep class com.sun.jna.** { *; }
-keep interface com.sun.jna.** { *; }
-keep class * extends com.sun.jna.Structure { *; }
-keep class * extends com.sun.jna.Structure$ByReference { *; }
-keep class * extends com.sun.jna.Structure$ByValue { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keep class com.sun.jna.platform.win32.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure {
    *;
}

# ---- -keep: kotlinx-serialization generated serializers ----
# Mirrors the official kotlinx.serialization ProGuard rules (and the existing app/proguard-rules.pro
# convention) so @Serializable settings/history/list models still serialize correctly after
# shrinking (DesktopSettingsStore / DesktopHistoryStore / DesktopListStore).
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** serializer(...);
}
-keepclasseswithmembers class ** {
    @kotlinx.serialization.Serializable <methods>;
}
-keep,includedescriptorclasses class io.github.mayusi.emuhelper.**$$serializer { *; }
-keepclassmembers class io.github.mayusi.emuhelper.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.mayusi.emuhelper.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- -keep: desktop app entry point ----
-keep class io.github.mayusi.emuhelper.desktop.MainKt {
    public static void main(java.lang.String[]);
}
-keep class io.github.mayusi.emuhelper.desktop.** { *; }

# ---- -keep: shared module (engine/model classes crossed via KMP jvm target) ----
-keepclassmembers class io.github.mayusi.emuhelper.data.model.** { *; }
-keepclassmembers class io.github.mayusi.emuhelper.data.source.** { *; }
