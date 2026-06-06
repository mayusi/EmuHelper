# Emulator Setup Helper — Implementation Spec

**Package:** `io.github.mayusi.emuhelper`
**Feature branch target:** `feature/emulator-setup`
**Status:** SPEC ONLY — no code exists yet

---

## 0. Guiding Principle

This feature operates **exclusively on files the user selects from their own device**. The app never fetches, hosts, embeds, decrypts, links to, or suggests any source of prod.keys or firmware. Every action is local-file-only. Violating any item in section 7 (Forbidden List) must be treated as a regression.

---

## 1. User Flow — Screen by Screen

### 1.1 Entry Point: Home screen ⋮ menu

Add one new `DropdownMenuItem` to the existing `MoreVert` overflow menu in `HomeScreen.kt`, positioned below "Settings":

```
DropdownMenuItem(
    text = { Text("Emulator setup") },
    leadingIcon = { Icon(Icons.Default.PhoneAndroid, null) },
    onClick = { menuOpen = false; onEmulatorSetup() }
)
```

`HomeScreen` gains a new `onEmulatorSetup: () -> Unit` parameter, wired in `EmuHelperApp.kt` to `navController.navigate(Routes.EMULATOR_SETUP_DISCLAIMER)`.

No other entry points are needed at v1. A future Settings card linking here is acceptable but out of scope.

---

### 1.2 Screen A — Disclaimer / Legal Acknowledgement (`EmulatorSetupDisclaimerScreen`)

**Purpose:** Gate the entire import flow behind an explicit informed-consent tap.

**Layout:** A single scrollable `Card` centred on the screen, matching the same `Box + Card` style as `OnboardingScreen`. No `Scaffold`/`TopAppBar` — the card is self-contained.

**Content** (see section 6 for exact copy):
- Heading: "Before you continue"
- Icon: `Icons.Default.Warning`, tinted `MaterialTheme.colorScheme.error`
- Five-point disclaimer body (exact wording in §6)
- Two buttons:
  - `OutlinedButton`: "I don't agree / Go back" → `popBackStack()`
  - `Button` (primary): "I understand — continue" → `navController.navigate(Routes.EMULATOR_SETUP_PICK_EMULATOR)`

**SettingsStore interaction:** If `seenSetupDisclaimer` is `true` (see §2.3), skip this screen by checking in `EmuHelperApp.kt`'s `LaunchedEffect` before navigating — navigate directly to `EMULATOR_SETUP_PICK_EMULATOR` and never push the disclaimer onto the back stack. **Do not** skip it silently without the user's explicit prior acknowledgement; the flag must only be set after the "I understand" tap.

---

### 1.3 Screen B — Pick Emulator (`EmulatorSetupPickEmulatorScreen`)

**Purpose:** Let the user tell the app which emulator they are setting up.

**Layout:** `Scaffold` with `TopAppBar` ("Emulator setup", back arrow).

**Content:**
- Subtitle: "Which emulator are you setting up?"
- Three `Card(onClick = …)` tiles in a `Column`, one per emulator:
  - **Eden** — subtitle: "Nintendo Switch emulator (Eden fork)"
  - **Citron** — subtitle: "Nintendo Switch emulator (Citron fork)"
  - **Sudachi** — subtitle: "Nintendo Switch emulator (Sudachi fork)"
- Each tile carries the emulator name as a `String` payload passed forward.

**Navigation:** tapping a tile → `navController.navigate(Routes.emulatorSetupKeys(emulator))` (route carries `emulator` as a path segment).

---

### 1.4 Screen C — Import Keys (`EmulatorSetupKeysScreen`)

**Purpose:** Let the user pick their `prod.keys` file and stage it to a reachable location.

**Layout:** `Scaffold` with `TopAppBar` ("Import keys — {emulator}", back arrow). Body is a single `Column` with a `verticalScroll`.

**Steps shown to user:**

1. **What is this?** — A `Card` with `bodyMedium` text explaining that `prod.keys` is a file the user must dump from their own Nintendo Switch console. A link-like `TextButton` labelled "What is prod.keys?" navigates to Screen F (Info/Help) or opens an inline `AlertDialog` if that is simpler.

