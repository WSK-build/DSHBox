#!/usr/bin/env python3
"""发布前个人数据泄露审查（source/ 目录）。

检查项：
  ① 本机绝对路径（Windows 盘符 / macOS / Linux 家目录）
  ② 开发者用户名与设备标识
  ③ 密钥/凭据文件是否存在
  ④ 邮箱与代理地址
  ⑤ 构建产物与临时文件残留

用法：python tools/privacy_audit.py [--root <目录>]
退出码 0 = 无发现；1 = 有发现（会在输出中逐条列出）。
"""
import argparse
import os
import re
import sys

SKIP_DIRS = {
    "build", ".gradle", ".kotlin", ".idea", "node_modules",
    ".git", "__pycache__", ".playwright-mcp",
}
TEXT_EXT = {
    ".kt", ".kts", ".java", ".md", ".xml", ".js", ".mjs", ".json", ".sh",
    ".html", ".css", ".toml", ".properties", ".py", ".txt", ".yml", ".yaml",
    ".pro", ".cfg", ".gitignore", ".xml",
}

# (名称, 正则, 说明)
PATTERNS = [
    ("Windows 用户目录", re.compile(r"[A-Za-z]:[\\/]+Users[\\/]+[A-Za-z0-9_.\-]+"), "本机绝对路径"),
    ("Windows 盘符路径", re.compile(r"(?<![A-Za-z0-9_])[A-Za-z]:[\\/]{1,2}(?!Users)[A-Za-z0-9_.\-]+"), "本机绝对路径"),
    ("macOS 家目录", re.compile(r"/Users/[A-Za-z0-9_.\-]+"), "本机绝对路径"),
    ("Linux 家目录", re.compile(r"/home/[A-Za-z0-9_.\-]+"), "本机绝对路径"),
    ("临时目录", re.compile(r"/tmp/[A-Za-z0-9_.\-]+"), "可能含本机痕迹"),
    ("邮箱地址", re.compile(r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}"), "个人信息"),
    ("私网/代理地址", re.compile(r"\b(?:10|172|192\.168)\.\d{1,3}\.\d{1,3}\.\d{1,3}\b"), "网络地址"),
    ("设备序列号样式", re.compile(r"\b[0-9A-Z]{14,16}\b"), "设备标识"),
    # 机型代号：厂商内部型号（如 "V" 开头四位数字、iPad 的 "iPA"、
    # 三星的 "SM-"、以及通用「字母+三位数字」形态）。
    # 这类串写进文档等于把开发/测试用机固定暴露，且对读者毫无价值
    # （应写成「手机」「平板」「Android 15 设备」）。v1.3.1 实测在
    # CHANGES / 测试夹具 / 设备记录里各有一处，故补此规则。
    ("机型代号样式", re.compile(r"\b(?:[A-Z]\d{4}[A-Z]?|iP[AB]\d{4}|SM-[A-Z]\d{3}[A-Z]?|[A-Z]{1,2}\d{3}[A-Z]{1,3})\b"), "设备标识"),
]

# 允许出现的白名单（每条都写明理由；新增条目必须同样写明依据）。
# 原则：只放行「确定与开发者个人信息无关」的内容，宁可有噪音也不放宽真风险。
ALLOW = [
    # —— 网络与示例地址 ——
    re.compile(r"127\.0\.0\.1"),
    re.compile(r"0\.0\.0\.0"),
    re.compile(r"\blocalhost\b"),
    re.compile(r"example\.com"),
    re.compile(r"noreply\.github\.com"),           # GitHub 保密邮箱
    re.compile(r"users\.noreply\.github\.com"),
    re.compile(r"10\.255\.255\.254"),               # WSL 默认 DNS，非个人网络

    # —— guest（沙箱内）路径：这些是发给 Debian 沙箱的路径，不是本机路径 ——
    re.compile(r"/tmp/\.dshbox"),                   # guest 内工作临时目录
    re.compile(r"/tmp/dsh-stage"),                  # guest 内 DSH 换层暂存
    re.compile(r"/tmp/dshapp-"),                    # 自带 runtime 安装路径（guest）
    re.compile(r"/tmp/(deep|sandbox|x|live-spill|old-spill)\b"),  # 单测夹具虚构路径
    re.compile(r"/tmp/\.\.\."),                     # 文档里的省略写法

    # —— Kotlin 语法误匹配 ——
    re.compile(r"this@"),                           # this@Label 被当成邮箱

    # —— 上游（Termux）自带内容：非我方数据，公开发布 ——
    re.compile(r"/Users/fornwall"),                 # Termux 作者机器路径（Android Studio 模板注释）

    # —— 测试常量：十六进制/时间戳占位，非真实设备号 ——
    re.compile(r"0123456789ABCDEF"),
    re.compile(r"1700000000000L?"),
    re.compile(r"X{14,16}"),
    re.compile(r"ABCDEFGHIJKLMNO"),

    # —— 文档里的省略写法 ——
    re.compile(r"[A-Za-z]:[\\/]Users[\\/]\.\.\."),
    re.compile(r"^\s*//"),                          # 注释里的说明
]

SECRET_FILES = [
    "keystore.properties", "local.properties", "*.jks", "*.keystore",
    "google-services.json", ".env", "*.p12", "*.pem", "id_rsa", "*.ppk",
]


def is_allowed(line: str) -> bool:
    return any(p.search(line) for p in ALLOW)


def scan(root: str):
    findings = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, root)
            ext = os.path.splitext(name)[1].lower()
            if ext not in TEXT_EXT and name not in TEXT_EXT:
                continue
            try:
                with open(path, "r", encoding="utf-8", errors="replace") as fh:
                    for lineno, line in enumerate(fh, 1):
                        if is_allowed(line):
                            continue
                        for label, rx, kind in PATTERNS:
                            for m in rx.finditer(line):
                                findings.append((rel, lineno, label, kind, m.group(0)))
            except OSError:
                continue
    return findings


