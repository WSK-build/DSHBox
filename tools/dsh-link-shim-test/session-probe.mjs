// 「会话持久化」链路探针：复刻真实 DSH materializePosix 的发布动作。
// 无垫片时应以 EACCES 失败（这正是 DSH 会话报错的根因）；带垫片时应成功。
import { mkdir, writeFile, link, readFile, rm, stat } from "node:fs/promises";

const dir = "/work/.dsh/probe";
await mkdir(dir, { recursive: true });
const tmp = dir + "/.tmp";
const final = dir + "/session.jsonl";

await writeFile(tmp, '{"event":"hello"}', { mode: 0o600 });
let mode;
try {
  await link(tmp, final);           // ← materializePosix 的核心一步
  mode = "publish-ok";
} catch (e) {
  mode = "publish-failed:" + e.code;
}
console.log("  发布结果:", mode);
if (mode === "publish-ok") {
  console.log("  内容:", await readFile(final, "utf8"));
  console.log("  权限:", "0o" + ((await stat(final)).mode & 0o777).toString(8));
}
await rm(tmp, { force: true });