2. **Pick your prod.keys file** — A `Card(onClick = …)` picker row (same pattern as the folder picker in `OnboardingScreen`):
   - When no file is chosen: text "Tap to pick prod.keys from your device"
   - When chosen: filename shown via `DocumentFile.fromSingleUri(context, uri)?.name`, tinted `primaryContainer`
   - Tapping launches `OpenDocument` (see §3)

3. **Staging folder** — A read-only informational `Card` showing the path where the file will be copied. Default = `Downloads/EmuHelper-Setup/` via `MediaStore` (see §3.4 for rationale). A small secondary `OutlinedButton` "Change staging folder…" lets advanced users pick an alternative folder via `OpenDocumentTree`.

4. **Copy to folder** — A `Button` (primary, `fillMaxWidth`) labelled "Copy prod.keys to staging folder". Enabled only when a file has been picked. Triggers the copy coroutine (see §3.3). Shows a `LinearProgressIndicator` and status text ("Copying…" / "Done!") while running.

**Navigation after success:** "Next: Import firmware →" `TextButton` appears below the progress area after a successful copy, navigating to Screen D. If the user wants to skip firmware, a secondary `TextButton` "Skip — just show instructions" navigates directly to Screen E.

---

### 1.5 Screen D — Import Firmware (`EmulatorSetupFirmwareScreen`)

**Purpose:** Let the user pick their firmware `.zip` and stage it.

Identical structure to Screen C but for firmware:

1. Explanatory `Card`: firmware must be dumped from the user's own console; the app provides none.
2. **Pick firmware zip** — `OpenDocument` with `application/zip` (and `*/*` fallback) mime.
3. **Staging folder** — same folder as keys (pre-filled, changeable).
4. **Copy to folder** — same copy logic; shows filename + size once done.
5. After success: "Next: Setup instructions →" navigates to Screen E.
6. A `TextButton` "Skip firmware" also goes to Screen E.

---

### 1.6 Screen E — Setup Instructions (`EmulatorSetupInstructionsScreen`)

**Purpose:** Tell the user exactly what to do inside their emulator now that the files are staged.

**Layout:** `Scaffold` with `TopAppBar` ("Setup instructions — {emulator}", back arrow). Body is `verticalScroll`.

**Content per emulator** (see §4 for exact copy):

