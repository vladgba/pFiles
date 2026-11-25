# pFiles guide

The full reference. For an overview, see the [README](../README.md).
A small file manager for Android, with a built-in code editor, read-only text viewer and hex editor.

pFiles has **zero library dependencies**. It uses only the Android framework and the Kotlin standard library: no AndroidX, no support libraries, no image assets. All screens are built in code and all icons are vector drawables, so the release APK is measured in kilobytes.

- Package: `x.vladgba.files`
- Android 5.0+ (API 21), targets API 35
- Licence: MIT

## Features

### File browser
- List and grid views, switchable from the menu
- Two panels, Total Commander style (bottom bar → Two panels): side by side in landscape, stacked in portrait. Each panel keeps its own folder, breadcrumb and selection; the one you touched last is active (accent line under its path) and the bottom bar acts on it
- Drag & drop: long-press an item (or a selection), then move your finger. Drop it on a folder to put it there, or anywhere else in a panel to put it in that panel's folder, then choose Copy or Move. The list scrolls when you hold near its top or bottom edge. With two panels, ⋮ also has Copy / Move to other panel
- Clickable breadcrumb path bar; Back goes up one folder until you reach a storage root
- Sort by name, size, date or type, ascending or descending; folders always come first
- Show/hide hidden files, and filter the current folder by name
- Search the current folder and everything inside it (search row → Subfolders…) by name (part of it, `*.txt` wildcard, or regex) and/or by text inside files (plain or regex, with line number and snippet). Results stream in and the search can be stopped; tap a result to open it, long-press to show it in its folder
- Storage shortcuts: internal storage, SD cards / USB drives, the app's own files, and `/`
- Long-press to multi-select: copy, cut, paste, delete, rename, share, open with…, properties
- Rename by pattern (⋮ → Rename by pattern…): wildcard (`*.jpeg` → `*.jpg`, `IMG_*` → `Photo *`) or Java regex with `$1` groups, optional match case, and a `[N]` / `[NNN]` counter. A live preview marks invalid names and clashes, which are skipped; renames go through temporary names, so swaps work
- Archives: browse and preview `.zip`, `.jar`, `.aar`, `.apk`, `.epub`, `.tar`, `.tgz`, `.tar.gz`, and `.gz` archives without extracting (tap to view, or ⋮ → Archive preview). For APK files, displays app icon, label, package id, version, and an Install button. Compress selection to `.zip` (⋮ → Compress to ZIP), and extract archives into a folder next to them (⋮ → Extract here). Entries resolving outside ("zip/tar slip") are refused; non-UTF-8 zip names are retried as CP437 on Android 7+
- Copy and move run in the background. Name clashes become `name (1).ext`, and delete never follows symlinks
- Files are shared with other apps through a small built-in content provider (no AndroidX `FileProvider`)

### Text editor
- Syntax highlighting for Kotlin, Java, JavaScript/TypeScript, C/C++, C#, Go, Rust, Swift, Dart and more; Python and shell scripts; XML/HTML/SVG; JSON; CSS; SQL; Markdown; YAML/TOML/INI/properties
- **Asynchronous, line-by-line highlighting**: tokenizing runs on a background thread and only the visible lines (plus a margin) are coloured. The state at the start of each line is remembered, so multi-line comments and strings are coloured correctly and an edit only re-scans the lines after it
- Line-number gutter, word wrap, text size, go to line
- Find / replace / replace all, and undo / redo
- Show unprintable characters: `·` space, `→` tab, `¶` line end, `␀`…`␟` control characters
- Kinetic scrolling (fling), which `EditText` doesn't provide itself
- Keeps the file's encoding (UTF-8, with or without BOM; falls back to Latin-1 so any byte round-trips), line endings (LF/CRLF) and writes atomically via a temp file + rename
- Picks a truly fixed-width system font at runtime, because some vendors' "monospace" isn't

