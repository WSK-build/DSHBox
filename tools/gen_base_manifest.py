#!/usr/bin/env python3
"""Generate the DSHBox base-layer manifest (tools/gen_base_manifest.py).

Reads a Debian suite's binary arch Packages index (path or URL, gzip ok) and
computes the package-name closure:

    (Essential ∪ Priority:required) ∪ build_base.sh seed list
    expanded over Pre-Depends/Depends (--no-install-recommends: no Recommends)

For each alternative group ("a | b"), the FIRST candidate that resolves to a
real package in the index wins (apt-like); virtual names fall back to the
first Provides. Emits the plain-text manifest consumed by
sandbox-manager/online/BaseManifest.kt.

stdlib only. Usage:
  python3 tools/gen_base_manifest.py \
      --index-src https://deb.debian.org/debian/dists/trixie/main/binary-arm64/Packages.gz \
      --suite trixie --arch arm64 \
      --output app/src/main/assets/runtime-online/base-manifest.txt
"""
import argparse
import datetime
import gzip
import re
import sys
import urllib.request

# runtime-bundle/build_base.sh 的 --no-install-recommends 安装清单（逐项对应）。
SEEDS = [
    "ca-certificates", "curl", "wget", "openssl", "git", "openssh-client", "rsync",
    "coreutils", "util-linux", "procps", "findutils", "grep", "sed", "gawk",
    "diffutils", "tar", "gzip", "bzip2", "xz-utils", "zip", "unzip", "file",
    "less", "python3", "python3-pip", "python3-venv", "locales", "tzdata", "apt-utils",
]
# S8 功能自检的关键二进制（与 manifest 一起冻结）。
SMOKE_BINARIES = [
    "/bin/bash", "/usr/bin/dpkg", "/usr/bin/apt-get", "/bin/tar",
    "/usr/bin/python3", "/usr/bin/git", "/usr/bin/curl",
]
LOCALE_WHITELIST = ["C", "C.UTF-8", "zh_CN", "zh_CN.utf8"]

CLAUSE_SPLIT = re.compile(r"\s*,\s*")
ALT_SPLIT = re.compile(r"\s*\|\s*")
VERSION_RE = re.compile(r"\([^)]*\)")
ARCH_QUAL_RE = re.compile(r":[A-Za-z0-9][A-Za-z0-9_.+-]*$")


def load_index(src: str):
    if src.startswith("http://") or src.startswith("https://"):
        req = urllib.request.Request(src, headers={"User-Agent": "DSHBox-manifest-gen/1"})
        with urllib.request.urlopen(req, timeout=180) as resp:
            data = resp.read()
    else:
        with open(src, "rb") as f:
            data = f.read()
    if data[:2] == b"\x1f\x8b":
        data = gzip.decompress(data)
    packages = {}
    for para in re.split(r"\n\s*\n", data.decode("utf-8", "replace")):
        fields = {}
        cur = None
        for line in para.splitlines():
            if not line.strip():
                continue
            if line[0] in " \t":
                if cur:
                    fields[cur] += "\n" + line.strip()
            elif ":" in line:
                key, rest = line.split(":", 1)
                cur = key.strip()
                fields[cur] = rest.strip()
        name = fields.get("Package")
        if name:
            packages[name] = fields
    return packages


def clean_name(alt: str) -> str:
    alt = VERSION_RE.sub("", alt).strip()
    alt = ARCH_QUAL_RE.sub("", alt).strip()
    return alt


def build_providers(packages):
    providers = {}
    for name, fields in packages.items():
        provides = fields.get("Provides", "")
        if not provides:
            continue
        for clause in CLAUSE_SPLIT.split(provides):
            prov = clean_name(clause.split("|")[0])
            if prov and prov not in providers:
                providers[prov] = name
    return providers


def dep_targets(field, packages, providers, warnings, context):
    if not field:
        return []
    out = []
    for clause in CLAUSE_SPLIT.split(field):
        picked = None
        for alt in ALT_SPLIT.split(clause):
            alt = clean_name(alt)
            if not alt:
                continue
            if alt.startswith("/"):
                warnings.append(f"{context}: file dependency {alt!r} skipped")
                continue
            picked = alt if alt in packages else providers.get(alt)
            if picked:
                break
        if picked:
            out.append(picked)
        else:
            warnings.append(f"{context}: unresolved clause {clause!r}")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--index-src", required=True, help="Packages file path or URL (gzip ok)")
    ap.add_argument("--suite", required=True)
    ap.add_argument("--arch", required=True)
    ap.add_argument("--output", required=True)
    args = ap.parse_args()

    packages = load_index(args.index_src)
    print(f"index paragraphs: {len(packages)}")
    providers = build_providers(packages)
    warnings = []

    required = {n for n, f in packages.items()
                if f.get("Essential") == "yes" or f.get("Priority") == "required"}
    seeds = []
    for s in SEEDS:
        r = s if s in packages else providers.get(s)
        if r:
            seeds.append(r)
        else:
            warnings.append(f"seed {s!r} not found in index")

    seen = set(required) | set(seeds)
    queue = list(seen)
    while queue:
        name = queue.pop()
        fields = packages[name]
        for field in ("Pre-Depends", "Depends"):
            for t in dep_targets(fields.get(field), packages, providers, warnings, name):
                if t not in seen:
                    seen.add(t)
                    queue.append(t)

    names = sorted(seen)
    today = datetime.date.today().isoformat()
    body = [
        "# DSHBox base-layer manifest — generated by tools/gen_base_manifest.py; do not hand-edit.",
        f"# scope = Essential/required ∪ build_base.sh seed closure (Depends+Pre-Depends, no Recommends)",
        "manifest_version=1",
        f"suite={args.suite}",
        f"arch={args.arch}",
        f"generated_at={today}",
        "",
        "[packages]",
        *names,
        "",
        "[smoke-binaries]",
        *SMOKE_BINARIES,
        "",
        "[locale-whitelist]",
        *LOCALE_WHITELIST,
        "",
    ]
    with open(args.output, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(body))
    print(f"manifest packages: {len(names)}")
    print(f"warnings: {len(warnings)}")
    for w in sorted(set(warnings))[:50]:
        print("  -", w)


if __name__ == "__main__":
    sys.exit(main())
