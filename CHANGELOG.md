# Changelog

All notable changes to EmuHelper are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/), and the project follows a
semantic-style versioning scheme while in alpha.

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