### Read-only text viewer
Used automatically for files over 1 MB, binary files and files you can't write to.
- Never loads the whole file: rows are read from disk on demand through a small page cache
- A sparse line index is built in the background (every 32nd row is stored); the file is scrollable while indexing
- Long lines and binary data are split into short rows (≤ 1024 bytes, never inside a UTF-8 sequence)
- Background per-row highlighting, find (ASCII case-insensitive, wraps around), go to line, copy a row with a long-press

### Hex editor
- Paged access: works on files of any size; saving writes back only the changed bytes
- Offset / hex / ASCII columns, fitting 16, 12, 8 or 4 bytes per row to the screen width
- Overwrite editing with an on-screen 0–F keypad; edited bytes are highlighted; undo
- Go to offset (hex or decimal), find hex bytes or text, find next, write text at the cursor
- Status bar with the value under the cursor as u8/i8, binary, u16/i16 and u32/i32 (little-endian)

### Opening files from other apps
pFiles appears in other apps' *Open with* and *Share* lists as **pFiles Editor** (text-like files) and **pFiles Hex** (any file). It handles `ACTION_VIEW`, `ACTION_EDIT` and `ACTION_SEND`.
- If the link resolves to a real file (a `file://` path, the system storage and Downloads providers, or a provider that exposes the file path), that file is opened directly
- Otherwise the content is copied into the app cache and the copy is opened. If the sending app granted write access, every save is also written back to the original; otherwise the copy is read-only and opens in the viewer
- Shared plain text (no file attached) opens as a read-only text copy
- Paths inside pFiles's own private data folder are never opened this way
- Copies older than a day are cleaned up automatically

## Building

Requirements: JDK 17+ and the Android SDK (platform 35).

```sh
./gradlew assembleDebug     # app/build/outputs/apk/debug/
./gradlew assembleRelease   # app/build/outputs/apk/release/
./gradlew testDebugUnitTest # JVM unit tests (JUnit is test-only; the APK has no dependencies)
```

Or open the project in Android Studio / IntelliJ IDEA and run it.

Release builds use R8 with resource shrinking, and the Kotlin compiler's runtime null-check assertions are turned off to keep the dex small. **The release build is signed with the debug key** so it installs straight away. Configure your own `signingConfig` in `app/build.gradle.kts` before publishing.

## Permissions

| Android version | What pFiles asks for |
| --- | --- |
| 11+ (API 30+) | "All files access" (`MANAGE_EXTERNAL_STORAGE`), granted from a banner that opens the system settings page |
| 6–10 (API 23–29) | `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` at runtime |
| 5.x (API 21–22) | Granted at install |

Publishing to Google Play with `MANAGE_EXTERNAL_STORAGE` requires a permission declaration for review.

## Project layout

```
app/src/main/java/x/vladgba/files/
├── MainActivity.kt      file browser: list/grid, breadcrumbs, sorting, selection, file operations
├── FileAdapter.kt       list rows and grid tiles, drawn type badges
├── Entry.kt             file snapshot + type → colour mapping
├── FileOps.kt           copy / move / delete / unique names / atomic write (background thread)
├── Storage.kt           storage roots and permission handling
├── FilesProvider.kt     minimal content:// provider for sharing and "open with"
├── Openers.kt           module registry (see below), write-back for files opened from other apps
├── OpenActivity.kt      entry point for VIEW / EDIT / SEND intents from other apps
├── EditorActivity.kt    text editor screen, undo/redo history
├── CodeEditText.kt      EditText with gutter, unprintable markers and kinetic scrolling
├── Highlighter.kt       async line-by-line highlighter for the editor
├── Syntax.kt            languages, line tokenizer, colour palettes
├── ViewerActivity.kt    read-only paged text viewer (ByteSource, LineIndex)
├── HexActivity.kt       hex editor (HexDoc paged document)
├── ZipActivity.kt       archive previewer (ZipTree, virtual folders, APK banner)
├── ZipOps.kt            zip compress / extract / tree inspection
├── TarOps.kt            tar / tgz / gz archive parser and extraction
├── ArchiveOps.kt        unified archive gateway (.zip, .apk, .tar, .tgz, .gz)
├── Fonts.kt             runtime selection of a truly monospace font
└── Ui.kt                small shared UI helpers (dp, insets, dialogs)
```

