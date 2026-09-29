# Security Policy

## Supported versions

The published source repository is the only distribution channel. Fixes land on the main line; there
are no maintained release branches yet.

| Version | Supported |
|---|---|
| 0.1.x | yes |

## Reporting a vulnerability

**Do not open a public issue for a security problem.** Use GitHub's private reporting instead: the
repository's **Security** tab → **Report a vulnerability**. That opens a draft advisory visible only
to the maintainer.

Please include:

- the version (or commit) and the execution backend involved (accessibility / direct / Shizuku);
- the capability id and the request envelope, with anything sensitive redacted;
- what happened, and what you expected the interlock to do instead.

A reproduction beats a prose description. There is no bug-bounty programme; reports are read by the
maintainer and you will get a reply.

## What this library is, for threat-model purposes

Interlock Relay is a **capability relay**. It exposes a host app's device capabilities to a sandboxed
agent over a single file-based channel, and routes every call through an approval interlock that the
host can gate, observe and audit. The host keeps control of UI, dialogs, policy and execution.

Reports are especially interesting when they show any of the following.

- A call that reaches an execution backend **without passing the interlock**: a gate bypass, a queue
  arbitration race, an answer applied to the wrong request id, or a parked request resumed twice.
- An argument that escapes a capability's declared shape: path traversal through an output path,
  argument injection into a shell verb, or a self-targeted destructive call that gets past the
  "this target is the relay itself" guard.
- Anything that lets the guest side change a **host-side verdict** (approval state, access tier, shell
  opt-in, audit record) instead of only its own copy of the advisory files.
- Sensitive data reaching the run log in spite of the redactor, or an audit record that can be
  rewritten without detection.
- A crash or hang reachable from the guest side that leaves the channel unable to answer.

## By design, not vulnerabilities

- **The sandbox is not a security boundary against the host.** The guest runs under the host's uid, and
  the guest-side files this library materialises — the entry CLI, the capability list, the usage notes
  — are advisory. The guest can rewrite them, and by construction that cannot change any host-side
  decision.
- **The host owns policy.** Mount point, naming, wording, notification channel ids, which capabilities
  are usable, and what the approval prompt says are all injected by the host. A host that configures a
  permissive path policy, or supplies its own execution backend, is making its own choice.
- **Unattended confirmation fails closed.** With the default surface (`NoSurfaces`) there is nowhere to
  ask, so a call that requires confirmation fails with `E_GATE_NO_FOREGROUND` rather than proceeding.
- **Device-mutating routes ask first.** Shell verbs are individually opt-in, and coordinate injection is
  reachable only by naming it explicitly.
