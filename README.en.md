**English** | [简体中文](README.md)

# DSHBox — Run DeepSeek Harness Locally on Android

<img width="1772" height="884" alt="DSHBox running DeepSeek Harness locally on Android phones and tablets" src="https://github.com/user-attachments/assets/a9622b15-a348-4c81-a708-3684a208e59e" />

[![Latest Release](https://img.shields.io/github/v/release/WSK-build/DSHBox?display_name=tag&sort=semver)](https://github.com/WSK-build/DSHBox/releases/latest)
[![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)](https://github.com/WSK-build/DSHBox/releases/latest)
[![Architecture](https://img.shields.io/badge/Architecture-ARM64-0091BD?logo=arm&logoColor=white)](https://github.com/WSK-build/DSHBox)
[![License](https://img.shields.io/github/license/WSK-build/DSHBox)](https://github.com/WSK-build/DSHBox/blob/main/LICENSE)
[![Build Status](https://github.com/WSK-build/DSHBox/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/WSK-build/DSHBox/actions/workflows/android.yml)
[![Download APK](https://img.shields.io/badge/Download-APK-2EA44F?logo=github)](https://github.com/WSK-build/DSHBox/releases/latest)
[![Community Discussion](https://img.shields.io/badge/DSH-Community%20Discussion-8250DF?logo=github)](https://github.com/deepseek-ai/deepseek-harness/discussions/5801)
[![Website](https://img.shields.io/badge/Website-Official%20Site-10A37F?logo=github&logoColor=white)](https://wsk-build.github.io/DSHBox/)

**DSHBox** is an open-source app that runs the full **DeepSeek Harness (DSH)** locally on Android phones and tablets. It brings DSH, PRoot, WebView, Debian and Node.js together with a terminal, file manager, AI browser assistant and phone assistant to form a personal AI workbench. No root access or separate Termux installation is required.

---

## Quick installation

| Item | Description |
|---|---|
| System requirements | Android 10+ · ARM64 |
| Permissions | No root access · No Termux required |
| Bundled components | DSH layer · WebView integration · PRoot (Debian and Node.js are installed on first run through online mirror selection or offline import) |
| APK size | See the APK attached to the Release |
| First-run setup | Follow the step-by-step guide to install minimal Debian and Node.js; multiple mirrors, retries and offline import are supported. The guide also detects the WebView version and provides compatibility notices. |
| **Important** | DSH requires a relatively recent system WebView engine. Older versions may cause blank screens, workspace loading failures and other issues. If this happens, update Android System WebView through Google Play, or consult an AI assistant to find a WebView version and upgrade or replacement method suitable for your device. |

**[Download the latest APK](https://github.com/WSK-build/DSHBox/releases/latest)** → install → launch the app → follow the guide to install the runtime through online mirrors or offline import → open DSH.

---

## What's new

Current version: **v1.5.0** (2026-10-17) — **AI browser assistant · System file sharing · DSH interface and interaction upgrades.**

1. Added **Browser and AI web assistant tools**: the `browser-use` plugin and sandbox CLI expose 24 tools for web interaction, screenshots, element/area feedback, local web previews and controlled advanced debugging. Browser is separate from Pilot, which operates native phone apps.
2. Added a **system file sharing area**: create or reuse the DSHBox folder in the phone's public storage and mount other selected directories. Unmounting does not delete source files; uninstalling the app does not automatically remove files in these public shared directories.
3. **Upgraded the DSH interface and interactions**: full-screen display, floating shortcuts, an attachment button and a bottom source picker; fixes for multiple-file uploads, broken back-button handling and unexpected keyboard activation. The home screen has been reorganized, with Settings moved to its upper-right corner.
4. Added a **step-by-step setup guide and WebView version notices**, incoming files from other apps and outbound file sharing. Improved plugin provisioning on first install, DSH startup status and Pilot theme synchronization; the home-screen entry has been renamed from DshPilot to Pilot.

See **[CHANGES.md](CHANGES.md)** for the full release history.

---

## Core features

### Fully integrated DeepSeek Harness

- DSH is bundled with the APK and provisioned into `runtime-current/dsh` on first launch using **version arbitration**: a newer installed version is retained. When the layer is replaced, one backup of the previous layer is kept in `previous/dsh`; user data is left untouched.
- The `DSH` tab opens `http://127.0.0.1:3080` in an embedded WebView, automatically handling launchToken authentication, a mobile user agent, keyboard adaptation and pinch-to-zoom. The full-screen layout includes a draggable floating button for refreshing or switching quickly to Home, Files, Terminal and Browser. Home also retains a one-tap option to open DSH in the system browser.

  Since v1.4.0, every refresh reloads with the **current token**. If DSH changes its token after a restart or health-check recovery, refreshing no longer keeps using an expired token.
- **Runtime hard-link compatibility shim**: Android app-data filesystems (FBE/FUSE) reject hard links, while DSH uses `link()` to publish files without overwriting existing ones. This can break session persistence, file tools and attachment publishing. A shim preloaded with `node --import` replaces `link` in `node:fs/promises` at runtime: when the platform rejects a hard link, it falls back to an equivalent content copy, retaining the source file, preserving no-overwrite behavior and setting permission bits. **The DSH source remains unchanged**, avoiding source-patch anchors that can drift during upstream refactoring.
- **Compatibility across WebView engine versions**: some engines do not parse the authority of nonstandard schemes such as `dsh-resource://`, leaving `hostname` empty and causing DSH's side-panel file viewer to report that the file resource service is unavailable. The plugin uses **runtime capability detection and narrowly scoped handling**, intervening only when the issue is detected.
- The foreground-service notification provides Start / Restart / Stop shortcuts.

### AI browser assistant (Browser, added in v1.5.0)

An independent embedded browser workspace exposes **24 tools** to DSH through the bundled `browser-use` plugin and sandbox CLI. It is provisioned by default for new users and can be controlled separately through the DSH browser automation switch on Home's Cordis tab.

- **Web interaction**: search, navigation, multiple-page management, content reading, element location, clicks, typing, scrolling and condition-based waits. Search engines include Bing, Google and Baidu, with Bing as the default.
- **Selection feedback and local previews**: select an element or area and submit its screenshot and a comment to the bound DSH conversation. Use the CLI to serve HTML and other sandbox files through a local static server, or register an existing service, then preview it in Browser and send feedback for changes.
- **Screenshots and debugging**: capture web pages in the foreground or background. If ordinary background capture fails, a CDP fallback is attempted sequentially only when advanced debugging is enabled; otherwise, the tool returns an explicit explanation. Advanced debugging provides DOM inspection, console access, script evaluation, network capture and HAR export. Request interception, modification and replay require separate authorization.
- **Files and records**: persistent history, bookmarks, page and download management. AI can prepare files for upload, but user approval is required before handing them to the web page. Task records can be viewed, edited or deleted, and a continuation summary can be sent to AI.
- **Control and interface**: taking over from AI requires confirmation; when AI control is disabled, AI can request authorization. Browser follows the app's theme and six-language setting, supports mobile/desktop/wide-screen modes and a configurable idle timeout, and offers a quick return to DSH by tapping the AI indicator.

Browser handles **embedded web pages**; Pilot handles **native phone apps**. DSH chooses which tool to call. Complex task recovery across pages, long-running streamed responses and some media and site compatibility scenarios remain incomplete. Full automation is not guaranteed for every task.

### Phone assistant (Pilot, added in v1.4.0)

A built-in **catalog of 39 phone-control capabilities** connects sandboxed DSH with the Android app layer through a **file-based mailbox channel**, without network transport or shared memory. It uses a two-layer design: UI adaptation and a core platform. The core is independently published under **Apache-2.0** in [interlock-relay](https://github.com/WSK-build/interlock-relay); this repository integrates it and provides the UI adaptation.

| Execution backend (three) | Description |
|---|---|
| Accessibility | AI directly interacts with the foreground screen: taps, swipes, long presses and more |
| Shizuku privileges | System shell-level (ADB) access; dangerous operations such as deletion are restricted, while some permissions must be explicitly enabled by the user at their own risk |
| Platform APIs | Invokes capabilities through standard system interfaces |

| Execution mode (two) | Description |
|---|---|
| Foreground | Visible interaction through accessibility or platform APIs |
| Background virtual display | AI operates in the background while the user continues using the device; limitations imposed by some apps may require fallback to foreground operation |

- **Approval and interaction controls**: each execution path is governed by per-capability settings: Deny / Ask for approval / Full access, with dialogs for user interaction.
- **Floating questions**: after the user leaves DSHBox, AI can ask a question through a floating window. The user can select options, reject all options or ask for the question to be rephrased.
- **Native DSH tools**: the bundled `@local/mobile-pilot` plugin provides `phone_*` tools that the agent can call directly through the core mailbox mechanism. Enable it through the DSH phone connection switch in Cordis.

### Plugin management and marketplace (added in v1.4.0)

Access plugin management from Home's Cordis tab:

| Capability | Description |
|---|---|
| Bundled DSHBox plugin switches | DSH mobile page adaptation (`dsh-mobile-adapt`), DSH phone connection (`mobile-pilot`) and DSH browser automation (`browser-use`). Enabled by default for new users; upgrades preserve an explicit disabled setting. Provisioning and updates are coordinated with DSH readiness. |
| Official DSH plugins | Switches for official features disabled upstream by default, such as the side-panel browser |
| Absolute safe mode | Starts without third-party plugins to recover from plugin-induced DSH crashes |
| Plugin loading records | Records grouped by startup for troubleshooting |
| Crash-recovery assistance | The terminal agent **opencode** can be installed, updated or removed with one tap. Run `opencode` in the terminal to diagnose and fix issues using natural language. |
| Plugin marketplace | App-level browsing, search and third-party plugin installation, updating and removal. Plugin compatibility still depends on the DSH version; see the known issues below. |

The marketplace uses two live data sources: **awesome-dsh-plugin** and **[awesome-dsh-mobile-plugins](https://github.com/WSK-build/awesome-dsh-mobile-plugins)**. The latter was created by DSHBox specifically to expand the mobile DSH plugin ecosystem; contributions through pull requests are welcome.

> **Version compatibility**: this release does not include fixes for the outstanding plugin marketplace issues. Upstream Alpha releases change frequently, including substantial plugin changes; compatibility updates will follow DSH's next official RC. The latest Alpha is not bundled. In existing tests, upgrading through the DSH online update option in Settings worked, but downgrading to the stable RC may cause conversation-data problems. Future bundled versions will track the latest stable RC.

### DSH mobile component adaptation (v1.4.0)

After upgrading the bundled DSH to **0.2.0-rc.2**, DSHBox addressed integration gaps in mobile WebView environments and enabled previously unavailable capabilities. **All adaptation is implemented on the app side**, through native bridges and Cordis plugins; DSH source code is not modified.

| Component | Mobile integration issue | Handling |
|---|---|---|
| Session persistence / file writes / attachment publishing | Android app-data filesystems reject hard links with EACCES | Runtime shim replaces `fs/promises.link` with an equivalent copy, as described above |
| Attachment uploads | WebView file-picker callbacks and multiple-selection results require native integration | Native source picker offers photos, video recording, gallery, phone files, audio, workspace and shared-area files; multiple-selection results are collected and deduplicated |
| Side-panel file preview | Host parsing differs for nonstandard schemes, causing a file resource service error | Runtime capability detection and URL parsing compatibility handling, applied only to affected engines |
| Open configuration file in Settings | Interception prevents upstream file creation, causing failure on a fresh device | Native code creates the required file if missing; existing user configuration is retained without overwriting |
| Upload source detection | A stale `accept` sentinel on a hidden file input can interfere with native DSH uploads | Cleanup when returning results, plus a plugin-side capture guard |
| Plugin provisioning | A profile can retain an old plugin copy after an APK upgrade | Content fingerprints trigger automatic refresh at startup; user edits are not overwritten, and failures are visible |
| Engine capability differences | Missing features such as `:has()` or `color-mix()` can fail silently | Diagnostics shows the actual engine fingerprint and tests CSS capabilities on the device |
| Voice input | WebView recording requires an additional audio-settings permission | Permission declarations are supplied to enable DSH voice input |
| Side-panel terminal | The guest `passwd` lacks the runtime UID, making Node's `os.userInfo()` throw `ENOENT` and the shell show `I have no name!` | The current UID is added to guest `passwd` at runtime by `GuestUserProvisioner` |
| Side-panel embedded browser | Its official upstream plugin is disabled by default | A switch in plugin management enables it after restarting DSH |
| Photos / video recording | The upstream interface has no camera entry | The `onShowFileChooser` accept sentinel is intercepted to open the system camera or video recorder |

v1.5.0 further connects the device Back button with DSH's internal panel hierarchy and suppresses autofocus without a user gesture to reduce unexpected keyboard activation and page movement. The attachment toolbar has a direct entry; the native source picker can operate independently of the mobile adaptation plugin.

### Layered PRoot runtime (no root required)

Four layers are provisioned independently. The PRoot userspace sandbox is separate from the Android host; runtime layers and user data (`user-data/` → guest `/root/projects`) are stored separately:

| Layer | Contents | Guest mount point |
|---|---|---|
| base | Minimal Debian 13 (trixie) rootfs | `/` (rootfs) |
| node | Node.js 24 (npm / npx / corepack) | `/usr/local` |
| dsh | DeepSeek Harness (npm package) | `/opt/dshapp/runtime` |
| android-side | PRoot / loader / shmem on the Android side | — |

- Sandbox keepalive and DSH run as two independent PRoot processes. Shutdown enumerates the process tree through `/proc` and sends SIGKILL to children first to avoid orphan processes and occupied ports.
- Each layer carries SHA-256 verification metadata. Integrity is checked layer by layer at startup so damaged layers can be identified and reinstalled.
- Since v1.4.0, **the `base` and `node` layers are no longer bundled in the APK**, which contains only `dsh` and `android-side`. Install them through online runtime installation, with mirror selection and reset options for minimal Debian and Node.js, or import the complete offline package. Online assembly removes unnecessary language packs and documentation to reduce storage use.

### System file sharing area (added in v1.5.0)

- After permission is granted, DSHBox creates or reuses a **DSHBox** folder in the phone's public storage. The file manager, DSH, sandbox and terminal access the same local files, which can also be transferred through other apps or a computer.
- Other public directories can be selected and mounted under separate names in the sandbox sharing area. Both the file manager and attachment source picker provide access. Restart the relevant running sessions as prompted to apply new mounts.
- **Unmounting removes only the mount reference, not the source directory or its files.** Uninstalling or reinstalling the app does not automatically remove files in public shared directories. Reinstallation requires permission to be granted again and additional mounts to be configured again. Deliberately deleting files still takes effect.

### File manager (major improvements in v1.2.0)

- **Directories and views**: workspace `/root/projects`, sandbox root with the Node and DSH layers overlaid, and shared-area access; breadcrumb navigation, list/grid views, sorting by name/time/size, folder creation and multi-select batch operations
- **New files** (added in v1.4.0): create an empty file directly in the current directory
- **Install into the sandbox** (added in v1.4.0): select a `.deb` file to extract and install it into the sandbox
- **Move to a selected folder**: full-screen destination picker with sandbox/workspace switching and folder creation; the source and its descendants are disabled to prevent cycles, with warnings for destinations across mount points. Conflicts support overwrite / skip / automatic rename and Apply to all remaining items. Same-volume `renameTo` is preferred, falling back to copying with permissions and timestamps preserved; renaming uses the same engine.
- **Import**: multiple-file and archive imports, per-item conflict decisions, cancellation and completion summaries; corrected ZIP filename decoding for Chinese names, with encrypted ZIP files explicitly rejected
- **Export**: export selected files to a directory through SAF, or package them into ZIP
- **Incoming files and outbound sharing** (added in v1.5.0): accept files through other apps' Share or Open with actions and choose a destination; multiple files and plain text are supported. The file menu can open the system share sheet independently of Export.
- **Global search**: search filenames and contents across the sandbox and workspace, with matching snippets
- **Risk controls**: system directories, the Node and DSH layers, and `.dsh` are marked by risk level; write operations require explicit confirmation.

- **Universal file viewer/editor**: classification by magic bytes, content inspection and extension ensures every file has an appropriate view or fallback.

| Type | Capabilities |
|---|---|
| Text / code | Sora Editor with line numbers, undo/redo, search and wrapping; syntax highlighting for JSON, YAML, shell, Python, JS, Java and Kotlin; encoding detection and manual selection, with read-only mode forced for lossy decoding. Size tiers: ≤2 MB editable; 2–10 MB editable after confirmation; >10 MB read-only tail window. Atomic saves, external-change detection and unsaved-change prompts. |
| Images | Pinch-to-zoom and double-tap zoom; tiled loading for very tall images; animated GIF/WebP; AVIF on Android 12+ |
| PDF | Native page rendering; password entry for encrypted PDFs on Android 15+, with external-app guidance on older versions |
| Archives | Read-only ZIP/JAR/APK/EPUB and TAR-family browsing, collapsible directories and encrypted-entry indicators; text previews and single-entry/all-entry export; correct Chinese ZIP filenames; information cards and external opening for 7z/RAR |
| Hex | Offset / hexadecimal / ASCII columns, random access in 64 KB blocks and entropy estimation |
| Office | Read-only text extraction from DOCX/XLSX; information cards and external opening for DOC/XLS/PPT |
| Markdown / HTML / SVG | Markdown source editing and Markwon preview with tables; offline HTML/SVG rendering in a WebView with JavaScript and network access disabled, destroyed on exit |
| Unknown / binary | Hex view and file information; external opening/editing, sharing and export as fallbacks |

### Updates and imports (Settings)

| Feature | Description |
|---|---|
| Update DSH online | Probe npm's official registry and Alibaba, Tencent Cloud and Huawei Cloud mirrors for versions and latency → select a mirror and version, with a second confirmation for downgrades → fetch the full dependency tree through npm in the sandbox → replace the layer and restart automatically. Runs in the background with live logs and cancellation that kills the process tree. |
| Update DSH by offline import | Accept a single layer archive: `.tar.zst / .tar.gz / .tar / .tgz`, or a ZIP containing a layer archive. Extract to staging, validate the layout and replace atomically; failures leave the original intact. |
| Install the runtime online | Probe mirrors separately for the **Node** and **minimal Debian** layers for availability and latency, then download, verify GPG signatures, trim and assemble the layer. Provides step-by-step progress, cancellation and error categories such as missing mirror packages, verification failures and insufficient space; any layer can be reset. |
| Import a runtime package offline | Replace base / node / android-side with per-layer SHA-256 verification and one backup in `previous/` for rollback, as described below. Since v1.4.0, the package is distributed separately on the Release page rather than bundled in the APK, as an alternative to online installation. |
| Plugin management / marketplace | Access through Home's Cordis tab: marketplace browsing and installation, installed-plugin management, official plugin switches, absolute safe mode, loading records and opencode recovery assistance. See the plugin section and compatibility notice above. |
| Diagnostics and logs | The latest 150 lines each for DSH, sandbox and guest-command logs, with scrolling and combined export; includes the **actual WebView engine fingerprint**: provider, version, Chromium major version and on-device CSS capability tests |
| Feedback | GitHub Star and issue-reporting entries; each explains the action before opening the destination |

### Storage usage and cleanup

- Storage accounting follows system conventions, using allocated blocks, deduplicating hard links and including app caches; it refreshes when Settings opens and can be refreshed manually.
- Cleanup categories can be selected separately: app cache, guest temporary files (only items older than 24 hours while running), truncated runtime logs and apt download caches. Rollback backups are optional and carry an explicit warning.
- Cleanup and background installation/import are mutually exclusive. Cleanup never touches `user-data/.dsh` or the runtime layers themselves.

### Terminal

- Multiple sessions: a sandbox terminal with the full PRoot Debian environment and tools such as bash, vim, htop, node, npm, apt, git, python3 and ssh; a restricted shell fallback; a floating panel for creating, switching and closing sessions
- **Direct DSH CLI access** (added in v1.4.0): run the official `dsh` commands (web / headless / tui / plugin) in the sandbox, using the same DSH installation and profile as the web interface
- **Bundled utility tools** (added in v1.4.0): `jq`, `sqlite3`, `patch`, `nano` and `strings` from binutils for common AI-driven tasks. The tool bundle is **self-healing**: missing tools are restored after layer replacement or upgrades without clearing app data.
- Two rows of extra keys: ESC / TAB / HOME / END / sticky CTRL / Paste / arrow keys / Page Up and Down / Backspace / Delete, with DECCKM awareness; pinch to adjust the font from 8–40 sp
- Based on Termux terminal-emulator / terminal-view (v0.118.0, unmodified) and the custom `terminal-session` layer

### Localization (v1.3.0)

- The interface supports all six UN official languages: English / 简体中文 / العربية / Español / Français / Русский. It follows the system by default and falls back to English for unsupported languages.
- Settings → Appearance provides a language selector with six languages plus Follow system. Changes take effect immediately without restarting, including notifications, dialogs and terminal menus.
- Quantity text follows each language's plural rules, including six forms in Arabic and four in Russian. Technical numbers such as file sizes, timestamps and hex offsets retain Western digits.
- Layout stays LTR globally. Arabic text follows Unicode bidirectional rendering without mirroring the whole navigation layout.
- Built-in i18n consistency checks validate keys, placeholders, plural forms and hardcoded strings; CI checks all six language resource sets.

## Interface (five bottom tabs)

| Tab | Features |
|---|---|
| Home | Control / Pilot / Cordis sub-tabs: sandbox and DSH status, Start / Restart / Stop, system metrics, phone assistant and plugin management. Settings is in the upper-right corner. |
| Files | Workspace / sandbox / shared-area browsing, move / rename / delete, multi-select operations, import / export / sharing, incoming files, viewers/editors, search/sort, new files/folders and `.deb` installation |
| DSH | Full-screen WebView at `http://127.0.0.1:3080` with automatic authentication and keyboard adaptation; floating shortcuts for refresh and switching modules |
| Terminal | Multiple sessions, extra keys, control panel and direct `dsh` CLI access |
| Browser | Embedded browser and AI web assistant, element/area feedback, local previews, history/bookmarks/downloads and debugging settings |

Settings has moved to Home's upper-right corner and retains appearance options (theme/language), storage and cleanup, app updates, DSH/runtime updates and imports, diagnostics, feedback and About.

## Build from source

| Environment | Version |
|---|---|
| JDK | 21 (official CI uses Temurin 21) |
| Android SDK | compileSdk / targetSdk 36 · build-tools 36.0.0 |
| Gradle | Wrapper 8.11.1 (AGP 8.9.2 · Kotlin 2.0.21) |

> The large runtime layers are **not in this repository**. Obtain `../runtime/` before building; see the next section.

```bash
./gradlew testDebugUnitTest     # All JVM unit tests
./gradlew :app:assembleRelease  # Output: app/build/outputs/apk/release/app-release.apk
```

| Module | Responsibility |
|---|---|
| `app` | UI for five tabs, viewers, Settings, diagnostics and updates; foreground service; online update orchestration and runtime installation |
| `sandbox-manager` | Layer provisioning, PRoot process management, DSH version arbitration and updates, import, verification and cleanup |
| `common` | Constants, npm mirrors, version comparison and log redaction |
| `bridge` | WebView JS Bridge security framework (reserved stub) |
| `terminal-session` | Session management, multiple terminals and PRoot terminal command construction |
| `terminal-view` / `terminal-emulator` | Termux terminal libraries (v0.118.0, unmodified) |
| `plugin-manager` | Plugin provisioning, marketplace catalog retrieval, compatibility checks and install guards; official plugin switches, absolute safe mode, loading records and opencode recovery assistance |
| `pilot` (`interlock-relay-core` + `dshbox-adapter`) | Phone assistant: independently published Apache-2.0 core platform and this app's UI adaptation layer |
| `browser` (`browser-api` / `browser-runtime` / `browser-bridge` / `browser-devtools`) | Browser contracts, WebView session runtime, sandbox tool bridge and controlled advanced debugging; UI integration is provided by app |

Runtime-layer build scripts are in `runtime-bundle/`. The layer `tar.zst` archives are produced by `build_base.sh`, `build_node.sh` and `build_android_side.sh` on Linux/WSL2. See `docs/BUILD_RUNBOOK.md` for build instructions and `tools/pipeline_dryrun.sh` for preflight checks.

The Release page also provides a complete offline runtime package containing base / node / android-side, separate from the APK. Since v1.4.0, the APK bundles only the `dsh` and `android-side` layers; `base` and `node` are assembled through online runtime installation or imported from that offline package.

## Large runtime files (not in this repository)

| Path in the distribution | Contents |
|---|---|
| `runtime/android-assets/runtime/android-side.tar.zst` | Android-side PRoot / loader / shmem and a `.sha256` sidecar; **bundled in the APK** |
| `runtime/android-assets/dsh/0.2.0-rc.2.tar.zst` | DSH layer and `.sha256`; unmodified upstream code, **bundled in the APK** |
| `runtime/offline-baseline/{base,node}.tar.zst` | Debian and Node layers with individual `.sha256` sidecars and `runtime-profile.json`; **not in the APK**, supplied in the offline package |
| `runtime/dshapp-runtime-debian-arm64-0.1.0.zip` | Complete runtime package for offline import |

- Asset inclusion: `app/build.gradle.kts` uses `assets.srcDirs("../../runtime/android-assets")` to reference the distribution directory, packaging its contents under `assets/runtime/*` and `assets/dsh/*`.
  - **Only files inside the directories configured by `assets.srcDirs` are included.** Moving `base` and `node` to `runtime/offline-baseline/` keeps them outside the APK. Inspection of the runtime assets confirms that the APK contains `assets/dsh/*` and `assets/runtime/android-side.tar.zst`.
- Build prerequisite: obtain `runtime/` before `assembleRelease`; without it, the required runtime assets cannot be bundled. Since v1.4.0, this prerequisite applies to the bundled `dsh` and `android-side` layers.
- Signing configuration (`keystore.properties` and `local.properties`) is not included in the repository. Use `tools/create_keystore.sh` to create a development signing key; without release signing configuration, release builds fall back to debug signing.

## Offline runtime import

- Distribution: a single `dshapp-runtime-debian-arm64-0.1.0.zip` file, selected through the offline runtime import option in Settings; available alongside online installation
- Layout and formats: outer ZIP/TAR archives and layer archives in `.tar.zst / .tar.gz / .tar / .tgz / .bz2 / .xz`, detected by magic bytes; one enclosing directory prefix and TAR containers holding layer archives are supported
- Verification: per-layer SHA-256 checks against sidecars and `runtime-profile.json`, plus Zip-Slip protection; damaged, truncated or encrypted packages return readable errors
- Replacement: previous layers move to `previous/`, retaining one rollback copy; the DSH layer and `user-data/.dsh` remain untouched.
- Offline DSH updates use the separate DSH offline update option with a single-layer archive such as `0.2.0-rc.2.tar.zst`; online updates remain available.

## License

This project is licensed under **GPL v3**; see [LICENSE](LICENSE). Third-party components retain their own licenses. See `THIRD_PARTY_NOTICES.md` for details:

- PRoot (GPL-2+) · talloc (LGPL-3+) · Debian rootfs (individual packages' Debian copyright files)
- DeepSeek Harness / Cordis (MIT) · Node.js (MIT)
- Termux terminal-emulator / terminal-view (Apache-2.0, v0.118.0, unmodified)
- sora-editor (LGPL-2.1-or-later, unmodified AAR) · Markwon (Apache-2.0) · commons-compress (Apache-2.0) · zstd-jni (BSD-2-Clause for the JNI wrapper; BSD-3-Clause for the Zstandard core)
- interlock-relay-core (Apache-2.0, independently published phone-assistant platform, source distributed with this repository) · Shizuku (MIT, the phone assistant's shell-identity execution backend)

The three DSHBox plugins, `dsh-mobile-adapt`, `mobile-pilot` and `browser-use`, each include a complete LICENSE and NOTICE and declare **GPL-3.0-only**. Third-party dependencies retain their original licenses. Bundled license documents are available offline under Settings → About → Open-source licenses; see [`licenses/README.md`](licenses/README.md) for origins and source rebuild instructions.

**Brand assets are not covered by those license grants.** The app name, app icon and DSHBox-owned in-app graphics listed in [`TRADEMARK.md`](TRADEMARK.md) remain the exclusive property of the rights holder, with all rights reserved. They may not be used for modified or derivative versions without permission, or as identifiers likely to cause confusion.
