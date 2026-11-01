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

**DSHBox** is an open-source app that runs the full **DeepSeek Harness (DSH)** locally on Android phones and tablets. It brings DSH, PRoot, System WebView or embedded GeckoView, Debian and Node.js together with a terminal, file manager, AI browser assistant, phone assistant and optional developer tools to form a personal AI workbench. Root access and a separate Termux installation are not required for normal use.

---

## Quick installation

| Item | Description |
|---|---|
| System requirements | Android 10+ · ARM64 |
| Permissions | No root and no Termux are required for normal use. Root is optional and available only through the developer-mode channel. |
| Available builds | **System WebView build**: smaller APK, reuses the device's system WebView. **Embedded Gecko build**: includes GeckoView 158 and does not depend on system WebView. |
| Bundled components | DSH `0.2.1-rc.5` · PRoot · System WebView or GeckoView. Minimal Debian and Node.js are installed through online mirror selection or offline import on first run. |
| First-run setup | Follow the setup guide to install minimal Debian and Node.js. Multiple mirrors, retries and offline import are supported. The System WebView build also checks the provider and document-start compatibility. |
| **Which build should I use?** | Prefer the smaller System WebView build when the device WebView is current and works correctly. Use the embedded Gecko build when system WebView is old, vendor-modified, blank, or when a fixed rendering engine is preferred. Both APKs use the same package name and signing key; switching by overwrite installation preserves Debian, sandbox and user-data. |