## Modules

Built-in viewers are modules registered in `Openers.all`. When you tap a file, the first enabled module whose `canOpen()` returns true handles it. The menu entry **Modules** switches each one on or off (`Openers.modules` is the enabled subset). Anything unclaimed goes to an external app via `ACTION_VIEW`, and "Open with…" lists every module plus other installed apps.

```kotlin
interface FileOpener {
    val id: String                       // stable id, used in preferences
    val title: Int                       // name shown in "Open with…"
    fun canOpen(f: File): Boolean        // claim the file on a plain tap
    fun open(a: Activity, f: File)
}
```

Current modules, in claim order:

| Module | Claims on tap | Notes |
|---|---|---|
| `RtfModule` | `.rtf` | Preview |
| `MarkdownModule` | Markdown files | Rendered view |
| `ImageModule` | jpg, png, gif, webp, bmp, heic, avif | Pinch zoom, double tap, swipe or menu for the next image in the folder, EXIF in Properties. Decoded downsampled, so big photos are safe |
| `MediaModule` | common audio and video | Platform `VideoView`; music continues through the folder, position survives rotation |
| `PdfModule` | `.pdf` | Platform `PdfRenderer`; pages render as they scroll into view, zoom 100 to 400 percent, go to page. Password-protected files are not supported |
| `SqliteModule` | `.db`, `.sqlite`, `.sqlite3`, `.db3` with an SQLite header | Read-only: tables and views with row counts, 500 rows per page, long-press shows the schema |
| `TextModule` | text files | Editor; large or read-only files are routed to the viewer |
| `ViewerModule` | nothing (Open with) | Read-only text viewer |
| `HexModule` | nothing (Open with) | Hex editor |
| `ZipModule` | archives | ZIP family, TAR, GZ, and read-only 7z and RAR4 through `ExtraArchives` |
| `TableModule` | nothing (Open with) | CSV/TSV with delimiter detection, JSON arrays and JSON lines, up to 16 MB and 50,000 rows |
| `ApkModule` | nothing (Open with) | Version, installed version, SDK levels, signing certificate, components and permissions |

To add one, implement `FileOpener`, add its activity to the manifest and add it to `Openers.all`.

### FTP module

All FTP and SFTP code lives in the `ftpmodule` APK (`x.vladgba.files.ftp`): the FTP client (commons-net), the SFTP client (JSch), the embedded FTP server and the SFTP server (Apache MINA SSHD). pFiles binds to it through the hand-written binder `IFtpService` and only the same signing key may call it. Version 2 added `serverStart`, `serverStop` and `serverStatus`: the module runs the servers as its own foreground service and needs its own access to all files, so pFiles opens the module's screen to ask for it. Edited files opened from a server are uploaded back when you save.

### Other features

- **Trash** (`Trash.kt`, `TrashActivity`): each storage volume has a hidden `.pFilesTrash` folder, so trashing is a rename and never copies data. Items are restored to their original place and purged after 30 days. FTP, Shizuku and app-private files are always deleted for good.
- **Bookmarks and recents** (`Places.kt`): stored in preferences; the storage menu lists them.
- **Storage analyzer** (`AnalyzeOps.kt`, `AnalyzerActivity`): one walk of the folder gives per-child sizes and the 200 largest files; duplicates are found by size, then a hash of the first 64 KB, then a full SHA-256.
- **Checksums** (`Checksums.kt`): one pass over the file for all four digests.

## Licence

MIT. See [LICENSE](../LICENSE).
