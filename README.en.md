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

**DSHBox** is an open-source app that runs the full **DeepSeek Harness (DSH)** locally on Android phones and tablets. It integrates and coordinates DSH, PRoot, WebView, Debian and Node.js, together with a terminal, a file manager and other tools, into a personal AI workbench. No root access, and no separate Termux installation.

---

## Quick install

| Item | Description |
|---|---|
| System requirements | Android 10+ · ARM64 |
| Permissions | No root · No Termux |
| Bundled | DSH layer · WebView · PRoot (Debian and Node.js are instead fetched online after probing multiple mirrors on first run, or imported offline) |
| Package | about 111 MB (Release) |
| First-run setup | New users follow the setup guide to fetch the runtime online (mirror-probed minimal Debian layer + Node.js layer), or import the full offline package |

**[Download the latest APK](https://github.com/WSK-build/DSHBox/releases/latest)** → install → open the app → follow the guide to get the runtime (online mirrors, or the offline import) → open DSH.

---

## What's new

Current version **v1.4.0**: **Phone assistant, plugin ecosystem, online runtime acquisition, and deep adaptation to DSH 0.2.0-rc.2**

1. New **Phone Assistant (DshPilot)**, which builds a mailbox channel between the embedded sandbox and the Android app layer so that DSH inside the sandbox can control the phone safely and under control (39 phone control capabilities; three execution paths — accessibility / Shizuku privileged / platform-direct; two execution modes — foreground / background virtual display; all governed by "deny / ask for approval / full access", plus floating-window questions);
2. New **plugin management panel and plugin market**, bringing DSHBox's own bundled plugins, DSH official plugins, user-made plugins and third-party plugins into one place, with an "absolute safe mode" and the terminal agent opencode (one-tap install / update / uninstall) to help resolve plugin problems;
3. **The runtime is no longer bundled in the package**. The settings page can probe multiple mirrors online, update and reset the **Node layer / minimal Debian layer** ("Get runtime online") and the **DSH layer** ("Update DSH (online)") separately (the offline import entry is still there), and **the APK drops from about 236 MB to about 111 MB**;
4. **Deep adaptation to DSH 0.2.0-rc.2**, enabling photo and video capture, voice input, the right-sidebar terminal and the right-sidebar embedded browser.

Full per-version changes: **[CHANGES.md](CHANGES.md)**.

---

## Highlights

### DeepSeek Harness fully embedded

- DSH ships inside the APK and is installed into `runtime-current/dsh` on first start through **version arbitration**: a newer installed layer is kept, and when layers are swapped the old one is backed up to `previous/dsh` (a single copy), never touching user data
- The `DSH` tab embeds a WebView that opens `http://127.0.0.1:3080`: it resolves the launchToken automatically to complete session authentication, uses a mobile UA, adapts to the keyboard, and supports pinch-to-zoom and a floating refresh button. The home page also keeps a one-tap entry that opens DSH in the system browser.
  
  Since v1.4.0 every page refresh reloads with the **current token**: once DSH has rotated its token (a restart, or a restart by the health loop), the page no longer gets stuck in a state where "even a manual refresh won't bring it back".
- **Runtime hard-link compatibility shim**: Android app data partitions (FBE/FUSE) reject hard links, while DSH uses `link()` as its primitive for "publish a file without overwriting it", which makes session persistence, file tools and attachment publishing fail. This version preloads a shim with `node --import` at startup and replaces `node:fs/promises`' `link` at runtime — when the platform refuses it, it degrades to a semantically equivalent content copy (the source file is kept, the "no overwrite" semantics are kept, and permission bits are filled in). **Not one byte of DSH source is changed**, so it is unaffected by upstream refactors and there is no patch-anchor drift
- **Cross-WebView-version adaptation**: some WebView versions do not resolve the authority of the non-standard scheme `dsh-resource://`, so `hostname` comes back empty → DSH cannot get the protocol name, and tapping a file in the right panel reports "file resource service unavailable". The plugin does **runtime capability probing + scope locking**: it only steps in when the anomaly is detected, leaving healthy kernels untouched
- The foreground service notification carries quick actions for "start / restart / stop"

### Phone Assistant (DshPilot, new in v1.4.0)

It ships a built-in **table of 39 phone control capabilities** and builds a **mailbox channel** (file delivery — no network, no shared memory) between the embedded sandbox and the Android app layer, so that DSH inside the sandbox can control and connect to the phone safely and under control. It is embedded as two layers, "UI adaptation + kernel platform"; the kernel is released independently under **Apache-2.0** in the [interlock-relay](https://github.com/WSK-build/interlock-relay) repository, and this repository consumes it and provides the UI adaptation layer.

| Execution path (three) | Description |
|---|---|
| Accessibility mode | The AI operates the screen directly in the foreground: tapping, swiping, long-pressing and so on |
| Shizuku privileged mode | Opens up system shell-level (adb) permissions; dangerous operations (such as deletions) are strictly limited, and some permissions are left for users to enable themselves, at their own risk |
| Platform-direct invocation | A set of function calls that go through the system's normal channels |

| Execution mode (two) | Description |
|---|---|
| Foreground | The user sees the operations as they happen, working with the accessibility / platform-direct paths |
| Background virtual display | You keep using the device in the foreground while the AI operates in the background; under the restrictions of some apps it automatically degrades to foreground operation |

- **Approval and interaction constraints**: every one of those links is constrained, and each capability can be set to "deny / ask for approval / full access"; interaction with the user happens through dialogs.
- **Floating-window questions**: when you leave the DSHBox screen, the AI can summon a floating window to ask a question, and you can select the relevant options, reject everything, or ask again.
- **Native tools for DSH**: the bundled DSH plugin `@local/mobile-pilot` provides a set of `phone_*` tools, so the agent can call our kernel mailbox mechanism directly. This plugin can be toggled freely under "DSH connect phone" inside "Cordis".

### Plugin management and plugin market (new in v1.4.0)

A new entry point on the home page brings plugin-related capabilities onto a single page:

| Capability | Description |
|---|---|
| Switches for bundled first-party plugins | The two bundled plugins "DSH mobile page adaptation" (`dsh-mobile-adapt`) and "DSH connect phone" (`mobile-pilot`); the switches show the **real installation result**, and restarting DSH applies the change. |
| DSH official plugins | Official entries that upstream ships disabled are exposed as switches (such as the right-sidebar embedded browser) |
| Absolute safe mode | Starts without loading any third-party plugins, for recovering startup when a third-party plugin crashes DSH |
| Plugin load log | Shows plugin loading by startup segment, to make troubleshooting easier |
| Crash repair assistant | The built-in terminal agent **opencode**, with one-tap install / update / uninstall; type `opencode` in the terminal to bring up the TUI and repair things by talking in natural language |
| Plugin market | Lives in the app layer, so it is unaffected by compatibility churn from fast DSH releases; browse and search plugins by category, and install / update / delete third-party plugins |

The market ships with two live data sources, **awesome-dsh-plugin** and **[awesome-dsh-mobile-plugins](https://github.com/WSK-build/awesome-dsh-mobile-plugins)**; the latter is a repository DSHBox created specifically for the mobile DSH plugin ecosystem, where users can submit PRs and build the ecosystem together.

### DSH mobile component adaptation (v1.4.0)

With the bundled DSH upgraded to **0.2.0-rc.2**, each functional component's breakage in a mobile WebView environment was worked through one by one, and capabilities that were previously unavailable upstream were enabled. The adaptation **is done entirely on the app side** (native bridge + cordis plugin), without changing DSH source:

| Component | Mobile breakage | Handling |
|---|---|---|
| Session persistence / file writes / attachment publishing | Android app data partitions reject hard links → EACCES | A runtime shim replaces `fs/promises.link`, degrading to a content copy (semantically equivalent; see above) |
| Attachment upload | WebView lacks `onShowFileChooser` → tapping upload does nothing at all | A native implementation of the file chooser callback + a source menu (sandbox file / import from Android / take photo / record video) |
| Right panel file preview | `host` parsing differences on non-standard schemes → "file resource service unavailable" | Runtime capability probing + a URL parsing compatibility layer, active only on affected kernels |
| "Open config file" in the settings panel | Upstream's file-creation step no longer runs after interception → always fails on a fresh device | The native side supplies the missing materialization logic (if the file already exists it is left exactly as it is, never overwriting user config) |
| Upload source detection | A leftover `accept` sentinel on the hidden file input pollutes DSH's native upload | Actively cleaned up when the result is filled in + a capture guard on the plugin side, as two layers of protection |
| Plugin installation | After an APK upgrade the plugin copy inside the profile is still the old version | Judged by content fingerprint at startup and refreshed automatically (user edits are not overwritten, and the failure reason is visible) |
| Kernel capability differences | Features such as `:has()` / `color-mix()` fail silently on old kernels | The diagnostics page provides a real kernel fingerprint and on-device CSS capability probing, for locating problems during troubleshooting |
| Voice input | WebView recording needs additional audio permissions | The permission declarations were filled in, so voice input works on the DSH page |
| Right-sidebar terminal | The guest `passwd` lacks a runtime uid → Node's `os.userInfo()` raises `ENOENT`, and the shell prompt degrades to `I have no name!` | The current uid is filled into the guest `passwd` at runtime (`GuestUserProvisioner`) |
| Right-sidebar embedded browser | Upstream ships this official plugin disabled | A switch is provided in the plugin management panel; turning it on takes effect immediately (with a DSH restart) |
| Photo / video capture | Upstream has no camera entry point | The `accept` sentinel of `onShowFileChooser` is intercepted and routed to the system camera / recorder |

### PRoot layered runtime (no root required)

Four layers installed independently; the PRoot userspace sandbox is isolated from the Android host, and the runtime and user data (`user-data/` → guest `/root/projects`) never write into each other:

| Layer | Content | Guest mount point |
|---|---|---|
| base | Debian 13 (trixie) minimal rootfs | `/` (rootfs) |
| node | Node.js 24 (npm / npx / corepack) | `/usr/local` |
| dsh | DeepSeek Harness (npm package) | `/opt/dshapp/runtime` |
| android-side | PRoot / loader / shmem (host side) | — |

- Sandbox keepalive and DSH are two independent PRoot processes; shutdown enumerates the whole process tree through `/proc` and SIGKILLs children first, leaving no orphans and holding no ports
- Every layer carries a SHA-256 sentinel, and integrity is verified layer by layer at startup, so damage can be identified and reinstalled
- Since v1.4.0 the **`base` and `node` layers are no longer bundled** (only `dsh` and `android-side` stay in the APK): they are installed by "Get runtime online" (minimal Debian layer / Node.js layer, with a choice of mirrors and a reset option) or by "import the full offline package". Layers assembled online have unnecessary locale packages, documentation and other unrelated components trimmed (hence "minimal Debian"), so they take up less space

### File management (major additions in v1.2.0)

- **Dual view**: workspace `/root/projects` + sandbox root (with the node and DSH layers overlaid), breadcrumb navigation, list / grid, sort by name / time / size, create folder, multi-select batch operations
- **Create file** (new in v1.4.0): create an empty file directly in the current directory
- **Install into sandbox** (new in v1.4.0): select a `.deb` and unpack-install it into the sandbox
- **Move to a folder**: a full-screen target picker (switch between sandbox / workspace, create folders, the source and its descendants greyed out to prevent cycles, a hint when the target is on another mount point); three conflict strategies (overwrite / skip / auto-rename + apply to all the rest); same-volume `renameTo` preferred, with a copy fallback (permission bits and timestamps preserved); "Rename" uses the same engine
- **Import**: multi-select batch import, archive extraction import, a per-item conflict decision, cancellable, with a summary when it finishes; ZIP filename encoding fixed, encrypted zips explicitly rejected
- **Export**: export a multi-selection to a directory (SAF), or pack it into a ZIP
- **Global search**: across the sandbox + workspace, searching file names and contents at the same time, with matching snippets in the results
- **Risk protection**: system directories / node / DSH layer / `.dsh` are graded and labelled, with a strong confirmation before any write

- **Universal file viewer / editor** — magic numbers + content sniffing + extension, classified in three tiers, so that every file has a UI:

| Type | Capability |
|---|---|
| Text / code | Sora Editor editing (line numbers / undo-redo / search / word wrap), json / yaml / shell / python / js / java+kotlin highlighting; automatic encoding detection + manual switching, lossy decoding forced read-only; size tiers (≤2 MB editable · 2–10 MB editable after confirmation · >10 MB read-only tail window); atomic save + external change detection + unsaved-change interception |
| Images | Pinch-to-zoom / double-tap to zoom, strip loading for very tall images, GIF / animated WebP, AVIF (Android 12+) |
| PDF | Native paged rendering; encrypted PDFs can take a password on Android 15+, and older versions are guided to open externally |
| Archives | Read-only browsing of zip / jar / apk / epub and the tar family (folder collapsing, encrypted entries marked), in-archive text preview, single-entry / full export; ZIP Chinese filenames are not garbled; 7z / RAR get an info card + open externally |
| Hex | Offset / Hex / ASCII columns, 64 KB random-access blocks, entropy estimation |
| Office | docx / xlsx text extraction, read-only; doc / xls / ppt get an info card + open externally |
| Markdown / HTML / SVG | md source editing + Markwon preview (tables included); html / svg rendered offline in a WebView (JS disabled, network disabled, destroyed on exit) |
| Unknown / binary | Hex view + a file info card; open externally / edit / share / export as a fallback |

### Updates and import management (settings page)

| Feature | Description |
|---|---|
| Update DSH (online) | Probes the official npm / Alibaba / Tencent Cloud / Huawei Cloud mirrors in parallel for version and latency → pick mirror and version (with a second confirmation for downgrades) → npm inside the sandbox pulls the full dependency tree → the layer swap restarts DSH automatically; runs in the background with live logs and can be cancelled (SIGKILL on the process tree) |
| Update DSH (offline import) | Single-file layer packages `.tar.zst / .tar.gz / .tar / .tgz` (or a zip containing one), staged extraction → shape validation → atomic layer swap, leaving no half-finished state on failure |
| Get runtime online | Probes the matching mirrors (reachability and latency), downloads, verifies GPG signatures, trims and assembles them into layers, separately for the **Node layer / minimal Debian layer**; step-by-step progress, cancellable, with failures reported by cause (package missing from the mirror / signature failure / not enough space, and so on); any layer can be reset at any time |
| Import runtime package offline | Replaces base / node / android-side as a whole package, with per-layer SHA-256 verification and a single `previous/` copy for rollback (see below); since v1.4.0 it is no longer distributed with the APK, but it is still offered separately on the Releases page, and it is equivalent to getting the runtime online. |
| Plugin management / plugin market | A separate page reached from the home-page entry: browse and install from the market, manage what is installed, toggle official plugins, absolute safe mode, plugin load log, crash repair assistant (see "Plugin management and plugin market" above) |
| Diagnostics and logs | DSH / sandbox / guest command logs, 150 lines each, scrollable and exportable as a merged file; includes the **real WebView kernel fingerprint** (provider / version / Chromium major version + on-device CSS capability probing) |
| User feedback | Two entries, GitHub Star and issue reporting, both showing a dialog before navigating away |

### Storage usage and cleanup

- Usage is counted the same way the system does (allocated blocks, hard-link deduplication, app cache included), refreshed automatically when you enter the settings page and refreshable by hand
- Cleanup items are checked independently: app cache / guest temporary files (while running, only entries older than 24 h are cleared) / run logs (truncated) / apt download cache; rollback backups are optional, with an explicit warning
- Cleanup is mutually exclusive with background installs / imports, and never touches `user-data/.dsh` or the runtime itself

### Terminal

- Multiple windows: sandbox terminal (a full PRoot Debian environment where bash / vim / htop / node / npm / apt / git / python3 / ssh work out of the box) / a restricted-shell fallback, with a floating control panel to create / switch / close windows
- **Drive the DSH CLI directly** (new in v1.4.0): run the official `dsh` commands (web / headless / tui / plugin) right inside the sandbox, sharing the same DSH and the same profile as the web UI
- **Bundled tools rounded out** (new in v1.4.0): `jq`, `sqlite3`, `patch`, `nano`, `strings` (binutils) and other system tools that an AI commonly needs when executing tasks; the tool set is **self-healing** — if a layer swap or an upgrade leaves something missing, it is installed again automatically, with no need to wipe data and start over
- A two-row auxiliary key bar (ESC / TAB / HOME / END / sticky CTRL / paste / arrow keys / page up-down / backspace / delete, DECCKM-aware), pinch-to-zoom font size 8–40sp
- Built on Termux terminal-emulator / terminal-view (v0.118.0, unmodified) plus our own `terminal-session` session layer

### Multilingual support (v1.3.0)

- A fully localized UI in the six official UN languages — English / 简体中文 / العربية / Español / Français / Русский; it follows the system by default, and falls back to English for unsupported languages
- Settings → Appearance gains a "Language" selector: the six languages plus follow-system, applied immediately with no restart; notifications, dialogs, terminal menus and the rest all follow the language
- Quantity strings follow each language's plural rules (six forms in Arabic, four in Russian, and so on); technical numbers such as file sizes, timestamps and hex offsets stay in Western digits
- Layout direction: always LTR globally (Arabic text renders correctly through Unicode bidi), and page navigation and layout are not mirrored as a whole
- A built-in i18n consistency gate (key coverage / placeholders / plural forms / hardcoded-string scanning) validates the six language resources in CI

## Interface (five bottom tabs)

| Tab | Function |
|---|---|
| Home | Sandbox / DSH status cards, start / restart / stop, uptime, open DSH in the system browser; entry cards for **DshPilot (phone assistant)** and **Cordis (plugin management · market)**; guidance when a new user is missing runtime layers |
| Files | Dual-view browsing, move / rename / delete, multi-select batch operations, import / export files, extract imported archives, export the selection as an archive, viewer / editor, search / sort, new file / folder, install `.deb` into the sandbox |
| DSH | An embedded WebView loading `http://127.0.0.1:3080` (automatic authentication, keyboard adaptation, floating-button refresh) |
| Terminal | Multi-window terminal, auxiliary key bar, control panel, and direct access to the `dsh` CLI |
| Settings | Appearance (theme / language), storage and cleanup, check for app updates, DSH updates (online / offline), runtime import (online / offline), diagnostics and logs, user feedback, about |

## Building from source

| Environment | Version |
|---|---|
| JDK | 21 (the official CI uses Temurin 21) |
| Android SDK | compileSdk / targetSdk 36 · build-tools 36.0.0 |
| Gradle | wrapper 8.11.1 (AGP 8.9.2 · Kotlin 2.0.21) |

> The large runtime layers are **not in this repository** (see the next section); fetch `../runtime/` before building.

```bash
./gradlew testDebugUnitTest     # full JVM unit tests (1203 cases in v1.4.0)
./gradlew :app:assembleRelease  # output: app/build/outputs/apk/release/app-release.apk
```

| Module | Responsibility |
|---|---|
| `app` | All UI (the 5 tabs, viewer, settings / diagnostics / update pages), foreground service, online update orchestration, online runtime acquisition |
| `sandbox-manager` | Layered runtime assembly, PRoot process management, DSH layer arbitration and updates, import / verification / cleanup |
| `common` | Constants, npm mirrors, version comparison, log redaction |
| `bridge` | WebView JS bridge security framework (reserved stub) |
| `terminal-session` | Terminal session layer (multi-window, PRoot terminal command construction) |
| `terminal-view` / `terminal-emulator` | Termux terminal libraries (v0.118.0, unmodified) |
| `plugin-manager` | Plugin management: installation control, plugin market (catalog fetching / compatibility checks / install guard), official plugin switches, absolute safe mode, plugin load log, crash repair assistant (opencode) |
| `pilot` (`interlock-relay-core` + `dshbox-adapter`) | Phone assistant: the kernel platform layer (independently released repository, Apache-2.0) + this app's UI adaptation layer |

Runtime layer build scripts live in `runtime-bundle/` (each layer's `tar.zst` is built on Linux/WSL2 by `build_base.sh` / `build_node.sh` / `build_android_side.sh`); the build manual is `docs/BUILD_RUNBOOK.md`, with a preflight script at `tools/pipeline_dryrun.sh`.

Note: the Releases page offers a packaged runtime bundle alongside each version (three layers: base / node / android-side, independent of the APK) that can be downloaded and used directly. Since v1.4.0 the APK only embeds the `dsh` and `android-side` layers; `base` and `node` no longer go into the package, and are either assembled on the spot by "Get runtime online" or imported offline from that bundle.

## Large runtime files (not in this repository)

| Path inside the release bundle | Content |
|---|---|
| `runtime/android-assets/runtime/android-side.tar.zst` | Host-side PRoot / loader / shmem, with a `.sha256` sidecar (**embedded in the APK**) |
| `runtime/android-assets/dsh/0.2.0-rc.2.tar.zst` | DSH layer + `.sha256` (pristine upstream, unpatched; **embedded in the APK**) |
| `runtime/offline-baseline/{base,node}.tar.zst` | The Debian layer and the Node layer, each with a `.sha256` sidecar and `runtime-profile.json`; **not in the APK**, delivered as part of the offline bundle |
| `runtime/dshapp-runtime-debian-arm64-0.1.0.zip` | The runtime bundle delivered externally (for offline import) |

- How it is referenced: `app/build.gradle.kts` points at the release directory through `assets.srcDirs("../../runtime/android-assets")`, and the build embeds it as `assets/runtime/*` and `assets/dsh/*`
  - The rule is right there in that line: **only what sits under the directory that `assets.srcDirs` points at goes into the package**; once `base` / `node` moved to `runtime/offline-baseline/` they stopped being embedded. The APK contents match this exactly — only `assets/dsh/*` and `assets/runtime/android-side.tar.zst`
- Build prerequisite: `runtime/` must be fetched before `assembleRelease`; without that directory the full runtime cannot be embedded — since v1.4.0 the prerequisite only applies to the `dsh` and `android-side` layers
- Signing keys (`keystore.properties`, `local.properties`) are not in the repository: create your own development signature with `tools/create_keystore.sh`; when it is unconfigured, the release build falls back to the debug signature

## Importing a runtime package offline

- Delivery form: a single `dshapp-runtime-debian-arm64-0.1.0.zip`, picked from "Import runtime package offline" in the settings page; it coexists with "Get runtime online"
- Layout and formats: outer zip / tar; layer archives `.tar.zst / .tar.gz / .tar / .tgz / .bz2 / .xz` (identified by magic number), tolerating one directory-prefix level and outer tars that contain layer archives
- Verification: per-layer SHA-256 (the sidecar cross-checked against `runtime-profile.json`) + Zip-Slip protection; damaged / truncated / encrypted packages return a readable error
- Replacement: the old runtime is moved into `previous/` (a single copy, for rollback), and the DSH layer and `user-data/.dsh` are never touched
- Offline DSH updates go through "Update DSH (offline import)" with a single-file layer package (such as `0.2.0-rc.2.tar.zst`), coexisting with online updates.

## License

This project is licensed under **GPL v3** (see [LICENSE](LICENSE)). Third-party components remain under their own licenses; see `THIRD_PARTY_NOTICES.md`:

- PRoot (GPL-2+) · talloc (LGPL-3+) · Debian rootfs (per each package's Debian copyright file)
- DeepSeek Harness / Cordis (MIT) · Node.js (MIT)
- Termux terminal-emulator / terminal-view (Apache-2.0, v0.118.0 unmodified)
- sora-editor (LGPL-2.1-or-later, aar unmodified) · Markwon (Apache-2.0) · commons-compress (Apache-2.0) · zstd-jni (BSD-3-Clause)
- interlock-relay-core (Apache-2.0, the phone assistant platform layer, independently released, source distributed with this repository) · Shizuku (MIT, the shell-identity execution path of the phone assistant)

**Brand assets are not covered by the licenses above.** The app name, the app icon and the project's own in-app brand graphics (listed in [`TRADEMARK.md`](TRADEMARK.md)) are held exclusively by the project rights holder, with all rights reserved; without permission they may not be used for modified / derivative versions, or in any way likely to cause confusion.