**[Download the System WebView or Embedded Gecko build](https://github.com/WSK-build/DSHBox/releases/latest)** → install by overwrite → launch the app → follow the guide to install the runtime through online mirrors or offline import → open DSH.

---

## What's new

Current version: **v1.5.1** (2026-10-31) — **System WebView / embedded Gecko rendering · developer mode · plugin marketplace enhancements.**

1. Added **System WebView and embedded Gecko APK variants** that share the same DSH page and app UI. System WebView startup compatibility was extended, while GeckoView 158 provides an independent renderer for old or vendor-modified WebView environments.
2. Added **developer mode**, with full ADB and Root channels independent of Pilot approvals and Shizuku. ADB can be mounted into the Debian sandbox while enabled and can connect to either a local ADB service or external devices.
3. **Plugin marketplace and toolchain upgrades**: bundled pnpm 11.1.2, compatibility checks, install guards, installed-state handling and catalog refresh fixes. DSH's Add Plugin flow and bundled-plugin removal after third-party installs were fixed.
4. **DSH compatibility and interaction fixes**: corrected link navigation overwriting the DSH page, login handoff, side-panel resources, configuration-file opening, 401 recovery, Gecko keyboard viewport behavior and low-WebView blank-page failures.
5. **Website and release workflow upgrades**: the website offers both APKs, and Releases now output `dshbox-v1.5.1-system-release.apk` and `dshbox-v1.5.1-gecko-release.apk` with release-contract validation.

See **[CHANGES.md](CHANGES.md)** for the full release history.

---

## Core features

### Fully integrated DeepSeek Harness with dual rendering (v1.5.1)

- DSH `0.2.1-rc.5` is bundled with the APK and the layer remains unmodified upstream code. On first launch it is provisioned into `runtime-current/dsh` using **version arbitration**; layer replacement keeps one backup in `previous/dsh` and never touches user data.
- **System WebView and embedded Gecko builds**: both variants share one DSH page, host navigation, file delivery, diagnostics and logs. Differences are isolated in the Renderer Adapter. Both APKs use `com.dshbox.app`, the same signing key, and preserve `filesDir` when installed over each other.
- **Full-screen DSH workspace**: the DSH tab opens the local URL, resolves the launch token automatically, uses a mobile user agent, handles keyboard and visual-viewport behavior and supports pinch-to-zoom. A draggable floating control provides refresh and shortcuts to Home, Files, Terminal and Browser, while Home retains the Browser and More entries.
- **System WebView compatibility**: required startup APIs are installed before the DSH main script through a single document-start injection. Providers that support document-start remain usable; providers without that capability are blocked with an explicit upgrade message.
- **Embedded GeckoView 158**: DSH runs with a bundled rendering engine and never silently falls back to system WebView. The Gecko path handles DSH login popups, external links, 401 recovery, file selection, document status, the side panel and keyboard viewport differences.
- **DSH access settings**: the Home DSH card opens a full-screen access panel for viewing and copying the tokenized URL, changing external-link behavior, copying connection information and checking service uptime.
- **Runtime hard-link compatibility shim**: Android app-data filesystems (FBE/FUSE) reject hard links, while DSH uses `link()` to publish a file without overwriting an existing one. This can break session persistence, file tools and attachment publishing. DSHBox preloads a shim with `node --import` and replaces `node:fs/promises.link`; when the platform rejects a hard link, it falls back to a content copy that retains no-overwrite semantics.
- **No DSH source changes**: compatibility code lives in the app, renderer adapters, bundled extensions and runtime wiring. The bundled DSH layer remains pure upstream code.
- The foreground-service notification provides Start / Restart / Stop shortcuts. Reloads and reauthentication always use the current launch token, so a token change after restart or health recovery is handled correctly.

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

### Developer mode (added in v1.5.1)

Open **Developer mode** from Pilot. It provides full capabilities for advanced users and is separate from Pilot's controlled approval system:

| Channel | Capabilities and limits |
|---|---|
| ADB | When enabled, a complete ADB client and its dependencies are mounted into the Debian sandbox and removed when disabled. Supports local services, wireless-debugging pairing, external-device debugging, ports and custom keys. |
| Root | Uses the device's existing `su` implementation and provides the `android-root` CLI in the guest. Supports access codes, repeated-failure lockout, Unix/TCP transports and permission prechecks. |

- **Separate from the phone assistant**: ADB and Root do not pass through Pilot's per-capability Deny / Ask / Full access approvals or action restrictions. After enabling a channel, the user chooses how to use it and is responsible for debugging, system and Root operations and their consequences. Pilot retains its controlled, approval-based permissions.
- **Separate from Shizuku**: developer-mode ADB does not depend on Shizuku and can serve as an alternative for some Shizuku compatibility gaps or unresolved issues. Switches, keys, server configuration and process lifetime are independent.
- **External devices**: this ADB client can connect both to the local ADB service and to other devices over the network. Disabling the channel removes the command entry point, server access and sandbox mount.
- **Before enabling**: wireless debugging must be supported and the networks must be reachable. Root requires a usable `su` implementation and may vary across Magisk, KernelSU, APatch, hiding policies and vendor ROMs.

### Plugin management and marketplace (added in v1.4.0, enhanced in v1.5.1)

Access plugin management from Home's Cordis tab:

| Capability | Description |
|---|---|
| Bundled DSHBox plugin switches | DSH mobile page adaptation (`dsh-mobile-adapt`), DSH phone connection (`mobile-pilot`) and DSH browser automation (`browser-use`). Enabled by default for new users; upgrades preserve an explicit disabled setting. Provisioning and updates are coordinated with DSH readiness. |
| Official DSH plugins | Switches for official features disabled upstream by default, such as the side-panel browser |
| Absolute safe mode | Starts without third-party plugins to recover from plugin-induced DSH crashes |
| Plugin loading records | Records grouped by startup for troubleshooting |
| Crash-recovery assistance | The terminal agent **opencode** can be installed, updated or removed with one tap. Run `opencode` in the terminal to diagnose and fix issues using natural language. |
| Plugin marketplace | App-level browsing, search, compatibility checks, install guards and third-party plugin installation, updating and removal |
| Installed plugins | Local installed items are shown first while the remote catalog loads asynchronously. States distinguish active, restart required, pending disable, host denied, incompatible and broken, avoiding a full-page wait on weak networks. |
| Bundled-plugin protection | Mobile adaptation, Pilot and browser-use are registered as profile dependencies and are no longer removed when pnpm recalculates `node_modules` after a third-party install, update or deletion. |

The marketplace uses two live data sources: **awesome-dsh-plugin** and **[awesome-dsh-mobile-plugins](https://github.com/WSK-build/awesome-dsh-mobile-plugins)**. The latter was created by DSHBox specifically to expand the mobile DSH plugin ecosystem; contributions through pull requests are welcome.

- **Bundled pnpm 11.1.2**: ships with a Corepack cache and probes the official npm registry plus mirrors in mainland China. The same pnpm store, registry and Corepack configuration is used by DSH, the official Add Plugin entry, the marketplace and the terminal.
- **Official Add Plugin works end to end**: package names and links now resolve metadata, install and uninstall correctly. Missing `pnpm`, rejected registry arguments and inconsistent store paths were fixed.
- **State labels reflect when a change takes effect**: enable or disable operations that require a DSH restart display “restart required”. Host-denied, incompatible, base-library and marketplace-internal items no longer show switches that cannot succeed.
- **Compatibility boundary**: the marketplace checks known host-version, package-carrier and dependency issues, but cannot guarantee that every third-party plugin works with every DSH release. Back up before upgrading to an Alpha; downgrading to an RC may cause conversation-data incompatibility.

### DSH mobile and dual-renderer adaptation (v1.5.1)

The bundled DSH is now **0.2.1-rc.5**. System WebView and Gecko share one DSH page and host implementation. Adaptation is limited to the app, renderer adapters, bundled page-compatibility assets and extensions; **DSH source code and business logic remain unchanged**:

| Component | Mobile difference or issue | Handling |
|---|---|---|
| System WebView startup | Older providers lack standard APIs required by DSH or do not support document-start | Required APIs are supplied before the DSH main script; providers that cannot guarantee a safe startup phase are blocked with an upgrade message |
| Gecko page bridge | GeckoView does not expose the same callbacks and extension environment as System WebView | Bundled extensions, document-status messages, navigation generations and native events provide the required behavior |
| Login | DSH opens a blank popup and receives the authorization URL asynchronously | Both renderers recognize popups only from a trusted DSH source. Browser authorization is handed to the system default browser |
| Links inside DSH | Loading a link in the main frame can replace the active DSH workspace, while side-panel iframes must remain embedded | “Default browser” sends top-level cross-origin HTTP(S) links to the system browser. “In-app side panel” targets keep their iframe behavior |
| 401 and reauthentication | Concurrent navigation, redirects and late callbacks can attribute state to the wrong document | Navigation nonces, response-header state and document status are correlated; results from old generations are discarded |
| Attachments | File-picker callbacks, multiple selection and source detection require native integration | Photos, video, gallery, phone files, audio, workspace and shared-area sources are supported; results are deduplicated and stale `accept` sentinels are cleared |
| Voice input | WebView recording requires an additional audio-settings permission | Required permissions are declared and connected to DSH voice input |
| Side-panel file preview | Authorities in nonstandard schemes may parse differently across engines | `dsh-resource://` parsing is completed in the app with consistent System and Gecko behavior |
| Open configuration file | The old page-interception path failed when the plugin was disabled or timing changed | The runtime exposes an `xdg-open` link that opens DSHBox's configuration viewer; existing files are never overwritten |
| Keyboard and top bar | Gecko may shift the layout viewport when the keyboard opens | Only the Gecko path compensates for the remaining visual-viewport difference and pins the conversation top bar; System WebView keeps its original behavior |
| Side-panel terminal | The guest `passwd` lacks the runtime UID and `os.userInfo()` can fail | The current UID is added to guest `passwd` at runtime |
| Side-panel browser | The relevant official plugin is disabled upstream by default | A switch in plugin management enables it after DSH restarts |
| Photos / video recording | DSH needs system camera and recording entry points | File-input accept sentinels route the request to the system camera or recorder |
| Page diagnostics | Load completion is not the same as readiness, and main-thread APIs do not prove Worker support | Diagnostics records document, main-thread, Worker, Gecko and page state separately without presenting unsupported behavior as working |

Device Back handling, autofocus suppression, floating shortcuts and the attachment source picker continue from v1.5.0. v1.5.1 moves plugin-dependent URL parsing and configuration-file opening into the host, while explicitly limiting Gecko keyboard behavior to the Gecko path so System WebView is not affected.

### Layered PRoot runtime (no root required)

Four layers are provisioned independently. The PRoot userspace sandbox is separate from the Android host; runtime layers and user data (`user-data/` → guest `/root/projects`) are stored separately:

| Layer | Contents | Guest mount point |
|---|---|---|
| base | Minimal Debian 13 (trixie) rootfs | `/` (rootfs) |
| node | Node.js 24 (npm / npx / corepack) | `/usr/local` |
| dsh | DeepSeek Harness `0.2.1-rc.5` (unmodified upstream npm package) | `/opt/dshapp/runtime` |
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
| Diagnostics and logs | DSH, sandbox and guest-command logs can be scrolled, copied and exported. Renderer diagnostics distinguish System provider, Gecko version, document state, main-thread APIs, Worker environment and known visual limitations. Load completion or an engine version alone is not treated as proof that DSH is usable |
| Feedback | GitHub Star and issue-reporting entries; each explains the action before opening the destination |

### Storage usage and cleanup

- Storage accounting follows system conventions, using allocated blocks, deduplicating hard links and including app caches; it refreshes when Settings opens and can be refreshed manually.
- Cleanup categories can be selected separately: app cache, guest temporary files (only items older than 24 hours while running), truncated runtime logs and apt download caches. Rollback backups are optional and carry an explicit warning.
- Cleanup and background installation/import are mutually exclusive. Cleanup never touches `user-data/.dsh` or the runtime layers themselves.

### Terminal

- Multiple sessions: a sandbox terminal with the full PRoot Debian environment and tools such as bash, vim, htop, node, npm, apt, git, python3 and ssh; a restricted shell fallback; a floating panel for creating, switching and closing sessions
- **Direct DSH CLI access** (added in v1.4.0): run the official `dsh` commands (web / headless / tui / plugin) in the sandbox, using the same DSH installation and profile as the web interface
- **Developer-mode commands**: enabling the ADB channel exposes the complete `adb` client in the sandbox. When the Root channel is enabled and the device grants access, `android-root` is also available. Disabling a channel removes its command entry point
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
| Home | Control / Pilot / Cordis sub-tabs: sandbox and DSH status, Start / Restart / Stop, system metrics, phone assistant, developer-mode entry and plugin management. Settings is in the upper-right corner. |
| Files | Workspace / sandbox / shared-area browsing, move / rename / delete, multi-select operations, import / export / sharing, incoming files, viewers/editors, search/sort, new files/folders and `.deb` installation |
| DSH | Loads `http://127.0.0.1:3080` through System WebView or embedded GeckoView, with automatic authentication, keyboard and page compatibility, floating refresh and shortcuts for switching modules |
| Terminal | Multiple sessions, extra keys, control panel and direct `dsh` CLI access |
| Browser | Embedded browser and AI web assistant, element/area feedback, local previews, history/bookmarks/downloads and debugging settings |

Settings has moved to Home's upper-right corner and retains appearance options (theme/language), storage and cleanup, app updates, DSH/runtime updates and imports, diagnostics, feedback and About.

## Known limitations

- **System WebView support depends on provider capabilities**: some tested WebView 101 and 124 environments can complete the main DSH workflows. Providers without document-start or the required standard APIs are blocked with an upgrade message. A WebView version number alone does not guarantee that every DSH page works.
- **Worker / PDF support is separate from the main page**: System WebView Workers use an independent environment. The current app diagnoses Worker capability but does not inject Worker compatibility shims. PDF, Excel and other high-version Worker paths may require a newer WebView or the embedded Gecko build.
- **Both APKs cannot be installed at the same time**: the System WebView and Gecko builds use the same package name and signing key and replace each other. Switching variants does not erase Debian, sandbox, user-data or workspaces.
- **Developer mode is an advanced capability**: ADB and Root bypass Pilot approvals. Users must understand the risks of wireless debugging, external-device access and Root commands and are responsible for their actions. `su`, wireless debugging, hiding policies and authorization behavior may differ across ROMs.
- **Plugin compatibility depends on both the host and the plugin**: the marketplace checks known host capabilities, package carriers and dependencies, but cannot guarantee every third-party plugin works with every DSH release.
- **Gecko content-script injection differs on some new Android versions**: current login, external-link, configuration-file and navigation paths use native events, extension status messages or runtime links. Any future feature requiring direct page main-world injection must be revalidated on those systems.
- **Back up before upgrading to an Alpha**: downgrading from an Alpha to a stable RC may cause conversation-data incompatibility.

## Build from source

| Environment | Version |
|---|---|
| JDK | 21 (official CI uses Temurin 21) |
| Android SDK | compileSdk 37.2 · targetSdk 36 · minSdk 29 |
| Gradle | Wrapper 9.5.0 (AGP 9.3.1 · Kotlin/Compose compiler plugin 2.4.20) |
| Kotlin | Compiler 2.4.20; language/API level pinned to 2.2; JVM target 17 |

> The large runtime layers are **not in this repository**. Because of their size, they are distributed as separate GitHub Release assets. Obtain `../runtime/` before building; see the next section.

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleSystemWebViewRelease :app:assembleGeckoRelease
pwsh tools/assemble_release_apks.ps1
```

The release archive step produces:

- `apk/dshbox-v<version>-system-release.apk`
- `apk/dshbox-v<version>-gecko-release.apk`

It then runs the release contract, validating DSH compatibility assets, Gecko extension permissions, script hashes, package identity and signing.

| Module | Responsibility |
|---|---|
| `app` | UI for five tabs, DSH access settings, viewers, Settings, diagnostics and updates; both renderer variants; foreground service; online updates and runtime installation |
| `dsh-renderer` | `api`: shared renderer contract, navigation authentication and file delivery; `system`: System WebView compatibility and capability probes; `gecko`: GeckoView 158 rendering, extensions, popups and file selection |
| `sandbox-manager` | Layer provisioning, PRoot process management, DSH version arbitration and updates, import, verification and cleanup |
| `common` | Constants, npm mirrors, version comparison and log redaction |
| `bridge` | WebView JS Bridge security framework (reserved stub) |
| `terminal-session` | Session management, multiple terminals and PRoot terminal command construction |
| `terminal-view` / `terminal-emulator` | Termux terminal libraries (v0.118.0, unmodified) |
| `plugin-manager` | Plugin provisioning, marketplace catalog retrieval, compatibility checks and install guards; official plugin switches, absolute safe mode, loading records and opencode recovery assistance |
| `pilot` (`interlock-relay-core` + `dshbox-adapter` + `developer-access`) | Phone-assistant core and UI adaptation; developer-mode ADB, Root, wireless pairing, devices, keys and channel UI |
| `browser` (`browser-api` / `browser-runtime` / `browser-bridge` / `browser-devtools`) | Browser contracts, WebView session runtime, sandbox tool bridge and controlled advanced debugging; UI integration is provided by app |

Runtime-layer build scripts are in `runtime-bundle/`. The layer `tar.zst` archives are produced by `build_base.sh`, `build_node.sh` and `build_android_side.sh` on Linux/WSL2. See `docs/BUILD_RUNBOOK.md` for build instructions and `tools/pipeline_dryrun.sh` for preflight checks. When building from source, you must first extract the `android-side` layer from the runtime archive published in a GitHub Release and place it at `runtime/android-assets/runtime/android-side.tar.zst`; otherwise the resulting APK lacks the required layer and cannot install a working runtime.

The Release page also provides a complete offline runtime package containing base / node / android-side, separate from the APK. Since v1.4.0, the APK bundles only the `dsh` and `android-side` layers; `base` and `node` are assembled through online runtime installation or imported from that offline package.

## Large runtime files (not in this repository)

| Path in the distribution | Contents |
|---|---|
| `runtime/android-assets/runtime/android-side.tar.zst` | Android-side PRoot / loader / shmem and a `.sha256` sidecar; **bundled in the APK** |
| `runtime/android-assets/dsh/0.2.1-rc.5.tar.zst` | DSH layer and `.sha256`; unmodified upstream code, **bundled in the APK** |
| `runtime/offline-baseline/{base,node}.tar.zst` | Debian and Node layers with individual `.sha256` sidecars and `runtime-profile.json`; **not in the APK**, supplied in the offline package |
| `runtime/dshapp-runtime-debian-arm64-0.1.0.zip` | Complete runtime package for offline import |

- Asset inclusion: `app/build.gradle.kts` uses `assets.directories.add("../../runtime/android-assets")` to reference the distribution directory, packaging its contents under `assets/runtime/*` and `assets/dsh/*`.
  - **Only files inside the directories configured by `assets.directories.add` are included.** Moving `base` and `node` to `runtime/offline-baseline/` keeps them outside the APK. Inspection of the runtime assets confirms that the APK contains `assets/dsh/*` and `assets/runtime/android-side.tar.zst`.
- Build prerequisite: obtain `runtime/` before `assembleSystemWebViewRelease` and `assembleGeckoRelease`; without it, the required runtime assets cannot be bundled. Since v1.4.0, this prerequisite applies to the bundled `dsh` and `android-side` layers.
- Signing configuration (`keystore.properties` and `local.properties`) is not included in the repository. Use `tools/create_keystore.sh` to create a development signing key; without release signing configuration, release builds fall back to debug signing.

## Offline runtime import

- Distribution: a single `dshapp-runtime-debian-arm64-0.1.0.zip` file, selected through the offline runtime import option in Settings; available alongside online installation
- Layout and formats: outer ZIP/TAR archives and layer archives in `.tar.zst / .tar.gz / .tar / .tgz / .bz2 / .xz`, detected by magic bytes; one enclosing directory prefix and TAR containers holding layer archives are supported
- Verification: per-layer SHA-256 checks against sidecars and `runtime-profile.json`, plus Zip-Slip protection; damaged, truncated or encrypted packages return readable errors
- Replacement: previous layers move to `previous/`, retaining one rollback copy; the DSH layer and `user-data/.dsh` remain untouched.
- Offline DSH updates use the separate DSH offline update option with a single-layer archive such as `0.2.1-rc.5.tar.zst`; online updates remain available.

## License

This project is licensed under **GPL v3**; see [LICENSE](LICENSE). Third-party components retain their own licenses. See `THIRD_PARTY_NOTICES.md` for details:

- PRoot (GPL-2+) · talloc (LGPL-3+) · Debian rootfs (individual packages' Debian copyright files)
- DeepSeek Harness / Cordis (MIT) · Node.js (MIT)
- Termux terminal-emulator / terminal-view (Apache-2.0, v0.118.0, unmodified)
- GeckoView 158 (MPL-2.0 and the licenses of its bundled components; only the embedded Gecko build contains it, with the upstream AAR unmodified)
- ZXing Core 3.5.3 (Apache-2.0, access-code QR support in developer mode)
- sora-editor (LGPL-2.1-or-later, unmodified AAR) · Markwon (Apache-2.0) · commons-compress (Apache-2.0) · zstd-jni (BSD-2-Clause for the JNI wrapper; BSD-3-Clause for the Zstandard core)
- interlock-relay-core (Apache-2.0, independently published phone-assistant platform, source distributed with this repository) · Shizuku (MIT, the phone assistant's shell-identity execution backend)

The three DSHBox plugins, `dsh-mobile-adapt`, `mobile-pilot` and `browser-use`, each include a complete LICENSE and NOTICE and declare **GPL-3.0-only**. Third-party dependencies retain their original licenses. Bundled license documents are available offline under Settings → About → Open-source licenses; see [`licenses/README.md`](licenses/README.md) for origins and source rebuild instructions.

**Brand assets are not covered by those license grants.** The app name, app icon and DSHBox-owned in-app graphics listed in [`TRADEMARK.md`](TRADEMARK.md) remain the exclusive property of the rights holder, with all rights reserved. They may not be used for modified or derivative versions without permission, or as identifiers likely to cause confusion.