- `SettingCard`-style cards (matching `SettingsScreen.kt`'s `SettingCard` composable) covering:
  1. **Where your files are** — the staging folder path in `monospace` / `code` style
  2. **Import prod.keys** — numbered steps for this emulator's UI
  3. **Install firmware** — numbered steps for this emulator's UI (shown only if firmware was staged, else still shown but labelled "(if applicable)")
  4. **Open with emulator (optional)** — a `Button` "Open keys file in {emulator}" that fires the intent hand-off (§5). Shown as `OutlinedButton` labelled "Try to open with {emulator}". Below it: `labelSmall` text "If this does nothing, follow the manual steps above."

**Navigation:** A `Button` at the bottom "Done — go home" pops to `Routes.HOME` (inclusive = false).

---

### 1.7 Screen F — Help/Info (optional inline dialog, not a separate route)

If "What is prod.keys?" is tapped, show an `AlertDialog`:

> **prod.keys** is a small file containing cryptographic keys specific to your Nintendo Switch console. Without it, Switch emulators cannot decrypt game data.
>
> You must dump this file yourself using tools like Lockpick_RCM run on your own console. No legitimate source on the internet provides this file legally.

One button: "OK".

---

## 2. New Files to Create

### 2.1 UI files

```
app/src/main/java/io/github/mayusi/emuhelper/ui/setup/
    EmulatorSetupDisclaimerScreen.kt   — Screen A composable (no ViewModel needed; stateless)
    EmulatorSetupPickEmulatorScreen.kt — Screen B composable (no ViewModel needed; stateless)
    EmulatorSetupKeysScreen.kt         — Screen C composable + EmulatorSetupKeysViewModel
    EmulatorSetupFirmwareScreen.kt     — Screen D composable + EmulatorSetupFirmwareViewModel
    EmulatorSetupInstructionsScreen.kt — Screen E composable + EmulatorSetupInstructionsViewModel
    EmulatorSetupModels.kt             — sealed class/enum Emulator { Eden, Citron, Sudachi }
                                          + data class StagedFile(val uri: Uri, val displayName: String, val sizeBytes: Long)
                                          + sealed class CopyState { Idle, Copying(progress: Float), Done(dest: String), Error(msg: String) }
```

**ViewModel responsibilities:**

`EmulatorSetupKeysViewModel` (and mirrored `EmulatorSetupFirmwareViewModel`):
- `pickedFileUri: StateFlow<Uri?>` — from SAF picker result
- `stagingFolderUri: StateFlow<Uri?>` — loaded from `SettingsStore.setupStagingFolder`, defaulting to null (MediaStore path used when null)
- `copyState: StateFlow<CopyState>` — drives the progress UI
- `fun onFilePicked(uri: Uri)` — calls `contentResolver.takePersistableUriPermission(READ)`, updates `pickedFileUri`
- `fun onStagingFolderPicked(uri: Uri)` — calls `takePersistableUriPermission(READ|WRITE)`, persists via `settings.setSetupStagingFolder(uri)`
- `fun copyToStaging(context: Context)` — launches coroutine on `Dispatchers.IO` (see §3.3); updates `copyState`

`EmulatorSetupInstructionsViewModel`:
- Receives `emulator: Emulator` from nav argument
- Exposes `fun fireOpenIntent(context: Context, fileUri: Uri)` — fires the intent (§5)

### 2.2 Routes additions in `EmuHelperApp.kt`

Add to the `Routes` object:

```kotlin
const val EMULATOR_SETUP_DISCLAIMER     = "emulator_setup_disclaimer"
const val EMULATOR_SETUP_PICK_EMULATOR  = "emulator_setup_pick_emulator"
const val EMULATOR_SETUP_KEYS           = "emulator_setup_keys/{emulator}"
const val EMULATOR_SETUP_FIRMWARE       = "emulator_setup_firmware/{emulator}"
const val EMULATOR_SETUP_INSTRUCTIONS   = "emulator_setup_instructions/{emulator}"

fun emulatorSetupKeys(emulator: String)         = "emulator_setup_keys/$emulator"
fun emulatorSetupFirmware(emulator: String)     = "emulator_setup_firmware/$emulator"
fun emulatorSetupInstructions(emulator: String) = "emulator_setup_instructions/$emulator"
```

All five routes use the existing `slideInHorizontally + fadeIn` / `fadeOut` transitions defined in `NavHost`.

Add five `composable(...)` blocks in the `NavHost` lambda, each injecting the `emulator` path argument where needed via `navArgument("emulator") { type = NavType.StringType }`.

`HomeScreen` gains `onEmulatorSetup: () -> Unit` parameter; `EmuHelperApp.kt` wires it to `navController.navigate(Routes.EMULATOR_SETUP_DISCLAIMER)` (or `EMULATOR_SETUP_PICK_EMULATOR` if `seenSetupDisclaimer == true`, checked via a one-shot `LaunchedEffect` in the composable lambda body).

### 2.3 SettingsStore additions

Add two new keys to the companion object:

```kotlin
private val KEY_SEEN_SETUP_DISCLAIMER  = booleanPreferencesKey("seen_setup_disclaimer")
private val KEY_SETUP_STAGING_FOLDER   = stringPreferencesKey("setup_staging_folder_uri")
```

Add corresponding flows and suspend setters following the exact pattern of existing entries:

```kotlin
val seenSetupDisclaimer: Flow<Boolean> = context.settingsStore.data.map { it[KEY_SEEN_SETUP_DISCLAIMER] ?: false }
val setupStagingFolder: Flow<Uri?>     = context.settingsStore.data.map { it[KEY_SETUP_STAGING_FOLDER]?.let { s -> Uri.parse(s) } }

suspend fun setSeenSetupDisclaimer(value: Boolean) { context.settingsStore.edit { it[KEY_SEEN_SETUP_DISCLAIMER] = value } }
suspend fun setSetupStagingFolder(uri: Uri?)        { context.settingsStore.edit {
    if (uri != null) it[KEY_SETUP_STAGING_FOLDER] = uri.toString()
    else it.remove(KEY_SETUP_STAGING_FOLDER)
} }
```

---

## 3. SAF Mechanics

### 3.1 Picking prod.keys — `ActivityResultContracts.OpenDocument`

```kotlin
val keysPicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
) { uri ->
    if (uri != null) viewModel.onFilePicked(uri)
}
// Launch with a permissive list so users can find the file regardless of extension:
keysPicker.launch(arrayOf("*/*"))
```

Although `prod.keys` has no registered MIME type, passing `"*/*"` ensures all files are shown. Do not hardcode a fake MIME — it would hide the file on many file managers. The ViewModel immediately calls `contentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)` so the URI survives process death.

### 3.2 Picking firmware zip — `ActivityResultContracts.OpenDocument`

```kotlin
firmwarePicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
```

Offering multiple MIME values increases compatibility across Android versions and OEM file managers that may only expose `.zip` under `application/x-zip-compressed`.

### 3.3 Copy coroutine

```kotlin
viewModelScope.launch(Dispatchers.IO) {
    _copyState.value = CopyState.Copying(0f)
    try {
        val inputStream  = context.contentResolver.openInputStream(pickedUri)!!
        val outputStream = openStagingOutputStream(context, stagingFolderUri, displayName)
        val buffer = ByteArray(256 * 1024)
        var copied = 0L
        var read: Int
        while (inputStream.read(buffer).also { read = it } != -1) {
            outputStream.write(buffer, 0, read)
            copied += read
            if (fileSize > 0) _copyState.value = CopyState.Copying(copied.toFloat() / fileSize)
        }
        inputStream.close()
        outputStream.close()
        _copyState.value = CopyState.Done(resolvedDisplayPath)
    } catch (e: Exception) {
        _copyState.value = CopyState.Error(e.localizedMessage ?: "Copy failed")
    }
}
```

No external library is required — standard `java.io` streams suffice.

### 3.4 Staging location — recommendation and rationale

**Recommended default: `MediaStore` / `Downloads/EmuHelper-Setup/`**

Use `MediaStore.Downloads` API (API 29+) to insert a new entry and obtain an `OutputStream`. This places the file in the system `Downloads` folder, visible in every stock file manager and accessible by the user and by emulator pickers.

```kotlin
// API 29+ path:
val values = ContentValues().apply {
    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
    put(MediaStore.Downloads.RELATIVE_PATH, "Download/EmuHelper-Setup")
}
val insertUri = context.contentResolver.insert(
    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
)!!
context.contentResolver.openOutputStream(insertUri)!!
```

**Why not `getExternalFilesDir`?** The emulator's own file picker cannot navigate into `Android/data/io.github.mayusi.emuhelper/files/` on Android 11+ without root. Files placed there are invisible to the emulator's built-in import dialog. **Do not use this as the default.**

**Advanced / user-chosen SAF tree:** If the user taps "Change staging folder…" and grants a tree URI, use `DocumentFile.fromTreeUri(context, treeUri)!!.createFile("application/octet-stream", displayName)!!.uri` instead. This lets power users stage directly to e.g. their SD card or a folder already visible to the emulator.

**Tradeoff table:**

| Location | Emulator picker can reach? | User can find it? | Notes |
|---|---|---|---|
| `Downloads/EmuHelper-Setup/` (MediaStore) | Yes | Yes | Recommended default |
| User-chosen SAF tree | Depends on folder chosen | Yes | Advanced option |
| `getExternalFilesDir` | No (Android 11+) | No | Forbidden as default |
| App cache dir | No | No | Only for temp, never final |

---

## 4. Instructions Content Per Emulator

These are the literal on-screen strings to display in Screen E. Render them inside `SettingCard`-style cards using `MaterialTheme.typography.bodyMedium` for body text and numbered `Text` items for steps.

### 4.1 Eden

**Your files are in:** `Downloads/EmuHelper-Setup/` (or your chosen folder)

**Import prod.keys:**
1. Open Eden.
2. Tap the three-dot menu (top-right) or go to **Settings**.
3. Select **System** → **Key files**.
4. Tap **Select prod.keys**, browse to `Downloads/EmuHelper-Setup/`, and select `prod.keys`.
5. Restart Eden if prompted.

**Install firmware:**
1. In Eden, go to **Settings** → **System** → **Firmware**.
2. Tap **Install firmware from zip**.
3. Browse to `Downloads/EmuHelper-Setup/` and select your firmware `.zip`.
4. Wait for installation to finish, then restart Eden.

### 4.2 Citron

**Your files are in:** `Downloads/EmuHelper-Setup/` (or your chosen folder)

**Import prod.keys:**
1. Open Citron.
2. Tap **Settings** (gear icon).
3. Go to **System** → **Encryption keys**.
4. Tap **Load prod.keys file**, navigate to `Downloads/EmuHelper-Setup/`, select `prod.keys`.
5. Tap **OK** and restart Citron if the app requests it.

**Install firmware:**
1. In Citron, go to **Settings** → **System** → **Firmware**.
2. Select **Install firmware (zip)**.
3. Navigate to `Downloads/EmuHelper-Setup/` and pick the firmware `.zip`.
4. Let the installation complete; restart Citron.

### 4.3 Sudachi

**Your files are in:** `Downloads/EmuHelper-Setup/` (or your chosen folder)

**Import prod.keys:**
1. Open Sudachi.
2. Tap the **menu icon** (top-left) → **Settings**.
3. Under **System**, tap **Key Directory** or **Select keys**.
4. Navigate to `Downloads/EmuHelper-Setup/` and select `prod.keys`.
5. Restart Sudachi if the app asks.

**Install firmware:**
1. In Sudachi, go to **Settings** → **System**.
2. Tap **Install firmware**.
3. Choose your firmware `.zip` from `Downloads/EmuHelper-Setup/`.
4. Confirm and allow installation to complete.

> **Implementation note:** Emulator UIs change between releases. These steps reflect the UIs known at spec-write time. Add a `labelSmall` footnote on Screen E: *"Menu names may differ slightly in newer versions of {emulator}. Check the emulator's own help if a step doesn't match."*

---

## 5. Intent Hand-Off (Best-Effort)

After the files are staged, the Instructions screen includes an `OutlinedButton` that fires an `ACTION_VIEW` intent so the user can hand the file to the emulator directly:

```kotlin
fun fireOpenIntent(context: Context, fileUri: Uri) {
    // fileUri is the MediaStore content:// URI from the staging copy step.
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(fileUri, "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "Open with emulator"))
    } catch (e: ActivityNotFoundException) {
        // Emulator not installed or no handler registered — fall through to instructions.
        // The ViewModel emits a one-shot event; the screen shows a Snackbar:
        // "No app handled this file — follow the manual steps above."
    }
}
```

**Why this may or may not work:**
- Nintendo Switch emulators generally do not register `intent-filter` entries for `.keys` or raw firmware `.zip` files. The system chooser will likely show "No apps available" or offer generic file managers.
- Even if the emulator appears in the chooser, it will open the file in its own file browser rather than directly importing it.
- The fallback (manual steps) is the reliable path; the intent is a convenience that may delight users on certain device/emulator combinations.
- Do **not** hardcode a package name (`setPackage("...")`) for any specific emulator fork. Package names change between forks and this would hard-couple the app to specific releases; `createChooser` is the correct approach.

---

## 6. Legal / Disclaimer Copy (Exact Wording)

The following text must appear verbatim in `EmulatorSetupDisclaimerScreen`. Do not shorten, paraphrase, or omit any point.

---

**Heading (titleMedium or headlineSmall):**
> Before you continue

**Body (bodyMedium, rendered as a single scrollable block with paragraph spacing):**

> This feature helps you move files **you already own** into a Nintendo Switch emulator. It does not supply, download, host, or link to any keys or firmware files.
>
> **You must read and agree to the following before proceeding:**
>
> 1. **Your own files only.** `prod.keys` and firmware must be dumped exclusively from a Nintendo Switch console that you own. Using files obtained from any other source may be illegal in your jurisdiction.
>
> 2. **No files are provided by this app.** EmuHelper does not contain, embed, download, or link to prod.keys, firmware, or any Nintendo intellectual property. This feature only copies files you select from your device's storage.
>
> 3. **Legal responsibility is yours.** Laws governing the dumping and use of cryptographic keys and console firmware vary by country. It is your responsibility to ensure your use complies with applicable law. The developer of this app accepts no liability for how you use this feature.
>
> 4. **No emulation endorsement.** This app provides a file-management convenience only. The developer makes no representation about the legality of any specific emulation activity in your jurisdiction.
>
> 5. **Proceeding means you agree.** Tapping "I understand — continue" confirms that you have read this notice, that the files you will import are legally yours, and that you accept full responsibility for their use.

**Buttons:**
- `OutlinedButton`: "I don't agree — go back"
- `Button` (primary): "I understand — continue"

---

## 7. Forbidden List — Must NOT Be Built

The following must never appear in any commit touching this feature. Treat any PR that includes them as a blocker:

1. **No key download.** No HTTP/HTTPS request, WebView, or deep link that retrieves, proxies, or redirects to a `prod.keys` file from any remote server.
2. **No firmware download.** Same restriction for firmware `.zip` or `.bin` files.
3. **No bundled keys or firmware.** No `prod.keys`, `title.keys`, firmware `.zip`, `.bin`, or any Nintendo-encrypted asset in `assets/`, `res/`, or any other resource directory.
4. **No links to key/firmware sources.** No hardcoded URL, QR code, or in-app text pointing to any website, Telegram channel, Discord server, or other location where keys or firmware can be obtained.
5. **No decryption logic.** No code that decrypts Nintendo-encrypted content, even if the input file is user-supplied.
6. **No emulator-package assumption.** No hardcoded emulator package names used to bypass Android permission boundaries or to silently write into another app's data directory.
7. **No silent background copy.** All file operations that read or write the user's storage must be initiated by an explicit user tap in the UI, never triggered automatically by a background service or WorkManager task.
8. **No `getExternalFilesDir` as the default staging location.** It is not accessible to the emulator's own file picker on Android 11+ and would silently break the workflow.

---

## 8. Miscellaneous Implementation Notes

- **Minimum SDK:** The app's existing `minSdk` applies. `MediaStore.Downloads` requires API 29 (Android 10). Add an `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)` guard; on API 28 (unlikely given the emulator audience) fall back to the `Environment.DIRECTORY_DOWNLOADS` path via `File`, with a `READ_EXTERNAL_STORAGE` permission check.
- **File size display:** Show `DocumentFile.fromSingleUri(context, uri)?.length()` in human-readable form (e.g. "12.4 MB") next to the filename once picked.
- **Progress accuracy:** `prod.keys` is typically under 100 KB; the progress bar will be nearly instant. Firmware is typically 300–500 MB; the progress bar matters here. Emit progress updates every buffer flush.
- **Error states:** If `ContentResolver.openInputStream` returns null, surface `CopyState.Error("Could not read the selected file. Has the file been deleted?")`.
- **Rotation / process death:** The picked file URIs are `String`-serialisable (via `uri.toString()`); persist them to `SettingsStore` immediately on pick so they survive process death. Clear them only when the user explicitly starts a new import session.
- **ViewModel sharing across Keys and Firmware screens:** Do not share a single ViewModel between the two screens — give each its own scoped `@HiltViewModel`. If the nav graph needs to pass the staging folder URI between them, encode it as a nav argument or read it from `SettingsStore.setupStagingFolder` (which both ViewModels inject).
- **Back-stack cleanliness:** When the user taps "Done — go home" on Screen E, use `popBackStack(Routes.HOME, inclusive = false)` to clear the entire setup sub-stack, consistent with how `SaveListScreen.onSaved` is handled.
- **No new permissions declared.** The feature uses only SAF (`OpenDocument`, `OpenDocumentTree`) and `MediaStore.Downloads`, none of which require manifest permission declarations on API 29+.
