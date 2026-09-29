# Interlock Relay wire protocol

This document describes the on-disk wire format spoken between the sandbox-side CLI
(`relay-client.cjs`) and the host-side core. It is extracted from the implementation:
`transport/EnvelopeCodec.kt`, `transport/ControlChannelServer.kt`, `transport/MailboxServer.kt`
and `protocol/RelayWire.kt` are the single sources of truth.

Two protocol generations coexist:

- **v1 mailbox** — capability requests through `run/inbox` / `run/outbox`.
- **v2 control channel** — submissions, follow-ups and asks through
  `run/control-inbox` / `run/control-outbox`.

Both directories live under the sandbox mount point (`/opt/interlock-relay` by default,
host-configurable). All writes are atomic: the writer creates `<id>.json.part` and renames it to
`<id>.json`; only renamed-in files are consumed.

## Identifiers and limits

| Rule | Value |
|---|---|
| Request id | 1..64 chars of `A-Za-z0-9_-` (`EnvelopeCodec.ID_PATTERN`) |
| Request file name | `<id>.json` — the file name is the only trusted source of the id; a body id that disagrees is rejected |
| v1 request body | ≤ 200,000 chars parsed / 256 KiB raw (`MAX_REQUEST_BYTES`) |
| v2 control body | ≤ 64,000 chars (`MAX_CONTROL_CHARS`) |
| JSON nesting depth | ≤ 24 (`MAX_DEPTH`) |
| TTL clamp | 1,000..120,000 ms (`MIN_TTL_MS` / `MAX_TTL_MS`), default 90,000 |

## v1 capability request

Written by the CLI into `run/inbox/<id>.json`:

```json
{"v":1, "id":"…", "ts":1700000000000, "capability":"screen.capture", "args":{}, "ttlMs":90000}
```

The host consumes the request and writes the response into `run/outbox/<id>.json`.

## v1 response envelope

Encoded by `EnvelopeCodec.encode`; optional fields are omitted when absent:

| Field | Meaning |
|---|---|
| `v` | protocol version, `1` (`RELAY_PROTOCOL_VERSION`) |
| `id` | echoes the request id |
| `ok` | final verdict |
| `elapsedMs` | host-side total handling time |
| `surface` | `foreground` \| `trusted-display` \| `observe-only` — what actually ran |
| `degradedFrom` / `degraded` | the surface was switched; `degraded` is only written when `surface` is present |
| `reason` | failure cause (failures only) |
| `note` | supplementary note (successes only) |
| `degradeReason` | why the surface switched |
| `data` | capability payload |
| `artifacts` | delivered file paths |
| `error` | `{code, retryable, exitCode}` — see below |
| `retryPolicy` | `safe_retry` \| `query_first` \| `user_decide` \| `never` (snake_case name of `RetryPolicy`) — present only when the capability admits a definitive verdict |
| `effectState` | `not_started` \| `dispatched` \| `verified` \| `unknown` (snake_case name of `EffectState`) |
| `consentKind` | `relay_policy` (host approval) or `android_projection` (system capture dialog) |

### Error codes

Single source: `RelayError`. Every code carries `retryable` and the CLI exit code
(`error.exitCode`); the CLI reads that field instead of re-deriving it. The table is closed:
`E_GATE_HOST_DENIED`, `E_GATE_SYSTEM_MISSING`,
`E_GATE_APPROVAL_TIMEOUT`, `E_AWAITING_CONSENT`, `E_GATE_WAITING_TURN`, `E_GATE_CANCELLED`,
`E_TASK_SUSPENDED_BY_HOST`, `E_NO_EDITABLE_TARGET`, `E_NODE_NOT_FOUND`, `E_NODE_AMBIGUOUS`,
`E_NODE_NOT_ACTIONABLE`, `E_ACTION_REJECTED`, `E_WAIT_TIMEOUT`, `E_GATE_NO_FOREGROUND`,
`E_CLIPBOARD_NO_FOCUS`, `E_CAPABILITY_UNAVAILABLE_ON_DEVICE`, `E_NOT_IMPLEMENTED`,
`E_NODE_NO_IME_ACTION`, `E_NODE_SYSTEM_CONSENT_TARGET`, `E_LAUNCH_NOT_LANDED`,
`E_SURFACE_API_LEVEL`, `E_SURFACE_NO_SHELL`, `E_SURFACE_TRUSTED_DENIED`, `E_SURFACE_UNAVAILABLE`,
`E_SURFACE_MODE_REQUIRED`, `E_SURFACE_SYSTEM_CONSENT_REQUIRED`, `E_SURFACE_SECURE_WINDOW`,
`E_BACKEND_SHIZUKU_DEAD`, `E_BACKEND_UNAVAILABLE`, `E_STORAGE_FULL`, `E_RATE_LIMITED`,
`E_QUEUE_FULL`, `E_REQUEST_TOO_LARGE`, `E_TRANSPORT_MALFORMED`, `E_TRANSPORT_TIMEOUT`,
`E_INTERNAL`.

### Exit codes (CLI process)

