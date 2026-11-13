**English** | [简体中文](README.md)

# DSHBox — DeepSeek Harness on Android: Run Locally or Connect Remotely

<img width="1772" height="884" alt="DSHBox running DeepSeek Harness locally or remotely on Android phones and tablets" src="https://github.com/user-attachments/assets/a9622b15-a348-4c81-a708-3684a208e59e" />

[![Latest Release](https://img.shields.io/github/v/release/WSK-build/DSHBox?display_name=tag&sort=semver)](https://github.com/WSK-build/DSHBox/releases/latest)
[![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)](https://github.com/WSK-build/DSHBox/releases/latest)
[![Architecture](https://img.shields.io/badge/Architecture-ARM64-0091BD?logo=arm&logoColor=white)](https://github.com/WSK-build/DSHBox)
[![License](https://img.shields.io/github/license/WSK-build/DSHBox)](https://github.com/WSK-build/DSHBox/blob/main/LICENSE)
[![Build Status](https://github.com/WSK-build/DSHBox/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/WSK-build/DSHBox/actions/workflows/android.yml)
[![Download APK](https://img.shields.io/badge/Download-APK-2EA44F?logo=github)](https://github.com/WSK-build/DSHBox/releases/latest)
[![Community Discussion](https://img.shields.io/badge/DSH-Community%20Discussion-8250DF?logo=github)](https://github.com/deepseek-ai/deepseek-harness/discussions/5801)
[![Website](https://img.shields.io/badge/Website-Official%20Site-10A37F?logo=github&logoColor=white)](https://wsk-build.github.io/DSHBox/)

**DSHBox** is an open-source mobile app for **DeepSeek Harness (DSH)**. It can run DSH locally on your phone or tablet, or connect to DSH remotely, with a terminal, file manager, browser automation and phone control in one app — a personal AI workbench you can carry. No root or Termux required.

---

## Quick installation

| Item | Description |
|---|---|
| System requirements | Android 10+ · ARM64 |
| Permissions | No root and no Termux for daily use; the Root channel in developer mode is an optional capability |
| Release builds | **System WebView build**: smaller APK, reuses the phone's system WebView. **Embedded Gecko build**: ships with GeckoView 158 and does not depend on the system WebView |
| Local execution | Provisions a PRoot + Debian sandbox on the device with bundled Node.js and the DSH `0.2.2-rc.2` runtime layer to run DSH locally |
| Remote connection | Scan a QR code or paste a pairing link to connect to DSH on the same LAN or the public internet; supports proxy / direct connection with multi-device management and switching |
| **First-run setup** | Follow the beginner guide to install the two runtime layers, minimal Debian and Node.js, step by step, with multi-mirror detection, retry on failure or offline import |
| **Which package to choose** | **System WebView build:** when the system WebView is recent and works correctly (or you are able to upgrade the system WebView version), prefer the smaller System WebView package.<br />**Embedded Gecko build:** when the system WebView is old and hard to upgrade due to vendor customization — which can cause blank DSH pages, loading failures, workspace loading errors and other issues — choose the Gecko package.<br />Both APKs share the same package name and signature, can overwrite each other, and keep your data. |

**[Download the System WebView or embedded Gecko build](https://github.com/WSK-build/DSHBox/releases/latest)** → install by overwrite → launch the app → follow the guide to fetch the runtime (online multi-mirror sources, or offline import as before) → open DSH.

---

## What's new

Current version: **v1.5.4** (2026-11-14) — **Startup reliability · Writer-lock safety.**

1. Added **atomic writer locks**: DSH writes the full PID before exclusively creating the lock, eliminating empty locks from forced kills; historical empty locks are safely recovered via kernel write leases;
2. **Slow startups no longer killed at 30 s**: a live process that is not yet ready gets the full 120 s startup window, so plugin-heavy or low-end devices no longer restart repeatedly;
3. **Orphaned empty locks auto-recover**: a kernel lease confirms no live writer before clearing historical empty locks at startup, so they no longer block startup permanently;
4. **Emergency instance warning & repair guidance enhanced**: clearly labels main (.dsh) and emergency (.dsh-emergency) data directories, temporary purpose, and operation boundaries; adds a foldable, copyable repair prompt template (6 languages).

See **[CHANGES.md](CHANGES.md)** for the complete changes in each version.

---

## Core features

### 1. Remote connection (new in v1.5.2)

The Home page gains a new “Remote” tab for connecting to DSH running on a computer or other devices. Remote sessions share the same host, file-delivery and diagnostics capabilities as local DSH.

- **Scan / paste pairing**: scan the pairing code shown by the desktop remote plugin, or paste a pairing link containing a token; a confirmation card is shown before pairing, and the device profile is created only after confirmation.
- **Adapted plugins**: dsh-remote-web-gateway, dsh-full-remote, dsh-bridge, dsh-pocket, generic URL tickets, the dsh-Remote scheme, ChongYep, saya-ch / dsh-mobile, plus account-based direct-connection entries such as ZCode workspace and DSH Remote.
- **Proxy and direct connection**: proxy mode forwards through the phone's local loopback and works with plaintext LAN traffic and self-signed certificates; direct mode lets the renderer access HTTPS addresses directly with a shorter path. The two modes can be switched per endpoint.
- **Multi-device management**: devices are organized into groups and can be created, renamed, moved between groups and deleted; each endpoint stores the target address, channel type, session mode and retention policy.
- **Stable isolation**: each device is bound to its own loopback IP, and each endpoint gets a stable port that is persisted; deleting a device recycles the slot and retires the port.
- **Session retention**: supports a default tier, per-endpoint overrides and a “permanent” tier; the hot pool keeps pages within its capacity, so leaving and returning does not reload a session.
- **External links and attachments**: links to the same upstream open in place within a remote page, while off-site http(s) links and page-open requests go to the system browser; file selection reuses the local seven-source attachment panel.
- **Remote downloads**: page downloads are collected under `DSHBox/download-remote`, with a bottom downloader, result notifications, and open-file / open-folder actions; when a cross-origin `<a download>` hits an approved file CDN, the navigation is canceled and handed to the downloader.
- **dsh-mobile / saya-ch**: parses JSON descriptors and URL pairing links and verifies the instance fingerprint and session CA; native-pair exchanges the session Cookie and CSRF token, device credentials persist and native-renew renews them automatically; iroh transport addresses are explicitly rejected, with guidance to switch to LAN or Cloudflare mode.
- **Web Agents**: web Agents such as WorkBuddy and MiniMax can connect as remote pages; WorkBuddy downloads support Tencent Cloud object-storage CDN tenant prefixes.
- **Six-language UI**: the remote panel, pairing, QR scanning, settings, error messages and downloader are available in Chinese, English, Arabic, Spanish, French and Russian.

### 2. Fully embedded local DeepSeek Harness with dual rendering

#### (1) DeepSeek Harness fully embedded local runtime

- DSH `0.2.2-rc.2` ships inside the APK and the layer content stays pure upstream. On first launch it is provisioned into `runtime-current/dsh` through **version arbitration**; when the layer is replaced, the old layer is backed up once to `previous/dsh`, and user data is never touched.
- **Runtime hard-link compatibility shim**: Android app-data partitions (FBE/FUSE) reject hard links, while DSH uses `link()` as its primitive for publishing files without overwriting, which breaks session persistence, file tools and attachment publishing. At startup the app replaces `node:fs/promises.link` via `node --import`; when the platform refuses the hard link, it falls back to a content copy that preserves the no-overwrite semantics.
- **Atomic writer locks**: the same shim intercepts Promise `writeFile` for `wx` lock creation, writes the full PID to a temporary file first, then exclusively creates the `.lock` via `renameat2(RENAME_NOREPLACE)`, eliminating empty locks from forced kills; historical empty locks are recovered only after a kernel write lease (`F_SETLEASE`) confirms no live writer holds the file. Zero DSH source changes.
- **Zero DSH source changes**: compatibility logic lives in the app, the renderer adapter, bundled extensions and runtime wiring; the bundled DSH layer keeps upstream files and business logic unchanged.
- **Full-screen DSH workspace**: the DSH tab resolves launchToken automatically on the local address, uses a mobile user agent, handles keyboard and visual viewport, and supports pinch-to-zoom; a draggable floating ball still provides refresh plus quick switching to Home, Files, Terminal and Browser, and Home keeps the “Browser” and “More” entries.

#### (2) DeepSeek Harness access settings

- **DSH local access settings**: the Home DSH card opens a full-screen access settings page to view and copy the full tokenized address, configure how links open externally, copy connection information and check uptime. Before local DSH starts, available ports are probed instead of pinning 3080; the page, notifications and the LAN gateway all follow the actual address.

- **DSH LAN access settings:**

  a. LAN gateway: devices on the same network can open the local DSH page; supports an access key, addresses with or without the key, a QR code, the number of online devices, and “Disconnect all and reset the key”.

  b. Access control: persisted keys, constant-time comparison, gate-cookie signature verification and rate limiting after failures; after a key rotation, old links and old sessions become invalid at the same time.

  c. Network adaptation: detects and binds the Wi-Fi private address; when a system VPN occupies the network, it bypasses to a non-VPN network with a notice.

- The foreground-service notification carries Start / Restart / Stop quick actions;

- Page refreshes and reauthentication always use the current launchToken, so after DSH restarts or the health loop rotates the token, requests no longer go to the old address.

#### (3) WebView dual rendering

- **System WebView build / embedded Gecko build**: both release variants share a single DSH page plus one implementation of host navigation, file delivery, diagnostics and logging, with differences isolated in the Renderer Adapter; both APKs are `com.dshbox.app` with the same signature and can overwrite each other while preserving `filesDir`.
- **System WebView compatibility**: APIs required at startup are filled in before the DSH main script runs, entering the page through a single document-start injection; engines that support document-start keep reusing the system WebView, while engines that do not explicitly block navigation and prompt for an upgrade.
- **Embedded GeckoView 158**: fully uses the bundled rendering engine, with no dependency on the system WebView and no silent fallback; it handles DSH account popups, external links, 401s, file selection, page state, the side panel, keyboard viewport and other differences.

### 3. Embedded AI automation browser (Browser)

An independent embedded browser workspace exposes **24 tools** to DSH through the bundled `browser-use` plugin and the sandbox CLI. It is provisioned by default for new users and can be controlled independently through the “DSH browser automation” switch on the Home Cordis tab.

- **Web interaction**: search, navigation, multi-page management, content reading, element location, clicks, typing, scrolling and conditional waits; search engines can be Bing, Google or Baidu, with Bing as the default.
- **View selection:** choose between a mobile view and a desktop view.
- **Selection feedback and local previews**: select a web element or region and submit its screenshot and comments to the bound DSH session; HTML and other files in the sandbox can be served through a local static server started via the CLI, or an existing service can be registered, then previewed in Browser with change feedback.
- **Screenshots and debugging**: supports foreground and background page captures; if an ordinary background capture fails, the CDP fallback is tried serially only when advanced debugging is enabled, otherwise an explicit message is returned. Advanced debugging provides DOM, console, script evaluation, network capture and HAR export; request interception, modification and replay require separate authorization.
- **Files and records**: persistent history, bookmarks, multi-page and download management; AI can prepare files for upload and hand them to the web page after user approval. Task records can be viewed, edited and deleted, and a continuation summary can be sent to AI.
- **Control and interface**: taking over from AI requires confirmation, and AI can request authorization when AI control is off; supports theme and six-language following, mobile / desktop / wide-screen modes, a configurable idle-close time, and a quick return to DSH by tapping the AI indicator.

Browser handles **embedded web pages** and Pilot handles **native phone apps**, with DSH choosing which to call. Complex cross-page task recovery, long-running streamed responses and some media and site compatibility scenarios are still being improved; full automation is not guaranteed for every task.

### 4. Phone control (Pilot)

#### (1) A safe, controlled phone-assistant channel (the app provides approval interception)

A built-in **catalog of 39 phone-control capabilities** and a **mailbox channel** between the embedded sandbox and the Android app layer (file delivery, no network, no shared memory) let sandboxed DSH control and connect to the phone safely and under control. It is embedded with a two-layer structure of “UI adaptation + core platform”; the core is published independently under **Apache-2.0** in the [interlock-relay](https://github.com/WSK-build/interlock-relay) repository, and this repository calls it and provides the UI adaptation.

| Execution backend (three) | Description |
|---|---|
| Accessibility mode | AI directly controls the screen in the foreground: taps, swipes, long presses and more |
| Shizuku privileged mode | Opens system shell-level (adb) permissions; dangerous operations such as deletion are strictly restricted, and some permissions are left for users to enable at their own risk |
| Direct platform calls | A set of capability calls through normal system channels |

| Execution mode (two) | Description |
|---|---|
| Foreground | Visible, direct operation, paired with the accessibility / direct-platform backends |
| Background virtual display | The user keeps using the device in the foreground while AI operates in the background; automatically degrades to foreground operation under some apps' restrictions |

- **Approval and interaction constraints**: every path above is constrained; per capability you can choose Deny / Ask for approval / Full access, interacting through dialogs.
- **Floating-window questions**: when the user leaves the DSHBox page, AI can raise a floating window to ask questions; the user can select options, reject them all, or ask for the question again.
- **Native tools for DSH**: the bundled `@local/mobile-pilot` plugin provides a set of `phone_*` tools so the agent can call our core mailbox mechanism directly. This plugin can be toggled freely under “DSH phone connection” in Cordis.

#### (2) Developer mode (ADB + root, permissions fully handed to the user)

A separate “Developer mode” panel is reachable from Pilot. It provides full capabilities aimed at advanced users and is not part of the Pilot phone assistant's controlled approval system:

| Channel | Capabilities and limits |
|---|---|
| ADB | When the switch is on, a complete ADB client and its dependencies are mounted into the Debian sandbox and removed when off; supports the local service, wireless-debugging pairing, external-device debugging, ports and custom keys. |
| Root | Uses the device's existing `su` implementation and provides an `android-root` CLI in the guest; supports access codes, failure lockout, Unix/TCP transports and permission prechecks. |

- **Separate from the phone assistant**: ADB / Root do not go through Pilot's per-item Deny / Ask for approval / Full access approvals, permission tiers or action interception. After enabling a switch, users decide its use themselves and are responsible for their own debugging, system or Root operations and their consequences; the original phone-assistant channel keeps its controlled permissions and approval protection.
- **Separate from Shizuku**: developer-mode ADB does not depend on Shizuku and can serve as an alternative path for some Shizuku compatibility gaps or unresolved issues; the two keep independent switches, keys, server configuration and lifecycles.
- **External-device debugging**: this ADB can connect not only to the phone's local service but also to other devices over the network. Once the ADB channel is off, the related commands, server entry points and mounts are withdrawn from the sandbox.
- **Before you use it**: wireless debugging requires the device to support the capability and the networks to be reachable; Root requires an existing usable `su` and may be affected by Magisk, KernelSU, APatch, hiding policies and vendor ROM differences.

### 5. Plugin management and marketplace

Entered from the Home Cordis tab, plugin-related capabilities are managed in one place:

| Capability | Description |
|---|---|
| Bundled DSHBox plugin switches | “DSH mobile page adaptation” (`dsh-mobile-adapt`), “DSH phone connection” (`mobile-pilot`) and “DSH browser automation” (`browser-use`); provisioned by default for new users, upgrades preserve explicitly disabled settings, and provisioning and updates follow DSH readiness. |
| Official DSH plugins | Switches for official entries disabled upstream by default (such as the side-panel built-in browser) |
| Safe mode | Starts without loading any third-party plugin, used to recover startup when a third-party plugin crashes DSH |
| Plugin loading records | View plugin loading per startup segment for troubleshooting |
| Crash-recovery assistance | The built-in terminal agent **opencode**, with one-tap install / update / uninstall; type `opencode` in the terminal to launch the TUI and complete fixes through natural-language interaction |
| Emergency repair instance (new in v1.5.3) | When DSH crashes, quickly start a fresh emergency instance (isolated empty data, sharing the current runtime layer, only the mobile-adapt plugin assembled), then switch back after fixing the main instance; a crash-recovery dialog plus a permanent "Emergency repair & instance switching" entry in the Home card "More" menu, with an "Emergency" badge on the card and notification while active |
| Plugin marketplace | Lives in the app layer, offering category browsing, search, compatibility checks, install guards and third-party plugin install / update / delete entry points |
| Installed plugins | Shows the local manifest first, then asynchronously loads the remote catalog; distinguishes active, takes-effect-after-restart, pending-disable, host-denied, incompatible and broken states, avoiding a whole-page wait on weak networks |
| Bundled-plugin protection | Mobile adaptation, Pilot and browser-use are written into profile dependencies; after third-party plugins are installed, updated or deleted, they are not pushed out when pnpm recomputes `node_modules` |

The marketplace ships with two live data sources: **awesome-dsh-plugin** and **[awesome-dsh-mobile-plugins](https://github.com/WSK-build/awesome-dsh-mobile-plugins)**. The latter is a repository created by DSHBox specifically for the mobile DSH plugin ecosystem; users can submit PRs to help build the ecosystem together.

- **Bundled pnpm 11.1.2**: ships with a Corepack cache and preset probes of the official npm registry and mainland-China mirrors; the pnpm store, registry and Corepack environment use one shared configuration across DSH, the official “Add Plugin” entry, the marketplace and the terminal.
- **The official “Add Plugin” flow is connected**: entering a package name or link pulls plugin metadata and supports install and uninstall; fixed missing `pnpm`, rejected registry arguments and inconsistent store paths.
- **Installed states show when they take effect**: entries that need a DSH restart to apply after enabling or disabling show “takes effect after restart”, and non-operable items such as host-denied, incompatible, base-library and the marketplace itself no longer show dead switches.
- **Compatibility boundary**: the marketplace checks known host-version, carrier and dependency issues in advance, but cannot guarantee every third-party plugin works on every DSH version. Back up before upgrading to an Alpha; falling back from an Alpha to an RC may cause conversation-data compatibility issues.

### 6. DSH mobile and dual-renderer adaptation

The bundled DSH is upgraded to **0.2.2-rc.2**. The System WebView and Gecko rendering paths share the same DSH page and host logic; adaptation is concentrated in the app, the Renderer Adapter, bundled page-compatibility assets and extensions, with **no modifications to DSH source code or business logic**:

| Component | Mobile difference or break point | Handling |
|---|---|---|
| System WebView startup | Older engines lack standard APIs DSH depends on, or have no document-start capability | Required capabilities are filled in before page scripts run; providers that cannot safely enter the pre-startup phase are explicitly blocked with an upgrade prompt |
| Gecko page bridge | GeckoView does not have the same callbacks and extension environment as System WebView | Bundled extensions, a page-state bridge, navigation generations and native events provide the capabilities DSH needs |
| Account login | DSH first opens a blank popup and then asynchronously obtains the authorization URL; engines handle it differently | Both System and Gecko recognize popups under trusted DSH origins; browser authorization is handed off to the system default browser |
| Links inside DSH | Loading a link directly in the main frame would overwrite the current workspace; side-panel iframes must be preserved | “Default browser” hands top-level non-same-origin HTTP(S) to the system browser; “in-app side panel” targets keep their iframe and are not wrongly intercepted |
| 401 and re-login | Concurrent navigations, redirects and late callbacks can attribute state to the wrong document | Navigation nonce, response-header state and document state are correlated with the current generation; results from old generations are dropped |
| Attachment upload | File selection, multi-select and source determination need native wiring | Provides camera, video recording, gallery, phone files, audio, workspace and shared area; multi-select results are deduplicated and `accept` sentinels are cleaned up |
| Voice input | WebView recording needs an extra audio-settings permission | The required permission is declared and wired into DSH voice input |
| Side-panel file preview | Authority/host parsing of non-standard schemes differs across engines | `dsh-resource://` parsing is completed at the app layer; System and Gecko share the same semantics |
| “Open configuration file” | The old approach relied on page interception and failed when the plugin was off or timing shifted | The runtime provides a native `xdg-open` link and the app opens the configuration-file viewer; existing files are not overwritten |
| Keyboard and top bar | Gecko may push the whole layout viewport up when the keyboard opens | Only Gecko gets the visual-viewport difference compensated and the conversation top bar pinned; System WebView keeps its original behavior |
| Side-panel terminal | The guest `passwd` lacks the runtime uid and `os.userInfo()` can fail | The current uid is patched into the guest `passwd` at runtime |
| Side-panel browser | The corresponding official plugin is disabled upstream by default | A switch is provided in the plugin management panel, taking effect after DSH restarts |
| Photos / video recording | The DSH page needs system camera / recording entry points | The `onShowFileChooser` accept sentinel routes to the system capture UI |
| Page diagnostics | Load completion does not mean the page is ready, and main-thread capability is not Worker capability | Diagnostics record document, main-thread API, Worker, Gecko and page state separately, never faking “supported” |

The device Back key, autofocus, floating quick switching and the attachment source panel carry over the capabilities completed in v1.5.0. v1.5.1 further moved URL parsing, configuration-file opening and Gecko keyboard handling — which were tied to plugin switches — into the app or explicitly limited them to the Gecko branch, so System WebView is no longer disturbed by Gecko-specific logic.

### 7. File manager

- **Areas**

| Area | Function |
| -------- | ---- |
| Sandbox files | The entire Debian sandbox filesystem |
| Workspace `/root/projects` | An app-private directory reserved for the DSH workspace; its contents are always protected and never cleared by sandbox resets |
| Shared area `/root/share` | After authorization, creates or reuses the **DSHBox** folder in the phone's public storage; the system shared-file directory where the user can, in a controlled way, pick other public system directories and mount them under separate names into the sandbox shared area; **unmounting only removes the association and never deletes the source directory or files**. Contents of public shared directories are not automatically deleted when the app is uninstalled or reinstalled |

- **Directories and views**: workspace `/root/projects`, the sandbox root (with the node and DSH layers overlaid) and shared-area entries; breadcrumb navigation, list / grid views, sorting by name / time / size, folder creation and multi-select batch operations
- **New file** (added in v1.4.0): create an empty file directly in the current directory
- **Install into the sandbox** (added in v1.4.0): select a `.deb` to unpack and install it into the sandbox directly
- **Move to a chosen folder**: a full-screen destination picker (sandbox / workspace switching, folder creation, the source and its descendants grayed out to prevent cycles, cross-mount-point destination hints); three conflict strategies (overwrite / skip / auto-rename + apply to all remaining); same-volume `renameTo` first with copy fallback on failure (permission bits / timestamps preserved); “Rename” uses the same engine
- **Import**: multi-select batch file import and archive extraction import, per-item conflict decisions, cancellation and a completion summary; fixed ZIP Chinese-name encoding, encrypted ZIPs explicitly rejected
- **Export**: multi-select export to a directory (SAF), or package as ZIP
- **External receiving and sharing** (added in v1.5.0): accepts files other apps send via “Share to” or “Open with another app” and lets you pick a save location, supporting multiple files and plain text; the file menu can directly raise the system share sheet, separate from the export function
- **Global search**: across sandbox + workspace, searching filenames and contents at once, with matching snippets in results
- **Risk protection**: system directories / node / DSH layers / `.dsh` are labeled by risk level, with strong confirmation before write operations

- **Universal file viewer / editor** — a three-tier classification of magic bytes + content sniffing + extension ensures every file has an interface:

| Type | Capabilities |
|---|---|
| Text / code | Sora Editor editing (line numbers / undo-redo / search / soft wrap), highlighting for json / yaml / shell / python / js / java+kotlin; encoding auto-detection + manual switching, forced read-only for lossy decoding; large-file tiers (≤2MB editable · 2–10MB editable after confirmation · >10MB read-only tail window); atomic save + external-change detection + unsaved-change interception |
| Images | Pinch-to-zoom / double-tap zoom, strip loading for extra-long images, GIF / animated WebP, AVIF (Android 12+) |
| PDF | Native paginated rendering; encrypted PDFs accept a password on Android 15+, older versions are guided to open externally |
| Archives | zip / jar / apk / epub and tar-family read-only browsing (collapsible directories, encrypted-entry markers), in-archive text previews, single-entry / all export; Chinese ZIP names are not garbled; 7z / RAR info cards + external open |
| Hex | Offset / Hex / ASCII three columns, 64KB-block random reads, entropy estimation |
| Office | docx / xlsx plain-text extraction read-only; doc / xls / ppt info cards + external open |
| Markdown / HTML / SVG | md source editing + Markwon preview (tables included); html / svg offline WebView rendering (JS disabled, network disabled, destroyed on exit) |
| Unknown / binary | Hex viewing + file info card; external open / edit / share / export as fallback |

### 8. Terminal

- Multiple windows: a sandbox terminal (full PRoot Debian environment with bash / vim / htop / node / npm / apt / git / python3 / ssh working out of the box) / a restricted-shell fallback, with a floating control panel to create / switch / close
- **Direct DSH CLI access** (added in v1.4.0): run the official `dsh` commands (web / headless / tui / plugin) directly in the sandbox, the same DSH and the same profile as the web page
- **Developer-mode commands**: after enabling the corresponding switch, the full `adb` is available in the sandbox; when the Root channel is on and the device is authorized, `android-root` is available. Turning a switch off removes the corresponding command entry
- **Bundled utility fill-in** (added in v1.4.0): system tools AI commonly needs for tasks, such as `jq`, `sqlite3`, `patch`, `nano` and `strings` (binutils); the tool bundle **self-heals** — if tools are found missing after a layer swap or upgrade, they are reinstalled automatically, with no need to clear data and start over
- A two-row auxiliary key bar (ESC / TAB / HOME / END / sticky CTRL / paste / arrow keys / page keys / backspace / delete, DECCKM-aware), with pinch-to-zoom font size 8–40sp
- Based on Termux terminal-emulator / terminal-view (v0.118.0, unmodified) + the in-house `terminal-session` layer

### 9. Settings page

#### (1) DSH version selection, reset and runtime management (DSH versions are freely selectable, never locked)

| Feature | Description |
|---|---|
| DSH version selection (online) | Probes versions and latency across the npm official / Alibaba / Tencent Cloud / Huawei Cloud mirrors in parallel → pick a mirror and version (downgrades need a second confirmation) → npm in the sandbox pulls the full dependency tree → the layer is swapped and restarted automatically; runs in the background, live logs, cancellable (SIGKILL to the process tree) |
| Reset & backup management (new in v1.5.3) | Reset the main instance to a fresh state with one tap (automatic backup before reset, or direct delete, with strong confirmation; only instance data is reset, project files are kept); the backup list is sorted by time with readable date names, supporting per-item restore / delete (no "delete all"), with reversible restore that keeps the original backup |
| Update DSH (offline import) | Single-file layer packages `.tar.zst / .tar.gz / .tar / .tgz` (or a zip containing a layer package), staged extraction → shape validation → atomic layer swap, leaving no half-finished artifacts on failure |
| Install the runtime online | Probes mirrors separately for the **Node layer / minimal Debian layer** (reachability and latency), downloads, verifies GPG signatures, trims and assembles into layers; install as soon as a usable mirror is selected without waiting for all probes (v1.5.3); step-by-step progress, cancellable, with failures categorized by cause (mirror missing packages / verification failure / insufficient space, etc.); any layer can be reset at any time |
| Import a runtime package offline | Replaces base / node / android-side as a whole package, with per-layer SHA-256 verification and one `previous/` copy for rollback (see below); since v1.4.0 it is not distributed inside the APK but is still provided separately on the Release page, equivalent to the online path. |

#### (2) Diagnostics and logs

DSH / sandbox / guest command logs can be scrolled, copied and exported; rendering diagnostics distinguish the System provider, Gecko version, page document state, main-thread APIs, the Worker environment and known visual capabilities, and never treat load completion or a version number alone as proof that the page is usable

#### (3) Storage usage and cleanup

- Usage is measured the same way the system does (allocated blocks, hard-link deduplication, app caches included); it refreshes automatically when Settings opens and can be refreshed manually
- Cleanup items are checked independently: app cache / guest temporary files (only entries older than 24h are cleaned while running) / runtime logs (truncated) / apt download caches; rollback backups are optional with an explicit warning
- Cleanup is mutually exclusive with background installation / import and never touches `user-data/.dsh` or the runtime layers themselves

### 10. Localization

- Fully localized interface: the six official UN languages — English / 简体中文 / العربية / Español / Français / Русский; follows the system by default and falls back to English for unsupported languages
- Settings → Appearance adds a “Language” selector: six languages + follow system, effective immediately on selection with no restart; notifications, dialogs, terminal menus and more all switch with the language
- Quantity strings follow each language's plural rules (6 forms in Arabic / 4 in Russian, etc.); technical numbers such as file sizes, timestamps and hex offsets stay in Western digits
- Layout direction: globally always LTR (Arabic text renders normally via Unicode bidi); page navigation and layout are not mirrored as a whole
- A built-in i18n consistency gate (keys / placeholders / plural forms / hardcoded-string scanning) validates the six-language resources in CI

### 11. Layered PRoot runtime (no root required)

Four layers are provisioned independently; the PRoot userspace sandbox is isolated from the Android host, and the runtime and user data (`user-data/` → guest `/root/projects`) never write into each other:

| Layer | Contents | Guest mount point |
|---|---|---|
| base | Minimal Debian 13 (trixie) rootfs | `/` (rootfs) |
| node | Node.js 24 (npm / npx / corepack) | `/usr/local` |
| dsh | DeepSeek Harness `0.2.2-rc.2` (npm package, pure upstream) | `/opt/dshapp/runtime` |
| android-side | PRoot / loader / shmem (host side) | — |

- The sandbox keepalive and DSH are two independent PRoot processes; on shutdown the whole process tree is enumerated via `/proc` and children get SIGKILL first, leaving no orphans and no occupied ports
- Each layer carries a SHA-256 sentinel; integrity is verified layer by layer at startup, so damage can be identified and layers reinstalled
- Since v1.4.0, **the `base` and `node` layers are no longer bundled in the APK** (it contains only `dsh` and `android-side`): they are installed through “Install the runtime online” (minimal Debian layer / Node.js layer, multiple selectable mirrors, resettable) or “offline import of the full package”. Online-assembled layers trim unnecessary language packs, documentation and other unrelated components (the “minimal Debian”), for a smaller footprint

## Interface (five bottom tabs)

| Tab | Features |
|---|---|
| Home | Four sub-tabs — Control / Pilot / Cordis / Remote: sandbox and DSH status control, system status, the phone-control entry, plugin management and remote connection; the Settings entry is at the upper-right corner |
| Files | Workspace / sandbox / shared-area browsing, move / rename / delete, multi-select batch operations, import / export / share, external file receiving, viewer / editor, search / sort, new files / folders, and `.deb` installation into the sandbox |
| DSH | Loads `http://127.0.0.1:3080` with System WebView or embedded GeckoView, with automatic authentication, keyboard and page compatibility, floating-ball refresh and quick module switching; also hosts remote connection pages |
| Terminal | Multi-window terminal, auxiliary key bar, control panel, and direct `dsh` CLI execution |
| Browser | Embedded browser and AI web assistant, element / region feedback, local web previews, history / bookmarks / downloads and debugging settings |

Settings has moved to the upper-right corner of Home and keeps Appearance (theme / language), storage and cleanup, app updates, DSH and runtime updates / imports, diagnostic logs, feedback and About.

## Known limitations

- **System WebView compatibility depends on provider capabilities**: parts of the WebView 101 / 124 environments have been tested and can complete the main DSH page flows; older engines lacking document-start or the required standard APIs are blocked from entering with an upgrade prompt. A system WebView version number alone does not guarantee every page works.
- **Worker / PDF support is not the same as main-page compatibility**: System WebView Workers use an isolated environment; for now diagnostics are provided but no Worker compatibility patches are injected. PDF, Excel or flows depending on newer Worker APIs may need a WebView update, or the embedded Gecko build.
- **The two APKs cannot be installed at the same time**: the System WebView and Gecko builds share a package name and signature and can only overwrite each other; installing the other build does not wipe Debian, the sandbox, user-data or created workspaces.
- **Developer mode is an advanced capability**: ADB / Root bypass the Pilot approval system. Users should understand the risks of wireless debugging, external-device access and Root commands and bear the consequences of their own operations. `su`, wireless debugging, hiding and authorization behavior can differ across ROMs.
- **Plugin compatibility is decided jointly by the host and the plugin**: the marketplace checks known host capabilities, carriers and dependency differences, but cannot guarantee every third-party plugin installs or runs on all DSH versions.
- **Remote connection depends on the peer plugin and the network**: the corresponding remote plugin must be installed and enabled on the computer; proxy mode requires the phone to reach the peer address, and direct mode supports HTTPS only. Login, Cookie and download behavior may differ across plugins.
- **iroh transport is not supported yet**: iroh addresses of saya-ch / dsh-mobile are explicitly rejected; please use LAN or ordinary HTTPS modes such as Cloudflare instead.
- **Remote downloads have origin restrictions**: only same-origin or approved file CDNs are handed to the downloader; other cross-origin downloads may still be handled by the page or the system.
- **LAN access requires the same subnet and the key**: the phone and the computer must be on the same LAN, DSH must be ready, and the current access key must be held; after a key reset, old links and old sessions become invalid.
- **Gecko static content scripts have platform differences on a few new systems**: the login, external-link, configuration-file and navigation critical paths that currently need bridging already use native events, the extension state bridge or runtime links; any future feature that injects into the page main world will still need revalidation.
- **Back up before upgrading to an Alpha**: after upgrading from a stable RC to a DSH Alpha, falling back to the RC may cause conversation-data compatibility issues.


## Build from source

| Environment | Version |
|---|---|
| JDK | 21 (official CI uses Temurin 21) |
| Android SDK | compileSdk 37.2 · targetSdk 36 · minSdk 29 |
| Gradle | Wrapper 9.5.0 (AGP 9.3.1 · Kotlin/Compose compiler plugin 2.4.20) |
| Kotlin | Compiler 2.4.20; language/API level pinned to 2.2; JVM target 17 |

> The large runtime layers are **not in this repository**; because of their size, they are published as separate GitHub Release assets instead (see the next section). Obtain `../runtime/` before building.

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleSystemWebViewRelease :app:assembleGeckoRelease
pwsh tools/assemble_release_apks.ps1
```

The release archiving step outputs:

- `apk/dshbox-v<version>-system-release.apk`
- `apk/dshbox-v<version>-gecko-release.apk`

The archiving script then runs the release contract gate, checking both APKs' DSH compatibility assets, Gecko extension permissions, script hashes, package name and signing.

| Module | Responsibility |
|---|---|
| `app` | All UI (the 5 tabs, DSH access settings, viewers, settings / diagnostics / updates pages), both rendering variants, the foreground service, online update orchestration and runtime acquisition |
| `remote-connection` | Remote connection: device/endpoint profiles, loopback proxy and direct sessions, pairing parsing, QR scanning, the remote panel and session display slots, the downloader |
| `dsh-renderer` | `api`: the shared rendering contract, navigation authentication and file delivery; `system`: System WebView compatibility and capability probes; `gecko`: GeckoView 158 rendering, extensions, popups and file selection |
| `sandbox-manager` | Layered runtime provisioning, PRoot process management, DSH layer arbitration and updates, import / verification / cleanup |
| `common` | Constants, npm mirrors, version comparison, log redaction |
| `bridge` | WebView JS Bridge security framework (reserved stub) |
| `terminal-session` | The terminal session layer (multi-window, PRoot terminal command construction) |
| `terminal-view` / `terminal-emulator` | Termux terminal libraries (v0.118.0, unmodified) |
| `plugin-manager` | Plugin management: provisioning control, the marketplace (catalog retrieval / compatibility checks / install guards), official plugin switches, absolute safe mode, plugin loading records and crash-recovery assistance (opencode) |
| `pilot` (`interlock-relay-core` + `dshbox-adapter` + `developer-access`) | The phone-assistant core and UI adaptation; developer-mode ADB, Root, wireless pairing, devices, keys and channel panels |
| `browser` (`browser-api` / `browser-runtime` / `browser-bridge` / `browser-devtools`) | Browser contracts, the WebView session runtime, sandbox tool bridging and controlled advanced debugging; UI integration lives in app |

Runtime-layer build scripts are in `runtime-bundle/` (each layer's `tar.zst` is built by `build_base.sh` / `build_node.sh` / `build_android_side.sh` on Linux/WSL2); the build manual is `docs/BUILD_RUNBOOK.md` and the preflight script is `tools/pipeline_dryrun.sh`. Note: when building from source, developers must first extract the `android-side` layer from the runtime archive published with a Release and place it at `runtime/android-assets/runtime/android-side.tar.zst` required by the build; otherwise the compiled APK lacks that runtime layer and cannot install the runtime properly.

Note: the Release page also ships a packaged full runtime bundle with each version (three layers: base / node / android-side, separate from the APK) that can be downloaded and used directly. Since v1.4.0 the APK embeds only the `dsh` and `android-side` layers; `base` and `node` no longer go into the package and are either assembled on the fly by “Install the runtime online” or imported offline from that bundle.

## Large runtime files (not in this repository)

| Path in the distribution | Contents |
|---|---|
| `runtime/android-assets/runtime/android-side.tar.zst` | Host-side PRoot / loader / shmem with a `.sha256` sidecar (**bundled in the APK**) |
| `runtime/android-assets/dsh/0.2.2-rc.2.tar.zst` | DSH layer + `.sha256` (pure upstream, unpatched; **bundled in the APK**) |
| `runtime/offline-baseline/{base,node}.tar.zst` | Debian and Node layers, each with a `.sha256` sidecar and `runtime-profile.json`; **not in the APK**, delivered as part of the offline bundle |
| `runtime/dshapp-runtime-debian-arm64-0.1.0.zip` | The externally delivered full runtime package (for offline import) |

- Inclusion: `app/build.gradle.kts` references the distribution directory via `assets.directories.add("../../runtime/android-assets")`, embedding its contents into `assets/runtime/*` and `assets/dsh/*` at build time
  - The rule is right in that line: **only files placed in the directories pointed to by `assets.directories.add` get packaged**; once `base` / `node` moved to `runtime/offline-baseline/`, they are no longer bundled. Inspection of the APK matches — it contains only `assets/dsh/*` and `assets/runtime/android-side.tar.zst`
- Build prerequisite: obtain `runtime/` before `assembleSystemWebViewRelease` / `assembleGeckoRelease`; if the directory is missing, the full runtime cannot be embedded. Since v1.4.0 this prerequisite applies only to the `dsh` and `android-side` layers
- Signing keys (`keystore.properties`, `local.properties`) are not in the repository: use `tools/create_keystore.sh` to create your own development signing; without configuration, release builds fall back to debug signing

## Offline runtime package import

- Distribution: a single `dshapp-runtime-debian-arm64-0.1.0.zip` file (published on the Release page), selected through the “Import a runtime package offline” option in Settings; coexists with “Install the runtime online”
- Layout and formats: an outer zip / tar; layer archives in `.tar.zst / .tar.gz / .tar / .tgz / .bz2 / .xz` (identified by magic bytes), with support for one directory prefix and outer tar containers holding layer archives
- Verification: per-layer SHA-256 (sidecars cross-checked against `runtime-profile.json`) + Zip-Slip protection; damaged / truncated / encrypted packages return readable errors
- Replacement: the old layers move into `previous/` (one copy for rollback); the DSH layer and `user-data/.dsh` are never touched
- Offline DSH updates use the single-file layer package of “Update DSH (offline import)” (e.g. `0.2.2-rc.2.tar.zst`), alongside online updates.

## User communication and feedback

- **QQ group**: 2163082322
- **Bilibili account**: search for “文大师啵啵”
- **Issue reports**: [GitHub Issues](https://github.com/WSK-build/DSHBox/issues)

## License

This project is licensed under **GPL v3**; see [LICENSE](LICENSE). Third-party components retain their own licenses; see `THIRD_PARTY_NOTICES.md` for details:

- PRoot (GPL-2+) · talloc (LGPL-3+) · Debian rootfs (individual packages' Debian copyright files)
- DeepSeek Harness / Cordis (MIT) · Node.js (MIT)
- Termux terminal-emulator / terminal-view (Apache-2.0, v0.118.0, unmodified)
- GeckoView 158 (MPL-2.0 and the licenses of its bundled components; only the embedded Gecko build contains it, with the upstream AAR unmodified)
- ZXing Core 3.5.3 (Apache-2.0, access-code QR support in developer mode)
- sora-editor (LGPL-2.1-or-later, unmodified AAR) · Markwon (Apache-2.0) · commons-compress (Apache-2.0) · zstd-jni (BSD-2-Clause for the JNI wrapper; BSD-3-Clause for the Zstandard core)
- interlock-relay-core (Apache-2.0, independently published phone-assistant platform, source distributed with this repository) · Shizuku (MIT, the phone assistant's shell-identity execution backend)

The three in-house plugins, `dsh-mobile-adapt`, `mobile-pilot` and `browser-use`, each include a complete LICENSE and NOTICE and declare **GPL-3.0-only**; third-party dependencies retain their original licenses. Bundled license documents are available offline under Settings → About → Open-source licenses; see [`licenses/README.md`](licenses/README.md) for origins and source rebuild instructions.

**Brand assets are not covered by the license grants above.** The app name, app icon and DSHBox-owned in-app brand graphics (listed in [`TRADEMARK.md`](TRADEMARK.md)) remain the exclusive property of the project's rights holder, with all rights reserved; they may not be used for modified or derivative versions without permission, or as identifiers likely to cause confusion.
