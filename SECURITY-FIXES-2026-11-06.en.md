# Security Fixes — 2026-11-06

> 中文版：[SECURITY-FIXES-2026-11-06.md](SECURITY-FIXES-2026-11-06.md)

This document explains the security fixes in the `fix/bump-vulnerable-deps` branch (based on v1.5.1, commit e9ec1e8): what was changed, why, how it was verified, what was not verified, and the follow-up actions for the maintainer when releasing. This branch changes version pins and the accompanying notices/strings only — no functional code changes.

## ① What changed

| # | Component | Old version | New version | Where |
|---|---|---|---|---|
| 1 | Node.js (runtime layer) | 24.19.0 (legacy Dockerfile line: 22.17.0) | **24.22.2** | The `NODE_VERSION` constant in `NodeSources.kt`, `build_node.sh`, `bundle.yaml`, `gen_profile.sh`, `Dockerfile`, and both `tools/` scripts — all unified to 24.22.2 |
| 2 | Bouncy Castle (`bcpg-jdk18on`, transitively `bcprov`/`bcutil`) | 1.78.1 | **1.86** | One version entry in `gradle/libs.versions.toml` |
| 3 | commons-lang3 (transitive via commons-compress) | 3.16.0 (transitive only) | **3.18.0** (explicit pin) | New entry in the version catalog; one explicit dependency line added in each of the two modules that use commons-compress (`app` and `sandbox-manager`), overriding the transitive version |
| 4 | pnpm (legacy build line only, not shipped in the APK) | 9.15.0 (another script floated `pnpm@latest`) | **10.34.9** | Pin updated in `Dockerfile`; the floating `pnpm@latest` in `install_dsh.sh` pinned to the same version for reproducibility |

23 files changed in total: the pin points above, plus consistency updates — the three corresponding rows in `THIRD_PARTY_NOTICES.md`, the runtime-import confirmation string in all 6 locales, the build docs, and the test fixtures that embed the Node version string. Nothing else was changed.

## ② Why

| # | Component | Vulnerability and impact (one line each) |
|---|---|---|
| 1 | Node.js | **CVE-2026-111497**: out-of-bounds write in the Brotli decoder, High, potentially reachable to remote code execution; **listed in CISA's Known Exploited Vulnerabilities (KEV) catalog** (2026-10-27), with a public arm64 proof-of-concept. Node is the execution engine for the agent and plugin code in this app, making this the highest-priority item. The same security release also fixes TLS hostname-verification bypasses (CVE-2026-106069 / CVE-2026-105483). 24.22.1 is the official security-fix release; 24.22.2 is the current latest 24.x, above the fix line. |
| 2 | Bouncy Castle | 6 OSV advisories: **CVE-2025-14813 (Critical)**, **CVE-2026-8763 (Critical, certificate constraint bypass)**, **CVE-2026-3505 (High, OpenPGP parsing resource-exhaustion DoS — directly on this project's `Release.gpg` verification path)**, CVE-2026-5598 (High), CVE-2026-13506 (High), CVE-2026-0636 (Moderate). |
| 3 | commons-lang3 | **CVE-2025-48924** (Moderate): uncontrolled recursion when processing long inputs, leading to denial of service. It enters the APK transitively via commons-compress; 3.18.0 is the fixed version. |
| 4 | pnpm | 25 OSV advisories against 9.15.0 (12 High / 13 Moderate), including command injection (CVE-2025-69262) and lockfile-integrity bypass (CVE-2025-69263). This affects only the legacy build line on the build machine, not the APK, but is fixed here together with eliminating the floating version. |

## ③ How verified

| # | Check | Result |
|---|---|---|
| 1 | OSV re-scan of the new versions | `bcpg-jdk18on` 1.86, `bcprov-jdk18on` 1.86, `bcutil-jdk18on` 1.86, `commons-lang3` 3.18.0, and `pnpm` 10.34.9 all return **zero advisories** |
| 2 | Node distribution availability | All 6 dist sources configured in `NodeSources` (official + 5 mirrors) already serve `v24.22.2/node-v24.22.2-linux-arm64.tar.xz` (verified HTTP 200/302); the app's existing SHASUMS256 upstream verification flow is unchanged and applies as-is |
| 3 | Repo-wide grep for old versions | All functional pins are cleared. Only two intentional exceptions remain: `docs/TERMINAL_CLI_DETECTION_RECORD.md` is a dated historical test record (rewriting it would falsify the record); and two comments/notices mention the overridden transitive version 3.16.0 as explanatory text, not as pins |

## ④ What was NOT verified

| # | Item | Note |
|---|---|---|
| 1 | **No build verification** | The audit environment has no JDK / Android SDK, so Gradle could not be run and the actually resolved dependency graph (including the effective versions of transitive dependencies) was not machine-verified. The bumps in this PR are routine version-catalog changes, and the commons-lang3 pin relies on Gradle's standard highest-version-wins plus an explicit direct dependency — but **please treat the maintainer-side CI build as the deciding check** |
| 2 | Debian rootfs cannot be audited version-by-version | The rootfs apt packages are not version-pinned and ship no SBOM; the shipped versions drift with the build date, so packages cannot be matched against vulnerability data from source |
| 3 | The bundled DSH npm tree cannot be fully scanned | The bundled DSH layer is not in this repo and has no lockfile; zero hits on the main package does not mean the whole transitive tree is clean |
| 4 | No upstream checksum for zstd-jni | The three in-repo copies were verified mutually consistent, but the repo records no upstream artifact checksum, so they cannot be compared offline against the official artifacts |
| 5 | Build-time Node layer checksum verification is best-effort | `build_node.sh` can skip verification when no cached checksum exists locally, and does not fetch the official SHASUMS256.txt on the spot (the runtime online-import verification path is complete; this note concerns only the build-time script) |

## ⑤ Follow-ups for the maintainer

| # | Action | Note |
|---|---|---|
| 1 | Rebuild the runtime layer package with the new Node version when releasing | The source pins have been updated first; for an official release, please rebuild the node layer package with 24.22.2 (the sha256/size fields in `bundle.yaml` are filled in by the packing script) |
| 2 | Legacy build-line version drift | The legacy line containing `runtime-bundle/Dockerfile` and `install_dsh.sh` still pins DSH 0.1.0-rc.6, which has drifted from the current 0.2.1-rc.5; this PR has bumped that line's Node/pnpm to safe versions, but whether to keep or retire the line is the maintainer's call |
| 3 | Suggestion (non-blocking) | If practical, producing an SBOM for the rootfs and keeping a lockfile for the DSH layer would let future security audits go from sampling to full scans |

— Nikita Law, November 6, 2026
