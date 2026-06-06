# EmuHelper

**A configurable Android client for browsing and retrieving files from user‑supplied web archive endpoints.**

EmuHelper is a generic, source‑agnostic download manager. It reads collection metadata from endpoints you configure, lets you assemble and persist selections, and retrieves them with a concurrent transfer engine. It ships with **no endpoints and no content** — you bring your own.

> **Status: Alpha (v0.1.1-alpha).** First public releases. Expect rough edges; please file issues.

---

## Overview

The app operates on an externally‑defined catalog of HTTP endpoints. For each configured group it parses a remote directory/metadata listing, presents the entries for selection, and transfers chosen items to local storage. Everything beyond the generic transfer/UI machinery — i.e. *which* endpoints exist — is provided by the operator at build time and is not part of this repository.

---

## Capabilities

- **Two selection workflows** — assemble a persistent selection set for later retrieval, or run an immediate ad‑hoc retrieval session.
- **Grouped catalog** — entries are organised into operator‑defined groups; the UI exposes one group at a time.
- **Selection tooling** — substring filtering, tag‑based filtering (locale/region tokens), size‑bucket filtering, ordering, and cross‑group multi‑select.
- **Persistent selection sets** — name, reuse, and serialise selection sets to/from portable `.json` documents for backup or transfer between installs.
- **Concurrent transfer engine**
  - Multi‑connection (range‑segmented) transfers with host fail‑over across mirror endpoints.
  - Per‑item progress, throughput and time‑remaining estimates.
  - Continues while the UI is not foregrounded, via a foreground service.
  - Optional post‑transfer archive expansion (`.zip`) and grouping of outputs into per‑group destination folders.
- **Transfer tuning** — adjustable connection count, a high‑throughput preset, an integrated link‑throughput probe, and a device capability readout.
- **Session persistence** — operator credentials for the configured backend are retained in encrypted on‑device storage and re‑applied automatically.
- **Modern UI** — Jetpack Compose · Material 3 · light/dark theming · animated navigation.

---

## Tech stack

- **Language:** Kotlin
- **UI:** Jetpack Compose · Material 3
- **DI:** Hilt (Dagger)
- **Navigation:** Navigation‑Compose
- **Networking:** OkHttp
- **Storage:** DataStore Preferences · EncryptedSharedPreferences (security‑crypto) · Storage Access Framework (SAF)
- **Serialization:** kotlinx.serialization
- **Min SDK:** 29 · **Target/Compile SDK:** 35 · **Java:** 17

---

## Building from source

This repository **contains no endpoint configuration**. The app reads its catalog from a single source file that is intentionally excluded from version control. To build, generate it from the committed template and supply your own endpoints:

```bash
# 1. Clone
git clone https://github.com/mayusi/EmuHelper.git
cd EmuHelper

# 2. Create the catalog from the template
cp app/src/main/java/io/github/mayusi/emuhelper/data/config/Catalog.kt.template \
   app/src/main/java/io/github/mayusi/emuhelper/data/config/Catalog.kt

# 3. Edit Catalog.kt and populate the endpoint map with the URLs you intend to use.
#    It compiles and runs with empty groups; entries appear only once you add endpoints.

# 4. Build
./gradlew :app:assembleDebug
```

Output APK: `app/build/outputs/apk/debug/app-debug.apk`.

---

## Configuration

`Catalog.kt` — the file mapping group keys to endpoint URLs — is **git‑ignored by design** so that no endpoints are published here. The committed `Catalog.kt.template` provides the full structure with empty groups so the project still compiles. The operator supplies endpoints locally.

---

## Disclaimer

EmuHelper is a general‑purpose transfer utility and front‑end. It hosts nothing, bundles no endpoints, and has no knowledge of what an operator chooses to point it at. Users are solely responsible for the endpoints they configure, the data they retrieve, and for complying with all applicable laws and the terms of any service they access. The authors provide the software "as is" and accept no responsibility for how it is used.

---

## License

Released under the **MIT License** — see [LICENSE](LICENSE).

---

## Credits

Built by **mayusi**.

Development was assisted by **Anthropic's Claude** (used as an AI coding assistant for implementation, debugging, and documentation).
