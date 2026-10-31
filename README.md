# pFiles

A small, fast file manager for Android with a built-in code editor, a viewer for huge files and a hex editor, in an APK measured in kilobytes.

## Features

- **Two panels.** Side by side in landscape, stacked in portrait, each with its own folder and selection. Drag and drop between them, or copy and move with one tap.
- **Browse your way.** List or grid, sort by name, size, date or type, hidden files on or off, a clickable path bar and shortcuts to every storage.
- **Search** the current folder and everything inside it, by name (wildcards or regex) and by text inside files.
- **Batch rename** with wildcards or regex and a numbered counter, with a live preview.
- **Archives.** Browse ZIP, JAR, APK, EPUB, TAR and GZ files without extracting them, compress a selection to ZIP, or extract next to the archive. APKs show their icon, name and version, with an Install button.
- **Code editor** with syntax highlighting for 20+ languages, line numbers, find and replace, undo and redo. It keeps the file's encoding and line endings.
- **Viewer for big files.** Files over 1 MB open instantly; lines are read from disk only when shown.
- **Hex editor** for files of any size, with an on-screen keypad and value inspector.
- **Open with.** pFiles appears in other apps as **pFiles Editor** and **pFiles Hex**.
- **No ads, no network, no data collection.**

## Permissions

| Permission | Why | Required |
|---|---|---|
| All files access (Android 11+) / storage | Browsing and editing your files | Yes |
| Install unknown apps | **Install** button for APK files | Optional |

### Notes

- Copy and move run in the background; name clashes become `name (1).ext`, and delete never follows symbolic links.
- Every editor and viewer feature is described in the [guide](docs/GUIDE.md).

## Build

Requires Android 5.0+ (API 21). Built with Kotlin on the plain Android framework, without libraries.

```bash
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/`.

## License

See [LICENSE](LICENSE).