Single source: `RelayExitCode`. The reply's `error.exitCode` wins over any local classification.

| Code | Meaning |
|---|---|
| 0 | success |
| 1 | usage error: unknown capability, malformed args — fix the call |
| 2 | denied by the user or by a rule |
| 3 | timeout |
| 4 | the user must act first (grant, open a page); `status` also uses 4 for "no such request" |
| 5 | retry later (rate limit, queue full, backend restarting); `cancel` also uses 5 for "recorded, not yet confirmed" |
| 6 | host-side internal fault |

## v2 control channel

Request into `run/control-inbox/<id>.json`; the reply lands at
`run/control-outbox/<id>.json` and is consumed (deleted) by the CLI.

Request frame: `{"v":2, "op":…, "id":"…", …op fields}` with `op` one of:

| op | Extra fields |
|---|---|
| `submit` | `capability` (must be in the closed capability set), `args`, `hostTtlMs`, `payloadDigest` (64 lowercase hex chars of the normalized args) |
| `status` | `targetId` |
| `cancel` | `targetId` |
| `health` | — |
| `ask` | `question` (1..200 chars), `options` (2..3 items, 1..60 chars), `timeoutMs` (5,000..120,000, default 60,000) |

Reply frame: `{"v":2, "id":…, "op":…, "ok":…, …payload}` (`EnvelopeCodec.encodeControl`).

### submit

- Acceptance receipt: `state:"QUEUED"`, no `result` — **not** a final answer; the host later
  overwrites the same reply file with the terminal frame.
- Terminal frame: either a `result` object carrying the full v1 reply, or `ok:false` with `cause`
  (the submission was refused before execution; the cause embeds the host error code as
  `E_…/detail`).
- Terminal `state` values: `SUCCEEDED`, `FAILED`, `CANCELLED`, `INTERRUPTED_BEFORE_EXECUTION`,
  `UNKNOWN`, `EXPIRED`. `CANCEL_REQUESTED` is in-flight, not terminal.
- Once a submission is renamed into the control inbox it is never re-sent over v1: a channel that
  disappears mid-flight is reported as `E_TRANSPORT_UNKNOWN` (exit 6) with the request id on stderr.

### status

Payload: `targetId` (echoed back), `found`, and when found: `state` (phase name), `result`
(terminal reply or null), `mayHaveDispatched`, `cancelRequested`. `found:false` maps to exit 4.
Nothing is waited for here: the payload only relays the state store's snapshot as of this read.

### cancel

Payload: `targetId`, `outcome`, and `state` (the target's phase name, or null) on the settled
outcomes. `outcome` is one of five values:

| `outcome` | Meaning | Reference CLI exit |
|---|---|---|
| `CANCELLED` | The request file was still unclaimed; provably never executed, now dropped. | 0 |
| `COMPLETED` | Already terminal before the cancel landed; read the result with `status`. | 0 |
| `CANCEL_REQUESTED` | In flight: the wish is recorded, the outcome is not yet provable. Carries `provablyUndispatched` — the only key that says whether a retry is safe. | 5 |
| `NOT_FOUND` | No such request; nothing is claimed about what it did. | 4 |
| `UNKNOWN` | The host could not record or read back the cancellation, so it cannot say either way. Follow up with `status`; do not re-dispatch a non-idempotent action on this reading. | 6 |

The reference CLI's `UNKNOWN` exit comes from its catch-all "unrecognised outcome" branch, which is
also exit 6 — treat that branch and `UNKNOWN` as the same reading.

### health

Payload: `channelLive` (the work loop is actually consuming), `queueDepth`,
`oldestQueuedAgeMs`. `--probe` on `doctor` surfaces this; a static assembly check alone never
proves a call will go through.

### ask

Not a capability action: it never enters the gate or touches a backend. The host presents the
question through its own overlay surface and answers with exactly one of three **success** branches
(`ok:true`):

```json
{"v":2,"id":"…","op":"ask","ok":true,"choice":{"kind":"option","index":0,"label":"A"}}
{"v":2,"id":"…","op":"ask","ok":true,"choice":{"kind":"reject_all"}}
{"v":2,"id":"…","op":"ask","ok":true,"choice":{"kind":"reask"}}
```

- `option` — the user picked option `index`; `label` echoes the displayed text.
- `reject_all` — legitimate answer: none of the options fit; rework the options and ask again.
- `reask` — the question itself should be put differently; rework the question, not the option set.

Failure branches (`ok:false`) carry `cause` plus `error{code, retryable, exitCode}`:
`E_ASK_TIMEOUT` (3; the cause distinguishes "no answer within the window" from "the card lost
window focus while waiting"), `E_ASK_BUSY` (5; another card is on screen or the channel stopped),
`E_ASK_NO_SURFACE` (4; no presentation surface available), `E_INTERNAL` (6; host-side wiring
problem). `E_ASK_NO_REPLY` (3) is a CLI-side synthesis when the channel was reachable but no reply
arrived within the budget. There is no `status`/`cancel` follow-up for an ask.
