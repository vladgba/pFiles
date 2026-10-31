# pFiles Roadmap: modular system + standard modules

## Starting point
- Single Gradle module `app`, zero dependencies, ~9k LOC, flat package.
- `FileOpener` + `Openers.modules` already exist, but cover only "open a file". Everything else (menu actions, file ops, search, storage roots) is hard-wired in `MainActivity` (856 lines).
- Modules are Activities listed in the manifest. Registration is a hard-coded list.

## Constraints (keep)
- No library dependencies, API 21+, tiny APK.
- No dynamic code loading (DexClassLoader): it is a security and Play-policy risk, and it breaks R8.

## Architecture options
| Option | Pros | Cons |
|---|---|---|
| A. In-APK modules behind a `Module` interface, one Gradle module | Simplest, R8 friendly, no build change | Boundaries by convention only |
| B. Gradle sub-modules (`:core`, `:mod-*`), assembled in `:app` | Enforced boundaries, optional modules per flavor | More build config, slower builds on phone |
| C. External plugin APKs (intents/AIDL) | True 3rd-party extensibility | Heavy: security, versioning, IPC |

**Recommendation:** A now, B when the module count reaches ~8 or a flavor needs to drop modules. C only if third parties ask for it.

## Target contract (v1)
```kotlin
interface Module {
    val id: String                      // "markdown", "hex"
    val title: Int
    val apiVersion: Int                 // contract version, for later changes
    fun capabilities(): Set<Capability> // OPENER, ACTION, SEARCH_PROVIDER, STORAGE_ROOT, PREVIEW
}
interface FileOpener : Module { canOpen(f); open(a, f); priority: Int }
interface FileAction : Module { appliesTo(sel: List<File>): Boolean; run(a, sel) } // menu entries
interface StorageProvider : Module { roots(): List<Root> }
```
- `ModuleRegistry` replaces the `Openers.modules` list: ordered by priority, enable/disable stored in SharedPreferences.
- A module crash must not take down the browser: each runs in its own Activity or a try/catch boundary.
- Operations are cancellable and run off the main thread (extend the `FileOps` pattern).

## Phase roadmap
### Phase 0: Prep (1 week)
- Write an ADR for option A and the contract.
- Move code into packages: `core/`, `browser/`, `modules/<name>/`.
- Add unit tests around `Openers` ordering.

### Phase 1: Registry (1–2 weeks)
- Introduce `Module`, `ModuleRegistry`, priority and enable flags.
- Port the existing 5 openers (Markdown, Text, Viewer, Hex, Zip) with no behavior change.
- "Open with…" and the settings screen read from the registry.

### Phase 2: Action extension points (2 weeks)
- Extract from `MainActivity` into modules: Rename by pattern, Compress/Extract, Share, Properties, Search.
- Menus are built from `FileAction.appliesTo(selection)`.
- `MainActivity` target is under 400 lines.

### Phase 3: Storage and services (1–2 weeks)
- `StorageProvider` for internal/SD/USB/app-files/`/`, which makes room for later remote roots.
- Shared services: `JobQueue` (background copy/move with progress notification), `Thumbnailer` cache.

### Phase 4: Gradle split (optional, 2 weeks)
- `:core` (contracts + ops), `:mod-*` for each module, `:app` as the assembler.
- Release flavors: `lite` (browser + text + zip) and `full`.

### Phase 5: Hardening (ongoing)
- Contract tests that every module must pass (canOpen is pure and fast, no main-thread IO).
- Proguard keep rules checked in CI, APK size budget in the CI workflow.

## Module roadmap
### Exists
Markdown viewer, Text editor, Read-only viewer, Hex editor, Archive previewer (zip/apk/tar/gz), Search, Batch rename.

### Standard modules to add (priority order)
| # | Module | Scope | Effort |
|---|---|---|---|
| 1 | Image viewer | Zoom/pan, EXIF basics, swipe between files in folder; thumbnails in grid | M |
| 2 | Media player | `MediaPlayer`/`VideoView` audio and video, no extra dependencies | M |
| 3 | Properties and Checksums | Size, perms, MD5/SHA-1/SHA-256, folder size | S |
| 4 | Trash / Recycle | Move to app trash, restore, auto purge | M |
| 5 | Bookmarks and Recents | Pinned folders, recent files, shown as storage shortcuts | S |
| 6 | Storage analyzer | Biggest files/folders, duplicate finder by size+hash | M |
| 7 | PDF viewer | `PdfRenderer` (API 21+), page nav | M |
| 8 | Compress formats | 7z/rar *read* only if pure-Kotlin and small; otherwise skip | L |
| 9 | Remote storage | SFTP/FTP/SMB as `StorageProvider`; likely a separate flavor | L |
| 10 | Root / Termux bridge | Open terminal here, run script (opt-in) | M |

Other candidates: SQLite viewer (read-only), JSON/CSV table view, diff viewer (two files), APK inspector (manifest, permissions), Clipboard history.

### Per-module checklist (Definition of done)
- Implements the contract, with an id and a title string resource.
- Unit tests for the pure logic (follow `ZipOpsTest`/`TarOpsTest`).
- Large-file safe: paged or streamed, never loads whole files.
- Works with files from `OpenActivity` (copy in cache, write-back).
- Adds under ~30 KB to the release APK; documented in README.

## Risks
**High**
- Refactoring `MainActivity` without tests can regress selection/back-stack behavior. Mitigation: do Phase 1 with zero behavior change, add tests first.
- `MANAGE_EXTERNAL_STORAGE` plus remote modules widens the attack surface. Mitigation: keep network modules in a separate flavor.

**Medium**
- Over-engineering: the contract grows before the second action module exists. Mitigation: only add extension points that an actual module needs.
- APK size creep. Mitigation: CI size budget.

**Low**
- Priority conflicts between modules. Mitigation: a deterministic order plus a user override in settings.

## Triggers to revisit
- More than ~8 modules, or a request for a slim build → Phase 4 (Gradle split).
- Third-party module requests → option C, designed as its own ADR.
