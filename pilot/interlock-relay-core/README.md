# Interlock Relay

**English** | [中文](README.zh-CN.md)

> **Interlock Relay** — a controlled relay between sandboxed AI agents and host app capabilities.
>
> It exposes a host app's device capabilities to an isolated sandbox through a single file-based channel.
> Every crossing passes through an interlock that the host can gate, observe, and audit.
>
> The core provides the protocol, transport, interlock, and extension points. The host retains control of
> UI, dialogs, policy, and execution.

`interlock-relay-core` is an Android library module. It boots a mailbox/control channel between a
sandboxed agent and the host app, routes every call through an approval interlock, dispatches to
three built-in execution backends (accessibility, direct platform calls, Shizuku), and persists
artifacts, quotas, and audit trails. Everything user-facing — screens, dialogs, wording, language,
branding — stays with the host, which injects it through seven small interfaces (the SPI).

## Module layout

```
interlock-relay-core/
├── src/main/java/interlock/relay/core/
│   ├── protocol/    wire types, capability registry, arg specs, effect semantics
│   ├── transport/   mailbox server, control channel, envelope codec, guest assets
│   ├── interlock/   gate, approval queue/broker, prompts, tiers, watchdog
│   ├── exec/        a11y / direct / shizuku backends and their services
│   ├── surface/     backend dispatcher and execution-mode policy
│   ├── storage/     paths, quota ledger, reaper, artifact layout
│   ├── log/         run log, audit log
│   ├── runtime/     RelayRuntime (entry point), container, coordinator, consent activity
│   └── spi/         the seven extension points listed below
├── src/main/assets/relay/relay-client.cjs   sandbox-side CLI (self-locating)
├── sample/          minimal host wiring
├── PROTOCOL.md      wire format, extracted from the codec
├── LICENSE / NOTICE
```

## Quick start

```kotlin
// When the sandbox starts:
RelayRuntime.start(applicationContext, RelayConfig())

// When it stops:
RelayRuntime.stop()
```

With no arguments, `RelayConfig()` selects the built-in defaults: system-language strings, no
presentation surfaces (confirmation-style calls then fail with `E_GATE_NO_FOREGROUND`), the built-in
39-entry capability table, default path policy, `SharedPreferences` storage, and an identity
redactor. Hosts override exactly the pieces they own:

| SPI | Purpose | Default |
|---|---|---|
| `RelayText` | localized strings + current language tags | app context, system language |
| `RelaySurfaces` | approval overlay / notification surface, ask surface, foreground state, notification channels | `NoSurfaces` (nothing presentable) |
| `RelayCapabilities` | capability table + usability gate | built-in 39-entry table, all usable |
| `RelayPathPolicy` | sandbox mount point, host dir names, CLI name, artifact prefix | `/opt/interlock-relay`, `relay`, `relay-` |
| `RelayExecutor` | add/remove/replace execution backends | built-in a11y + direct + shizuku |
| `RelayPrefs` | key-value preferences | `SharedPreferences` (`relay`, `relay_shell_verbs`) |
| `RelayRedactor` | run-log field redaction | identity |

See `sample/` for a minimal wiring and `PROTOCOL.md` for the wire format the sandbox side speaks.

## Sandbox-side CLI

The host materializes `assets/relay/relay-client.cjs` into `<mount>/bin/<cli-name>` on every channel
start. The script derives its entry directory from its own location and its command name from its own
file name, so the same bytes are correct under `/opt/interlock-relay` as well as any other mount
point. It offers `call`, `status`, `cancel`, `doctor`, `capabilities`, `home`, and `ask`.

## Compatibility

Hosts that already deployed their own mount point and command names can keep the sandbox side
byte-identical by passing those values through `RelayPathPolicy` (values below are an example;
each host passes its own):

```kotlin
RelayConfig(
    pathPolicy = object : RelayPathPolicy {
        override val guestEntry = "/opt/myassistant"          // sandbox mount point
        override val hostDirName = "myassistant"              // filesDir subdir (plus -state/-stage/-audit)
        override val cliName = "myassistant"                  // script name under bin/
        override val artifactPrefix = "myassistant-"          // generated artifact names
        override val assetClient = "relay/relay-client.cjs"   // packaged asset path (unchanged)
        override val mediaAlbumDir = "MyAssistant"            // media-library album dir
    },
)
```

The wire protocol (envelope fields, error codes, exit codes, capability ids, control ops, ask
semantics) does not change across releases; see `PROTOCOL.md`.

## Building

```
./gradlew test          # JVM unit tests
./gradlew assembleDebug # produces build/outputs/aar/interlock-relay-core-debug.aar
```

Requires JDK 17+, Android SDK with `compileSdk = 36`, `minSdk = 29`.

## License

Apache License 2.0 — see `LICENSE` and `NOTICE`.
