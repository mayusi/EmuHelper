<p align="center"><img src="docs/logo.png" alt="EmuHelper logo" width="120" height="120"></p>

<h1 align="center">EmuHelper</h1>

<p align="center"><strong>A configurable Android client for browsing and retrieving files from user-supplied web archive endpoints.</strong></p>

<p align="center">
  <a href="https://github.com/mayusi/EmuHelper/releases"><img alt="Release" src="https://img.shields.io/github/v/release/mayusi/EmuHelper?include_prereleases&sort=semver&label=release"></a>
  <a href="LICENSE"><img alt="License: MIT" src="https://img.shields.io/badge/license-MIT-blue"></a>
  <img alt="Min Android 10 (API 29)" src="https://img.shields.io/badge/Android-10%2B%20(API%2029)-3DDC84?logo=android&logoColor=white">
  <img alt="Built with Kotlin" src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white">
</p>

---

EmuHelper is a generic, **source-agnostic** download and transfer manager for Android. You point it at HTTP archive endpoints you configure, it reads their directory/metadata listings, lets you assemble and save selections, and fetches them with a fast multi-connection transfer engine — saving everything neatly into per-category folders on your device.

It ships with **no endpoints and no content**. The "bring-your-own-endpoints" model is the whole idea: EmuHelper is the machinery for browsing and retrieving large files; *what* you point it at is entirely up to you and supplied at build time.

> **Status: Early / Alpha — v0.3.2.** Still new and actively being built. Expect rough edges, and please file issues.

---

## Why use it

If you regularly pull large files down to a phone or tablet from archives you control, a browser download or a generic file manager quickly gets clumsy. EmuHelper is built for that specific job:

- **Maintain offline backups** of large files from archives and endpoints you control, without babysitting a browser.
- **Manage a personal library** of big downloads — build a list once, then fetch the whole batch on demand.
- **Pull big files fast** over your own connection using multiple parallel connections per file, with a safe cap so the device stays cool and responsive.
- **Stay organized automatically** — downloads land in tidy, per-category folders instead of one giant unsorted pile.
- **Reuse your selections** — save curated lists to portable files so you can re-fetch them later or move them between installs.

---

## ✨ Features

- **Configurable endpoints** — operate on your own catalog of HTTP archive endpoints; nothing is bundled.
- **Two ways to fetch** — build and save a selection set to retrieve later, or run an instant ad-hoc session and download right now.
- **Fast multi-connection transfers** — each file is pulled with range-segmented (parallel) connections, with mirror/host fail-over where available.
- **Safe connection cap** — a hard ceiling on total simultaneous connections keeps the device from overheating or thrashing, no matter how aggressive your settings.
- **Resilient writes** — downloads stream to a `.part` staging file and are validated for size, then published into the destination, so partial files never masquerade as complete ones. Already-complete files are detected and skipped.
- **Tidy per-category folders with smart reuse** — output is grouped into per-category subfolders, and folder matching is **case-insensitive**, so files merge into a folder another app already created (e.g. `psp`) instead of spawning a duplicate.
- **Optional archive extraction** — automatically expand downloaded `.zip` archives into the destination folder. **Off by default**; flip it on in Settings.
- **Background-friendly** — transfers continue via a foreground service while the app is backgrounded; pause, resume, retry, and cancel are all supported.
- **Transfer tuning** — sliders for connections-per-file and files-at-once, plus a one-tap **Max throughput** preset for fast Wi-Fi.
- **Built-in network speed test** — measure your real throughput (Mbit/s, MB/s, and an estimated minutes-per-GB) so you can tell whether slow downloads are your link or the source.
- **Device readout** — model, free/total RAM, CPU cores, and app version at a glance.
- **Guided file-staging helper** — a step-by-step assistant for moving files **you select from your own device storage** into another app's import folder, with on-screen import instructions. It only copies files you explicitly pick; it never reaches out anywhere on its own.
- **Modern UI** — Jetpack Compose, Material 3, light/dark theming, and animated navigation.

