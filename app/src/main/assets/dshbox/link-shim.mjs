/**
 * DSHBox · Android 硬链接兼容垫片（app 侧，运行期注入）
 *
 * ## 为什么存在
 *
 * Android app-data 文件系统（FBE/FUSE）**拒绝硬链接**（EACCES/EPERM/ENOTSUP/ENOSYS），
 * 而 DSH 用 `link()` 作为「不可覆盖地发布一个文件」的原语，于是会话持久化、写工具、
 * 附件发布三条链路在真机上全部失败。
 *
 * ## 为什么用垫片而不是改写 DSH 源码
 *
 * 历史方案是在安装期/构建期**改写 DSH 的 JS 文件**，把 `link()` 调用点替换成
 * `rename()`/`copyFile()`。那要求补丁锚点与上游源码逐字节匹配，上游每次调整
 * （0.1.1 插入 `lstat`、0.1.5 拆分发布点……）都会让整块补丁**静默跳过**，
 * 维护成本高且失效无告警。
 *
 * 本垫片改在**运行期**拦截 `node:fs/promises` 的 `link`，只做一件事：
 * 真正的硬链接被平台拒绝时，退化为语义等价的内容拷贝。DSH 源码**一个字节都不改**，
 * 因此不存在锚点漂移，也不受上游重构影响。
 *
 * 启动方式（app 侧在 PRoot 命令里注入，见 SandboxProcessRunner）：
 *   node --import /opt/dshbox/link-shim.mjs <dsh 入口> --profile web
 *
 * ## 语义等价性（关键设计）
 *
 * `copyFile(existing, newPath, COPYFILE_EXCL)` 与 `link(existing, newPath)` 的对照：
 *
 * | 性质 | link() | copyFile(EXCL) | 是否等价 |
 * |---|---|---|---|
 * | 目标不存在时创建 | ✅ | ✅ | 等价 |
 * | 目标已存在 | 抛 EEXIST | 抛 EEXIST | 等价（调用方按 EEXIST 分支处理） |
 * | 源文件保留 | ✅ | ✅ | 等价 |
 * | 内容一致 | ✅ | ✅ | 等价 |
 * | 权限位 | 同一 inode，天然一致 | 需显式补 | **垫片显式补 chmod** |
 * | 后续改动互不影响 | ❌（同一 inode） | ✅（独立副本） | 更强，各调用点均为「发布后即不再改」 |
 *
 * 特别注意：`copyFile` **保留源文件**这一点，让它同时解决了另一个坑——
 * `dsh-attachment-local` 在发布后有一句**不容错**的 `await unlink(staged.path);`；
 * 若用 `rename()` 把源文件移走，那句 unlink 会抛 ENOENT 并让发布报
 * ATTACHMENT_WRITE_FAILED（字节其实已落盘）。垫片不移动源文件，该 unlink 照常成功。
 *
 * ## 覆盖范围
 *
 * DSH 的三条失败链路全部只通过 `node:fs/promises` 的**具名导入**使用 link
 * （已对 0.1.1/0.1.5 逐包核实：`dsh-session-persistence-jsonl`、`dsh-fs-local`、
 * `dsh-attachment-local` 均为 `import { …, link, … } from "node:fs/promises"`）。
 * 未发现 `linkSync` 使用。
 *
 * ## ⚠️ 已知限制（**未覆盖**，勿误以为全量兜底）
 *
 * 本垫片**只替换 `node:fs/promises` 的异步 `link`**，**不覆盖**以下入口：
 *
 * | 入口 | 状态 | 说明 |
 * |---|---|---|
 * | `node:fs.linkSync` | ❌ 未覆盖 | 同步版；上游若改用它会**静默失效**（不报错、行为退回平台默认） |
 * | `node:fs.link`（回调版） | ❌ 未覆盖 | 同上 |
 * | `node:fs.promises.link` | ✅ 覆盖 | 与 `node:fs/promises` 是同一对象，替换互通 |
 *
 * **若上游将来在别处改用同步版**：会话/附件会在真机上重新出现 EACCES，
 * 而本文件不会有任何告警。排查时**先确认是否所有 link 调用仍走异步版**：
 *
 * ```bash
 * # 在解出的 DSH 层里搜同步版用法（应为空）
 * grep -rn "linkSync" node_modules/@deepseek-ai/<包名>/lib/
 * ```
 *
 * 该边界同时登记在 `DSH_COMPAT_NOTES.md` #1（含上游升级核对清单）。
 */

import fsp from "node:fs/promises";
import { constants } from "node:fs";
import { syncBuiltinESMExports } from "node:module";

/** 平台拒绝硬链接时可能给出的错误码（各家 ROM/内核不一，全部纳入）。 */
const LINK_DENIED = new Set(["EACCES", "EPERM", "ENOTSUP", "ENOSYS"]);

const realLink = fsp.link.bind(fsp);

/**
 * 与 `link()` 契约一致的链接实现：平台拒绝硬链接时退化为内容拷贝。
 *
 * 只在该退化的场景下介入——真实可用的硬链接路径完全不受影响，
 * 其它错误（ENOENT/EXDEV/EEXIST…）原样抛出，保持调用方的既有分支。
 *
 * 不导出：本模块以 `--import` 预加载，靠**副作用**（替换 `fsp.link`）生效，
 * 无外部调用方。
 */
async function linkWithFallback(existing, newPath) {
  try {
    return await realLink(existing, newPath);
  } catch (error) {
    if (!error || !LINK_DENIED.has(error.code)) throw error;

    // EXCL 保留 link() 的「不覆盖」语义：目标已存在时抛 EEXIST，
    // 与上游 catch 分支（校验目标完整性后继续）严格对应。
    await fsp.copyFile(existing, newPath, constants.COPYFILE_EXCL);

    // link() 共享 inode 因而天然继承源文件权限；拷贝是新建文件，
    // 默认权限受 umask 影响，这里从源文件补齐（尽力而为，不因此让发布失败）。
    try {
      const st = await fsp.stat(existing);
      await fsp.chmod(newPath, st.mode);
    } catch {
      /* 权限补齐失败不影响内容正确性 */
    }
  }
}

fsp.link = linkWithFallback;
// 让已经 `import { link } from "node:fs/promises"` 的模块看到替换后的实现
// （ESM 内建具名导入是活绑定，必须显式重同步）。
syncBuiltinESMExports();
