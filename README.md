# pFiles

A small, fast file manager for Android with a built-in code editor, a viewer for huge files and a hex editor, in an APK measured in kilobytes.

## Features

- **Two panels.** Side by side in landscape, stacked in portrait, each with its own folder and selection. Drag and drop between them, or copy and move with one tap.
- **Browse your way.** List or grid, sort by name, size, date or type, hidden files on or off, a clickable path bar and shortcuts to every storage.
- **Search** the current folder and everything inside it, by name (wildcards or regex) and by text inside files.
- **Batch rename** with wildcards or regex and a numbered counter, with a live preview.
- **Archives.** Browse ZIP, JAR, APK, EPUB, TAR, GZ, 7z and RAR files without extracting them (7z and RAR are read-only; RAR5 is not supported), compress a selection to ZIP, or extract next to the archive. APKs show their icon, name and version, with an Install button.
- **Code editor** with syntax highlighting for 20+ languages, line numbers, find and replace, undo and redo. It keeps the file's encoding and line endings.
- **Viewer for big files.** Files over 1 MB open instantly; lines are read from disk only when shown.
- **Hex editor** for files of any size, with an on-screen keypad and value inspector.
- **Image viewer** with pinch zoom, swipe through the folder and EXIF details. **Media player** for audio and video. **PDF viewer** with zoom and go-to-page.
- **Table view** for CSV, TSV and JSON, a read-only **SQLite browser** and an **APK info** screen with permissions and the signing certificate.
- **Trash.** Deleted files go to a trash folder on the same storage, can be restored, and are removed after 30 days. Untick "Move to trash" in the delete dialog to delete for good.
- **Bookmarks and recent files** in the storage menu; long-press the path bar to bookmark a folder.
- **Storage analyzer.** See which folders take the space, the largest files and duplicate files.
- **Checksums.** CRC32, MD5, SHA-1 and SHA-256 from the Properties dialog.
- **Compare** two files as text or hex.
- **Modules.** Every built-in viewer can be switched off in the menu under Modules.
- **Open with.** pFiles appears in other apps as **pFiles Editor** and **pFiles Hex**.
- **FTP and SFTP** through the separate **pFiles FTP** module (a second small APK, `pFiles-FTP-*.apk`, installed next to pFiles). Connect to FTP or SFTP servers to browse, download and upload files in either panel, edit remote files and save them back, and share your own storage with an embedded FTP or SFTP server. Without the module the app still works; only the FTP and SFTP features say it is missing.
- **No ads, no data collection.**

## Permissions

| Permission | Why | Required |
|---|---|---|
| All files access (Android 11+) / storage | Browsing and editing your files | Yes |
| Install unknown apps | **Install** button for APK files | Optional |
| Internet & network state (FTP module) | Connecting to FTP and SFTP servers and sharing files over Wi-Fi | Optional |

### Notes

- The FTP module has its own permissions: internet, a foreground service while a file server runs, and **All files access**, which it asks for the first time you start the server.
- Copy and move run in the background; name clashes become `name (1).ext`, and delete never follows symbolic links.
- Every editor and viewer feature is described in the [guide](docs/GUIDE.md).

## Build

Requires Android 5.0+ (API 21). Built with Kotlin on the plain Android framework. A few small libraries cover what the framework lacks: zip4j (encrypted ZIP), Apache Commons Compress with XZ for Java (7z), junrar (RAR) and Shizuku. The FTP module adds commons-net (FTP), JSch (SFTP client) and Apache MINA SSHD (SFTP server).

```bash
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/`.

## License

See [LICENSE](LICENSE).
