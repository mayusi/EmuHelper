<p align="center"><img src="docs/logo.png" alt="EmuHelper logo" width="120" height="120"></p>

<h1 align="center">EmuHelper</h1>

<p align="center"><strong>An Android & Windows download manager for the Internet Archive — browse large collections, build lists, and pull files fast over multiple connections.</strong></p>

<p align="center">
  <a href="https://github.com/mayusi/EmuHelper/releases"><img alt="Release" src="https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fmayusi%2FEmuHelper%2Fbadges%2Frelease.json&cacheSeconds=3600"></a>
  <a href="https://github.com/mayusi/EmuHelper/releases"><img alt="Downloads" src="https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fmayusi%2FEmuHelper%2Fbadges%2Fdownloads.json&cacheSeconds=3600"></a>
  <a href="LICENSE"><img alt="License: MIT" src="https://img.shields.io/badge/license-MIT-blue"></a>
  <img alt="Min Android 10 (API 29)" src="https://img.shields.io/badge/Android-10%2B%20(API%2029)-3DDC84?logo=android&logoColor=white">
  <a href="https://github.com/mayusi/EmuHelper/releases"><img alt="Windows" src="https://img.shields.io/badge/Windows-.msi%20%2B%20portable-0078D6?logo=windows&logoColor=white"></a>
  <img alt="Built with Kotlin" src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white">
  <a href="https://discord.gg/jEnMYW5YfE"><img alt="Discord" src="https://img.shields.io/badge/Discord-Join-5865F2?logo=discord&logoColor=white"></a>
</p>

---

<p align="center"><strong>✅ Ready to go — just install, sign in, and download. No setup required.</strong></p>

---