def find_secret_files(root: str):
    import fnmatch
    hits = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            for pat in SECRET_FILES:
                if fnmatch.fnmatch(name, pat):
                    hits.append(os.path.relpath(os.path.join(dirpath, name), root))
    return hits


def gitignored_patterns(root: str):
    """读取 .gitignore 里的非空、非注释行。"""
    path = os.path.join(root, ".gitignore")
    if not os.path.isfile(path):
        return []
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        return [ln.strip() for ln in fh
                if ln.strip() and not ln.strip().startswith("#")]


def is_gitignored(rel_path: str, patterns) -> bool:
    """该相对路径是否被 .gitignore 覆盖（支持 name / /name / *.ext / dir/ 写法）。"""
    import fnmatch
    rel = rel_path.replace("\\", "/")
    name = os.path.basename(rel)
    for raw in patterns:
        pat = raw.lstrip("/").rstrip("/")
        if not pat:
            continue
        if fnmatch.fnmatch(name, pat) or fnmatch.fnmatch(rel, pat):
            return True
        # 目录前缀写法：build/ 应覆盖 build/xxx
        if rel == pat or rel.startswith(pat + "/"):
            return True
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    ap.add_argument(
        "--strict",
        action="store_true",
        help="严格模式：被 .gitignore 覆盖的本地凭据文件同样计为失败（用于校验待发布副本）。",
    )
    args = ap.parse_args()
    root = os.path.abspath(args.root)

    print(f"审查根目录：{root}\n")
    ignore_lines = gitignored_patterns(root)

    print("=== ① 凭据/密钥文件 ===")
    secrets = find_secret_files(root)
    secret_ignored = {}
    if secrets:
        for s in secrets:
            covered = is_gitignored(s, ignore_lines)
            secret_ignored[s] = covered
            tag = "已被 .gitignore 覆盖（仅本地，不会随发布泄露）" if covered \
                else "!! 未被 .gitignore 覆盖，有随发布泄露的风险"
            print(f"  ! {s}")
            print(f"      {tag}")
    else:
        print("  无（keystore.properties / local.properties / *.jks 等均不存在）")

    print("\n=== ② 文本内容扫描 ===")
    findings = scan(root)
    blocking = []
    local_only = []
    for item in findings:
        rel = item[0]
        (local_only if is_gitignored(rel, ignore_lines) else blocking).append(item)

    def dump(items):
        by_file = {}
        for rel, lineno, label, kind, hit in items:
            by_file.setdefault(rel, []).append((lineno, label, kind, hit))
        for rel in sorted(by_file):
            print(f"\n  {rel}")
            for lineno, label, kind, hit in by_file[rel][:12]:
                print(f"    L{lineno}  [{label}/{kind}] {hit}")
            if len(by_file[rel]) > 12:
                print(f"    … 另有 {len(by_file[rel]) - 12} 处")

    if not findings:
        print("  无发现")
    else:
        if blocking:
            print(f"  — 待发布内容中的发现（{len(blocking)} 处）—")
            dump(blocking)
        if local_only:
            print(f"\n  — 已被 .gitignore 覆盖的本地文件（{len(local_only)} 处，不会随发布泄露）—")
            dump(local_only)

    unignored_secrets = [s for s, cov in secret_ignored.items() if not cov]
    blockers = len(blocking) + len(unignored_secrets)
    if args.strict:
        blockers += len(local_only) + len([s for s, cov in secret_ignored.items() if cov])

    print(f"\n{'=' * 56}")
    if blockers == 0:
        print("结论：待发布内容未发现个人数据泄露 ✔")
        if local_only or secrets:
            print("       （本地凭据文件已列出，但均被 .gitignore 覆盖，不会进入仓库）")
    else:
        print(f"结论：{blockers} 处会随发布泄露，必须处理（见上）")
    return 0 if blockers == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
