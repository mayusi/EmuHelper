# Releasing EmuHelper (local, manual)

Releases are cut **locally**, not by CI. Two hard reasons:

1. **Sources are private.** The real `Catalog.kt` (with the source list) is
   git-ignored. CI only has `Catalog.kt.template` (empty), so a CI-built app
   ships with **no sources**.
2. **Stable signing.** The in-app updater only works if every build is signed
   with the **same** key. The CI runner generates a **new random debug key**
   each run, so CI APKs can't update over a previously-installed build
   (`package conflicts with an existing package`).

So: build locally with the real catalog + a stable key, then upload.
The `.github/workflows/release.yml` workflow is **manual-only** and for smoke
testing — do not use its artifacts for a real release.

## Prerequisites
- Real `Catalog.kt` present at
  `shared/src/commonMain/kotlin/io/github/mayusi/emuhelper/data/config/Catalog.kt`
  (git-ignored; generate from the template + your endpoints if missing).
- Android SDK build-tools (aapt2, apksigner) on the machine.
- **WiX Toolset v3** for the `.msi` (`winget install WiXToolset.WiXToolset`),
  with `candle.exe` on PATH:
  `export PATH="$PATH:/c/Program Files (x86)/WiX Toolset v3.14/bin"`
- Signing key: the local debug keystore `~/.android/debug.keystore`
  (alias `androiddebugkey`, storepass `android`). This is the "debug-signed
  alpha" key the installed apps trust — keep using it so updates apply cleanly.
  Its cert SHA-256 is `21:E5:16:3F:...:70:1C`.

## Steps

1. **Bump version** in `app/build.gradle.kts` (`versionCode` + `versionName`)
   and desktop `APP_VERSION` in `DownloadController.kt` +
   `packageVersion` in `desktopApp/build.gradle.kts`. Keep them in sync.

2. **Verify green** (in-process compiler dodges the flaky Kotlin daemon):
   ```
   ./gradlew.bat :shared:allTests :app:testDebugUnitTest :desktopApp:test \
       --console=plain -Dkotlin.compiler.execution.strategy=in-process
   ```

3. **Build the APK** (debug variant = the stable-signed alpha):
   ```
   ./gradlew.bat :app:assembleDebug --console=plain -Dkotlin.compiler.execution.strategy=in-process
   # -> app/build/outputs/apk/debug/app-debug.apk  (auto-signed with ~/.android/debug.keystore)
   ```
   Confirm signer + sources:
   ```
   apksigner verify --print-certs app-debug.apk   # expect 21e5163f...
   ```

4b. **Build the Linux artifacts** (must run ON Linux — jpackage only builds the host OS's format).
   No Linux device needed: use **WSL2 Ubuntu on this machine** (has JDK 17; the Windows Android
   SDK is visible over `/mnt/c`). jpackage builds a Linux `.deb`; the portable app-image is the
   Steam Deck / SteamOS artifact (tar it).
   ```
   wsl.exe -d Ubuntu -- bash -lc 'export ANDROID_HOME="/mnt/c/Users/Naxte/AppData/Local/Android/Sdk"; \
     cd /mnt/c/Users/Naxte/Downloads/b/EmuHelperApp && \
     ./gradlew :desktopApp:packageReleaseDeb :desktopApp:createReleaseDistributable \
       --console=plain -Dkotlin.compiler.execution.strategy=in-process'
   # .deb  -> desktopApp/build/compose/binaries/main-release/deb/emuhelper_<ver>-1_amd64.deb
   # image -> desktopApp/build/compose/binaries/main-release/app/EmuHelper/  (tar -czf ... EmuHelper)
   ```
   Notes: use `:shared:jvmTest` (NOT `:shared:allTests`) in WSL — the Android unit-test variant
   needs the Android toolchain and fails under WSL. WSL gradle runs are slow first-time (5-12 min).
   Verify on Linux: `:desktopApp:test` (0 failures) + `./gradlew :desktopApp:run --args="--headless"`
   (real IA download, MD5-verified). GUI-on-screen still needs a real Deck.

5. **Build the MSI** (needs WiX on PATH):
   ```
   ./gradlew.bat :desktopApp:packageReleaseMsi --console=plain -Dkotlin.compiler.execution.strategy=in-process
   # -> desktopApp/build/compose/binaries/main-release/msi/EmuHelper-<ver>.msi
   ```
   (`createReleaseDistributable` runs ProGuard — rules live in
   `desktopApp/proguard-desktop.pro`. If you ever skip WiX, ship the portable
   app-image from `main-release/app/EmuHelper/` zipped instead.)

5. **Commit + tag** (as mayusi, no MCP, no co-author). Do NOT commit the real
   Catalog.kt or any keystore — both are git-ignored; double-check
   `git status` is clean of them.

6. **Create the release + upload BOTH artifacts:**
   ```
   sha256sum app-debug.apk EmuHelper-<ver>.msi   # put these in the notes
   gh release create v<ver> app-debug.apk EmuHelper-<ver>.msi \
       -R mayusi/EmuHelper --title "v<ver> — ..." --notes-file notes.md
   ```
   (or `gh release upload` / `gh release delete-asset` to fix an existing one.)

7. **Sanity check** the published assets are the real ones:
   ```
   gh release view v<ver> -R mayusi/EmuHelper --json assets --jq '[.assets[]|{name,size}]'
   ```

## The two bugs this process prevents
- Empty app (no sources) → because we build with the **real** Catalog.kt.
- "Package conflicts" on update → because we sign with the **same** debug key
  the installed app already trusts.
