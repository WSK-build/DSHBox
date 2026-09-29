/**
 * DSHBox · 垫片真机验证脚本（自包含，无第三方依赖）
 *
 * ## 用途
 *
 * 在**真实 Android 设备**上验证两件事：
 *   1. app-data 文件系统是否真的拒绝硬链接（整条兼容逻辑的前提）；
 *   2. `link-shim.mjs` 是否真的能兜住（退化为内容拷贝且语义不变）。
 *
 * 与 `run.mjs` 的分工：那个用「强制 EACCES 的变体」在 Linux 上模拟平台拒绝，
 * 覆盖面广（会话/写工具/附件三条链路的真实代码序列）；本脚本不模拟任何东西，
 * 直接打在真实平台文件系统上，是**落地性**的最终确认。
 *
 * ## 用法（两种都要跑，才能看出垫片的作用）
 *
 *   # ① 不带垫片：应看到平台拒绝硬链接
 *   node /path/to/device-test.mjs
 *
 *   # ② 带垫片：同一组断言应全部通过
 *   node --import /path/to/link-shim.mjs /path/to/device-test.mjs
 *
 * ## 建议的运行位置
 *
 * 必须落在 **app 的私有数据目录**下（`/data/user/0/<pkg>/files/...`）或该目录
 * 可达的 PRoot 视图内 —— 这是唯一会触发 FBE/FUSE 拒绝硬链接的位置。
 * 放在 `/sdcard`（FUSE 且多为 sdcardfs/exfat）或 `/data/local/tmp` 会得到
 * 不同结论，无法代表 DSH 的实际运行环境。
 */

import { link, mkdir, open, readFile, rm, stat, unlink, writeFile, chmod } from "node:fs/promises";
import { existsSync } from "node:fs";
import path from "node:path";

const results = [];
function check(name, cond, detail) {
  results.push({ name, pass: !!cond, detail: detail ?? "" });
}

const base = path.join(process.cwd(), ".dshbox-shim-test-" + process.pid);
await mkdir(base, { recursive: true });

console.log("=== 运行环境 ===");
console.log("  node    :", process.version);
console.log("  platform:", process.platform, process.arch);
console.log("  测试目录:", base);
console.log("");

// ── 1. 平台是否拒绝硬链接（前提验证）────────────────────────────────────
let linkVerdict;
{
  const src = path.join(base, "probe-src");
  const dst = path.join(base, "probe-dst");
  await writeFile(src, "PROBE");
  try {
    await link(src, dst);
    const [a, b] = await Promise.all([stat(src), stat(dst)]);
    const real = a.ino === b.ino;
    linkVerdict = real ? "platform-allows-hardlink" : "platform-silently-copied";
    console.log("[前提] 硬链接调用成功；是否为真硬链接(同 inode):", real);
  } catch (error) {
    linkVerdict = "platform-denies-hardlink";
    console.log("[前提] 硬链接被拒绝 ->", error.code, "|", String(error.message).slice(0, 90));
  }
  console.log("");
}

// ── 2. 发布语义：内容/权限/不覆盖（与 DSH 三条链路等价的最小断言）────────
{
  const dir = path.join(base, "publish");
  await mkdir(dir, { recursive: true });
  const src = path.join(dir, "object.bin");
  const dst = path.join(dir, "published.bin");
  await writeFile(src, "PAYLOAD", { mode: 0o600 });
  await chmod(src, 0o600);

  let publishErr = null;
  try {
    await link(src, dst);
  } catch (error) {
    publishErr = error;
  }
  check(
    "发布成功（垫片生效时应通过；无垫片且平台拒绝时应失败）",
    publishErr === null,
    publishErr ? publishErr.code : "",
  );
  if (publishErr === null) {
    check("目标已生成", existsSync(dst));
    check("内容一致", (await readFile(dst, "utf8")) === "PAYLOAD");
    const mode = (await stat(dst)).mode & 0o777;
    check("权限位保持 0600", mode === 0o600, "mode=0o" + mode.toString(8));

    // 「不覆盖」语义：目标已存在时必须 EEXIST，而非静默覆盖
    let again = null;
    try {
      await link(src, dst);
    } catch (error) {
      again = error;
    }
    check("目标已存在时抛 EEXIST（不覆盖语义）", again && again.code === "EEXIST", again ? again.code : "(未抛错)");
    check("已存在目标未被覆盖", (await readFile(dst, "utf8")) === "PAYLOAD");

    // 源文件保留（附件链路依赖：上游发布后还会 unlink 源文件，源必须还在）
    check("源文件保留（附件链路前提）", existsSync(src));
    let unlinkErr = null;
    try {
      await unlink(src);
    } catch (error) {
      unlinkErr = error;
    }
    check("随后 unlink 源文件不抛错", unlinkErr === null, unlinkErr ? unlinkErr.code : "");
  }
}

// ── 3. 另一种上游形态：open(wx) + 写入后发布（fs-local 写工具链路）────────
{
  const dir = path.join(base, "writetool");
  await mkdir(dir, { recursive: true });
  const temp = path.join(dir, ".stage");
  const target = path.join(dir, "written.txt");

  const handle = await open(temp, "wx", 0o600);
  await handle.chmod(0o600);
  await handle.writeFile("TOOL-OUTPUT", { encoding: "utf8" });
  await handle.close();

  let err = null;
  try {
    await link(temp, target);
  } catch (error) {
    err = error;
  }
  check("写工具发布成功", err === null, err ? err.code : "");
  if (err === null) {
    check("写工具内容一致", (await readFile(target, "utf8")) === "TOOL-OUTPUT");
  }
}

// ── 报告 ─────────────────────────────────────────────────────────────────
console.log("=== 结论 ===");
console.log("  平台硬链接行为:", linkVerdict);
console.log("");
let failed = 0;
for (const r of results) {
  console.log(`${r.pass ? "PASS" : "FAIL"}  ${r.name}${r.detail ? "   [" + r.detail + "]" : ""}`);
  if (!r.pass) failed++;
}
console.log(`\n${failed === 0 ? "ALL PASS" : "FAILED " + failed} (${results.length - failed}/${results.length})`);

if (linkVerdict === "platform-denies-hardlink" && failed > 0) {
  console.log("\n[提示] 平台拒绝硬链接且断言未全过 —— 若本次是「不带垫片」运行，属预期；");
  console.log("       请再用 `node --import link-shim.mjs device-test.mjs` 复跑，应全过。");
}

await rm(base, { recursive: true, force: true });
process.exit(failed === 0 ? 0 : 1);