EmuHelper is a download manager for the [Internet Archive](https://archive.org), built for **Android and now Windows too**. **Grab the app, sign in with your free Internet Archive account, and you're downloading in under a minute** — the collections are already wired up, so there's nothing to configure. It browses those collections, lets you assemble and save selections, and fetches them with a fast multi-connection transfer engine that drops everything into tidy per-category folders.

> **Status: v1.0.0.** EmuHelper is now a native Windows desktop app as well as Android — same download engine and catalog, ships as a `.msi` installer or a portable app-image with a bundled Java runtime, so there's nothing to install first. PS4 joins the supported consoles, and both apps now run an on-device, best-effort **security scan** on downloaded files before you rely on them. Under the hood, this still builds on the RAR extraction, multi-mirror engine, and Xbox/Xbox 360 support from earlier releases. Still actively being built — expect rough edges, and please file issues.

> *Builders:* the source repository itself is content-free by design — it ships with no collections, generated from an empty template. The prebuilt release is the ready-to-use one; if you build from source you supply your own. See [Build from source](#-build-from-source).

---

## Why use it

Pulling large files from the Internet Archive down to a phone, tablet, or handheld through a browser is slow and clumsy — single-connection downloads, no way to queue a batch, and files scattered everywhere. EmuHelper is built for exactly that job:

- **Pull big files fast** — each download uses multiple parallel connections with automatic mirror fail-over, with a safe cap so the device stays cool and responsive.
- **Build a library, fetch on demand** — assemble a list once, then download the whole batch when you're ready.
- **Stay organized automatically** — downloads land in tidy, per-category folders instead of one giant unsorted pile.
- **Pick up where you left off** — resume interrupted batches, re-download from history, and skip files you already have.
- **Reuse and share selections** — save lists to portable files (or import one from a URL) to re-fetch later or move between installs.

---

## ✨ Features

- **Configurable collections** — you set up which Internet Archive collections it reads from; none are bundled with the project. PS4 is now among the supported consoles, alongside the existing lineup.
- **Sign in with your own account** — uses your free Internet Archive login. New here? The app has a built-in, guided "create an account" flow. On Windows, your login is encrypted at rest with Windows DPAPI, so only your Windows account can decrypt it.
- **Security scan (best-effort)** — after a download finishes, EmuHelper runs an offline, on-device safety check: known-bad hash lookup, real-vs-claimed file type, archive safety (zip-slip / zip-bomb patterns), and Windows-executable heuristics. You get a colored safety badge and details, and can move flagged files to Quarantine. **This is the best on-device check we could build — not a guarantee.** No offline scanner catches everything, but it's better than nothing, and it never auto-deletes or blocks a download for you.
- **Two ways to fetch** — build and save a selection set to retrieve later, or run an instant ad-hoc session and download right now.
- **Fast multi-connection transfers** — each file is pulled with range-segmented (parallel) connections, with mirror/host fail-over where available.
- **Safe connection cap** — a hard ceiling on total simultaneous connections keeps the device from overheating or thrashing, no matter how aggressive your settings.
- **Resilient writes** — downloads stream to a `.part` staging file and are validated for size, then published into the destination, so partial files never masquerade as complete ones. Already-complete files are detected and skipped.
- **Tidy per-category folders with smart reuse** — output is grouped into per-category subfolders, and folder matching is **case-insensitive**, so files merge into a folder another app already created (e.g. `psp`) instead of spawning a duplicate.
- **Optional archive extraction** — automatically expand downloaded `.zip` archives into the destination folder. **Off by default**; flip it on in Settings.
- **Background-friendly** — transfers continue via a foreground service while the app is backgrounded; pause, resume, retry, and cancel are all supported.
- **Transfer tuning** — sliders for connections-per-file and files-at-once, plus a one-tap **Max throughput** preset for fast Wi-Fi.
- **Built-in network speed test** — measure your real throughput (Mbit/s, MB/s, and an estimated minutes-per-GB) so you can tell whether slow downloads are your link or the source.
- **Download history & re-download** — see what you've fetched, re-download an item, or copy a filename; orphaned partial files are cleaned up automatically.
- **Source health check** — ping your configured collections to see at a glance which are reachable.
- **Device readout & local error log** — model, free/total RAM, CPU cores, app version, and an on-device (never-uploaded) error log you can view or share for troubleshooting.
- **Guided file-staging helper** — a step-by-step assistant for moving files **you select from your own device storage** into another app's import folder, with on-screen instructions. It only copies files you explicitly pick; it never reaches out anywhere on its own.
- **Modern UI** — Jetpack Compose, Material 3, light/dark theming, animated navigation, and a layout that works on handhelds in landscape.

---

## 🛠️ Tech stack

- **Language:** Kotlin (Java 17)
- **Platforms:** Android and Windows, sharing one Kotlin Multiplatform download engine and catalog (`:shared`), with `:app` (Android) and `:desktopApp` (Windows) on top
- **UI:** Jetpack Compose · Material 3
- **DI:** Hilt (Dagger)
- **Navigation:** Navigation-Compose
- **Networking:** OkHttp
- **Storage:** DataStore Preferences · EncryptedSharedPreferences (security-crypto) on Android, Windows DPAPI-encrypted credentials on desktop · Storage Access Framework (SAF)
- **Serialization:** kotlinx.serialization
- **Min SDK:** 29 (Android 10) · **Target/Compile SDK:** 35 · **Windows:** 10 or newer, no separate Java install required (runtime is bundled)

---

## 📲 Install

**Android**

1. Download the latest APK from the [Releases](https://github.com/mayusi/EmuHelper/releases) page.
2. On your device, allow installs from unknown sources for your browser/file manager when prompted.
3. Open the APK to install. Requires **Android 10 (API 29)** or newer.

**Windows**

1. Download the `.msi` installer (or the portable app-image, if you'd rather not install anything) from the [Releases](https://github.com/mayusi/EmuHelper/releases) page.
2. It's an unsigned alpha build, so Windows SmartScreen will show an "unknown publisher" warning — click **More info → Run anyway**.
3. Run it. A Java runtime is bundled in, so there's nothing else to install.

> **Both prebuilt builds come ready to use.** Sign in with your Internet Archive account and start downloading right away — the collections are already set up for you, so there's nothing to configure. *(The source repository itself stays content-free by design; if you build from source, you supply your own collections — see below.)*

> The alpha builds install as a separate `.debug` package on Android, so they won't collide with any future release build.

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

**What is this, plainly?**
A fast, organized download manager for the [Internet Archive](https://archive.org). It signs in with your free Internet Archive account, lets you browse and queue large files from the collections you've set up, and downloads them with multiple connections into tidy folders.

**Do I need an account?**
Yes — a free Internet Archive account, which the app can guide you through creating. It downloads using your own login; the project doesn't include or share any account.

**Does it come with collections built in?**
No. EmuHelper ships with **no collections of its own** — you configure which Internet Archive collections it reads from. The config file (`Catalog.kt`) is generated from a committed template and kept out of version control, so the repository itself stays content-free and every clone starts from an empty, buildable project.

**Can I build it without setting anything up?**
Yes. Clone, copy the template to `Catalog.kt`, and run `./gradlew :app:assembleDebug`. The app compiles and runs with empty lists — collections appear once you add them.

**What permissions does it need?**
Internet access, notification permission (Android 13+), Storage Access Framework (SAF) access to a folder you pick, and install-packages permission for the optional in-app updater. Nothing more.

**Is it affiliated with the Internet Archive?**
No. EmuHelper is an independent, unofficial client. The Internet Archive is a nonprofit digital library; please consider [donating](https://archive.org/donate) to support it.

---

## 🗺️ Roadmap

EmuHelper is actively developed. Recently shipped: in-app updates (with patch notes and one-tap install), download history, notification controls, an in-app theme toggle, and per-list management.

On the horizon:

- List sorting and search within large lists
- Smarter transfer scheduling and retry behavior
- Continued UI and accessibility polish

Have an idea? Open a [feature request](https://github.com/mayusi/EmuHelper/issues/new/choose).

---

## 💬 Community & support

Got a question, hit a snag, or just want to follow along as EmuHelper grows? Come hang out on Discord — it's the best place to get help, share feedback, and catch updates early.

**[Join the EmuHelper Discord →](https://discord.gg/jEnMYW5YfE)**

Maintainer: **naxte** on Discord.

---

## 🤝 Contributing & issues

This is an early alpha, and feedback is genuinely welcome. Bug reports, reproduction steps, and feature ideas all help — please open an [issue](https://github.com/mayusi/EmuHelper/issues). See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines, and [CHANGELOG.md](CHANGELOG.md) for the release history. If you'd like to send a fix, small focused pull requests are easiest to review.

---

## 📄 License

EmuHelper's own code is released under the **MIT License** — see [LICENSE](LICENSE).

### Bundled third-party component — UnRAR

In-app RAR extraction is powered by the official UnRAR library from RARLAB, which
is bundled under its own license (kept verbatim at
[`app/src/main/cpp/unrar/license.txt`](app/src/main/cpp/unrar/license.txt)). As that
license requires:

> UnRAR source code may be used in any software to handle RAR archives without
> limitations free of charge, but cannot be used to develop RAR (WinRAR)
> compatible archiver and to re-create RAR compression algorithm, which is
> proprietary. Distribution of modified UnRAR source code in separate form or as
> a part of other software is permitted, provided that full text of this
> paragraph, starting from "UnRAR source code" words, is included in license, or
> in documentation if license is not available, and in source code comments of
> resulting package.

EmuHelper uses UnRAR for **decompression only**. The UnRAR license is permissive
but is not an OSI-approved open-source license, so while the prebuilt APK and the
GitHub source both bundle it legitimately, this component is the reason the project
isn't a candidate for stores (like F-Droid) that require fully OSI-approved
dependencies.

---

## Credits

Built by **mayusi**.

RAR extraction powered by **UnRAR**, © Alexander Roshal / RARLAB.

Development was assisted by an AI coding assistant (used for implementation, debugging, and documentation).
