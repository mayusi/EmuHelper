# Changelog

All notable changes to EmuHelper are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/), and the project follows a
semantic-style versioning scheme while in alpha.

## [0.3.2] — 2026-06-11

### Fixed
- Landscape display on handheld devices: several screens (including sign-in)
  centered their content and clipped the bottom off-screen with no way to
  scroll. They now scroll so every button and link is reachable — notably the
  "Log In", "Skip login", and "Create an account" options on the sign-in screen.

## [0.3.1] — 2026-06-11

### Added
- A guided "create an account" path for new users: a link on the sign-in
  screen leads to a short explainer and an in-app signup page (with an
  "open in browser" fallback), so people without an account can make one
  without getting stuck at the login wall.

## [0.3.0] — 2026-06-11

### Added
- **Updatable source catalog (optional).** The source list can now be refreshed
  from a remote file at runtime, so it can change without shipping a new app
  build. It's off by default and the built-in catalog is always the fallback —
  a remote update can only add or replace sources, never remove the built-in
  ones, and any failed or invalid fetch leaves the catalog untouched.
- **Source health check** (in About): pings each configured endpoint and reports
  which are reachable, so dead sources are easy to spot.
- **Local error log** (in About): an on-device, no-network record of errors that
  can be viewed, copied, or shared for diagnosis.

### Internal
- A release helper script that builds, tests, hashes, and drafts release notes.

## [0.2.1] — 2026-06-11

### Added
- Re-download from history: tap a past download to fetch it again.
- Search and sort in the saved-list library, plus a creation date on each list.
- Settings: "Reset to defaults" for speed controls and a Storage section to
  view, change, or revert the download folder.
- A confirmation prompt before downloading a batch that may not fit in the
  available space, and a rough scan-size hint when choosing categories.

## [0.2.0] — 2026-06-11

### Performance
- Faster, smoother downloads: throttled progress aggregation, cached archive
  metadata (no more refetching the same listing per file), signal-based pause
  (instant resume), buffer reuse, and lighter UI hot paths.

### Added
- Live download progress in the notification (count, %, speed).
- Tappable history entries — open the download folder or copy a filename.
- Cancel/retry in the file-staging flow and cancel for the speed test.
- First unit-test suite (81 tests) for the project's core logic.

### Changed
- Clearer sign-in prompt and empty-state guidance on the home screen.
- A scan that finds nothing now offers go-back and retry instead of a dead end.

## [0.1.9] — 2026-06-11

### Security
- The in-app updater now verifies updates before installing: it only accepts
  APKs from the official GitHub release host and checks a published SHA-256
  when available, discarding anything that doesn't match.
- Stored credentials are never written to unencrypted storage as a fallback.
- Hardened archive extraction against path-escape, and scoped session cookies
  strictly to their source.

### Added
- Resume an interrupted download batch after a crash or restart.
- "Already downloaded" indicator on files you've fetched before.
- Search across all scanned categories at once.
- Import a saved list from a URL.

### Fixed
- Several download-engine races (duplicate retries, history miscounts).
- Update download can now be cancelled; partial files are cleaned up.
- Clear "storage full" message; retry re-checks folder access first.
- Version comparison handles pre-release tags; assorted UI state fixes.

## [0.1.8] — 2026-06-11

### Added
- **In-app updates** — the update system now shows the new release's patch
  notes inside the app, downloads the new build with a progress bar, and hands
  it to the installer so you can update without leaving the app. Routes you to
  grant install permission if needed, and falls back to opening the release page
  if a build has no installable asset.
- A dismissed update is remembered per version, so it won't keep re-appearing
  until a newer release is out.

## [0.1.7] — 2026-06-11

### Added
- **In-app update check** — on launch (at most once a day) the app checks for a
  newer release and shows a dismissible "update available" banner.
- **About screen** — version, project links, and a manual "check for updates"
  button, reachable from the home menu.
- **Download history** — completed and failed transfers are logged and viewable,
  with a clear-history action.
- **Notification controls** — pause/resume and cancel a running batch from the
  download notification.
- **Theme toggle** — choose System / Light / Dark in Settings.
- **Rename saved lists** from the library.
- **Free-space readout** with a "may not fit" warning before downloading.
- Remembers your last selection so it's pre-ticked next time.

### Changed
- Cleans up orphaned temporary download files on launch.
- UI polish: unified corner radius and spacing, smooth list animations, a
  friendlier empty state in the picker, one-tap Settings on the home screen,
  a step indicator in the guided file-staging flow, and accessibility fixes.

## [0.1.6] — 2026-06-08

### Changed
- Repository hygiene & project-health pass: added contributing, security, and
  code-of-conduct guidelines, issue and pull-request templates, and this
  changelog.
- Internal cleanup and tidying across the project.

## [0.1.5] — 2026-06-07

### Changed
- **Privacy & stability:** stopped writing sensitive details to the device log;
  hardened optimized release builds for serialized data.
- **More reliable downloads:** detect lost storage-folder access up front
  instead of failing late; verify file size mid-transfer to prevent corrupt
  files; "Retry failed" now preserves each file's target folder.
- **UX:** confirm before deleting a saved list; "Open folder" button after a
  batch finishes; request notification permission on Android 13+; corrected a
  settings slider range.
- Full README overhaul.

## [0.1.4] — 2026-06-06

### Changed
- Archive extraction is now **off by default**.
- Case-insensitive folder matching: downloads merge into an existing folder of a
  different letter case instead of creating a duplicate.
- The "already downloaded, skip" check and per-file overwrite are now
  case-insensitive as well.

## [0.1.3] — 2026-06-06

### Added
- Guided file-staging helper: copies files you select from your own device into
  another app's import folder, with on-screen instructions.
- Project logo.

### Changed
- Dead-code cleanup.

## [0.1.2] — 2026-06-06

### Added
- App version shown on the home screen.

[Releases]: https://github.com/mayusi/EmuHelper/releases