---

## 🛠️ Tech stack

- **Language:** Kotlin (Java 17)
- **UI:** Jetpack Compose · Material 3
- **DI:** Hilt (Dagger)
- **Navigation:** Navigation-Compose
- **Networking:** OkHttp
- **Storage:** DataStore Preferences · EncryptedSharedPreferences (security-crypto) · Storage Access Framework (SAF)
- **Serialization:** kotlinx.serialization
- **Min SDK:** 29 (Android 10) · **Target/Compile SDK:** 35

---

## 📲 Install

1. Download the latest APK from the [Releases](https://github.com/mayusi/EmuHelper/releases) page.
2. On your device, allow installs from unknown sources for your browser/file manager when prompted.
3. Open the APK to install. Requires **Android 10 (API 29)** or newer.

> The alpha builds install as a separate `.debug` package, so they won't collide with any future release build.

---

## 🔧 Build from source

This repository **contains no endpoint configuration**. The catalog lives in a single source file that is intentionally kept out of version control; you generate it from the committed template and supply your own endpoints.

```bash
# 1. Clone
git clone https://github.com/mayusi/EmuHelper.git
cd EmuHelper

# 2. Create the catalog from the committed template
cp app/src/main/java/io/github/mayusi/emuhelper/data/config/Catalog.kt.template \
   app/src/main/java/io/github/mayusi/emuhelper/data/config/Catalog.kt

# 3. Edit Catalog.kt and populate the endpoint map with the URLs you intend to use.
#    It compiles and runs with empty groups — entries appear only once you add endpoints.

# 4. Build
./gradlew :app:assembleDebug
```

Output APK: `app/build/outputs/apk/debug/app-debug.apk`. Or just open the project in **Android Studio** and run it.

---

## ⚙️ Configuration

`Catalog.kt` — the file mapping category keys to endpoint URLs — is **git-ignored by design** so that no endpoints are ever published here. The committed `Catalog.kt.template` ships the full structure with **empty lists**, so the project always compiles out of the box. Endpoints are **operator-supplied at build time** and are not part of this repository.

---

## ❓ FAQ

**Does it come with any download sources?**
No. EmuHelper ships with **no endpoints and no content**. You supply your own endpoints at build time.

**Why isn't `Catalog.kt` in the repository?**
Endpoints are operator-supplied. Keeping them out of version control is deliberate, so every clone starts from an empty, buildable project. You generate `Catalog.kt` from the committed template.

**Can I build it without setting up any endpoints?**
Yes. Clone, copy the template to `Catalog.kt`, and run `./gradlew :app:assembleDebug`. The app runs fine with empty lists — entries only appear once you add your own endpoints.

**What permissions does it need?**
Internet access, notification permission (Android 13+), and Storage Access Framework (SAF) access to a folder you pick. Nothing more.

---

## 🗺️ Roadmap

EmuHelper is actively developed. Recently shipped: in-app updates (with patch notes and one-tap install), download history, notification controls, an in-app theme toggle, and per-list management.

On the horizon:

- List sorting and search within large lists
- Smarter transfer scheduling and retry behavior
- Continued UI and accessibility polish

Have an idea? Open a [feature request](https://github.com/mayusi/EmuHelper/issues/new/choose).

---

## 🤝 Contributing & issues

This is an early alpha, and feedback is genuinely welcome. Bug reports, reproduction steps, and feature ideas all help — please open an [issue](https://github.com/mayusi/EmuHelper/issues). See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines, and [CHANGELOG.md](CHANGELOG.md) for the release history. If you'd like to send a fix, small focused pull requests are easiest to review.

---

## 📄 License

Released under the **MIT License** — see [LICENSE](LICENSE).

---

## Credits

Built by **mayusi**.

Development was assisted by an AI coding assistant (used for implementation, debugging, and documentation).
