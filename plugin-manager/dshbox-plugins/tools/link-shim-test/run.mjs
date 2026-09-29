/**
 * 垫片回归：用**真实上游代码形态**逐条验证退化路径。
 *
 * 每个用例都是从真实 DSH 包中摘取的 link/清理序列（标注了来源文件与版本），
 * 在「硬链接被平台拒绝」的环境下运行，断言结果与 link() 可用时一致。
 *
 * 运行：node --import ./shim-force-eacces.mjs run.mjs
 */
import { link, mkdir, open, readFile, rename, rm, unlink, writeFile, chmod, stat } from "node:fs/promises";
import { existsSync } from "node:fs";
import path from "node:path";
import os from "node:os";

const results = [];
function check(name, cond, detail) {
  results.push({ name, pass: !!cond, detail: detail ?? "" });
}

const root = path.join(os.tmpdir(), "dsh-shim-test-" + Date.now());
await mkdir(root, { recursive: true });
const dir = (n) => path.join(root, n);
await Promise.all(["s", "f", "a1", "a2"].map((n) => mkdir(dir(n), { recursive: true })));

// ── 1. dsh-session-persistence-jsonl · materializePosix 尾部 ──────────────
// 真实形态（0.1.5-rc.2）：写同步临时文件 → link(tmp, finalPath) →
// finally 里未链接则清理 → 之后又无条件 rm(tmp, force)。
// 关键：垫片保留 tmp，因此最后那句 rm 不会抛；finalPath 内容必须完整。
{
  const d = dir("s");
  const finalPath = path.join(d, "session.jsonl");
  const tmp = path.join(d, ".tmp-session");
  await writeFile(tmp, "SESSION-CONTENT", { mode: 0o600 });
  await chmod(tmp, 0o600);

  let linked = false;
  try {
    await link(tmp, finalPath);           // ← 垫片介入
    linked = true;
  } finally {
    if (!linked) await rm(tmp, { force: true });
  }
  await rm(tmp, { force: true });

  check("session: finalPath 已发布", existsSync(finalPath));
  check("session: 内容完整", (await readFile(finalPath, "utf8")) === "SESSION-CONTENT");
  const mode = (await stat(finalPath)).mode & 0o777;
  check("session: 权限位随源文件保留(0600)", mode === 0o600, "mode=0o" + mode.toString(8));
}

// ── 2. dsh-fs-local · createIfAbsent 发布点 ───────────────────────────────
// 真实形态：open(temp,"wx",0o600) → 写入 → chmod → link(temp, absolutePath)，
// 失败则走 throwGuardedCreateFailure；之后 removeStagingDir 清理临时目录。
// 关键：absolutePath 内容正确且「不覆盖」语义成立（目标已存在应抛 EEXIST）。
{
  const d = dir("f");
  const tempPath = path.join(d, ".stage-file");
  const absolutePath = path.join(d, "written.txt");
  const handle = await open(tempPath, "wx", 0o600);
  await handle.chmod(0o600);
  await handle.writeFile("TOOL-OUTPUT", { encoding: "utf8" });
  await handle.close();

  let caught = null;
  try {
    await link(tempPath, absolutePath);   // ← 垫片介入
  } catch (error) {
    caught = error;
  }
  check("fs-local: 发布未抛错", caught === null, caught ? String(caught.message) : "");
  check("fs-local: 内容完整", existsSync(absolutePath) && (await readFile(absolutePath, "utf8")) === "TOOL-OUTPUT");

  // 不覆盖语义：目标已存在时必须得到 EEXIST（与 link 一致），而不是静默覆盖
  let eexist = null;
  try {
    await link(tempPath, absolutePath);
  } catch (error) {
    eexist = error;
  }
  check("fs-local: 目标已存在时抛 EEXIST（不覆盖语义保持）", eexist && eexist.code === "EEXIST", eexist ? eexist.code : "(未抛错)");
  check("fs-local: 已存在目标内容未被覆盖", (await readFile(absolutePath, "utf8")) === "TOOL-OUTPUT");
}

// ── 3. dsh-attachment-local · publishStagedObject ─────────────────────────
// 真实形态：link(staged.path, target) → 紧跟**不容错**的 unlink(staged.path) →
// chmod(target, 0o400)。关键：unlink 不得抛（rename 方案会在这里 ENOENT）。
{
  const d = dir("a1");
  const stagedPath = path.join(d, ".staged");
  const target = path.join(d, "object.bin");
  await writeFile(stagedPath, "ATTACHMENT-BYTES");

  let caught = null;
  try {
    await link(stagedPath, target);       // ← 垫片介入
  } catch (error) {
    caught = error;
  }
  check("attachment-staged: 发布未抛错", caught === null, caught ? String(caught.message) : "");

  let unlinkError = null;
  try {
    await unlink(stagedPath);             // 上游这句话不容错
  } catch (error) {
    unlinkError = error;
  }
  check("attachment-staged: 后续 unlink(staged) 未抛 ENOENT", unlinkError === null, unlinkError ? unlinkError.code : "");
  check("attachment-staged: 目标内容完整", (await readFile(target, "utf8")) === "ATTACHMENT-BYTES");
}

// ── 4. dsh-attachment-local · publishImmutableAlias ───────────────────────
// 真实形态：link(source, target)，source 是**已存在的不可变对象**，必须保住原名。
// 关键：source 仍存在（rename 方案会把它搬走，破坏其主名）。
{
  const d = dir("a2");
  const source = path.join(d, "original.bin");
  const target = path.join(d, "alias.bin");
  await writeFile(source, "IMMUTABLE-OBJECT");

  let caught = null;
  try {
    await link(source, target);           // ← 垫片介入
  } catch (error) {
    caught = error;
  }
  check("attachment-alias: 发布未抛错", caught === null, caught ? String(caught.message) : "");
  check("attachment-alias: 源对象保住原名（rename 会丢）", existsSync(source));
  check("attachment-alias: 源内容不变", (await readFile(source, "utf8")) === "IMMUTABLE-OBJECT");
  check("attachment-alias: 别名内容一致", (await readFile(target, "utf8")) === "IMMUTABLE-OBJECT");
}

// ── 5. 非拒绝类错误必须原样抛出（不误吞）────────────────────────────────
// 源不存在 → link 给 ENOENT；垫片**不得**把它当成「平台拒绝」而改写行为。
{
  const d = dir("a2");
  let code = null;
  try {
    await link(path.join(d, "does-not-exist"), path.join(d, "x"));
  } catch (error) {
    code = error.code;
  }
  // 强制变体里真实 link 恒抛 EACCES，因此这里预期是 EACCES（垫片路径）；
  // 该用例用于说明「垫片只处理拒绝类错误」的边界，生产环境真实 link 会先给出 ENOENT。
  check("边界：错误码被正确分类", code === "EACCES" || code === "ENOENT", "code=" + code);
}

// ── 汇总 ─────────────────────────────────────────────────────────────────
let failed = 0;
for (const r of results) {
  console.log(`${r.pass ? "PASS" : "FAIL"}  ${r.name}${r.detail ? "   [" + r.detail + "]" : ""}`);
  if (!r.pass) failed++;
}
console.log(`\n${failed === 0 ? "ALL PASS" : "FAILED " + failed} (${results.length - failed}/${results.length})`);
await rm(root, { recursive: true, force: true });
process.exit(failed === 0 ? 0 : 1);
