/**
 * Android 硬链接兼容层，以 node --import 预加载。
 *
 * 替换 node:fs/promises.link：仅对 LINK_DENIED 中的平台拒绝错误
 * 回退到 COPYFILE_EXCL，保留源文件及目标已存在时的 EEXIST 语义。
 * 拷贝后尝试同步权限；权限同步失败不影响已完成的内容复制。
 *
 * 只处理异步 Promise 接口，不替换 fs.link 回调接口或 fs.linkSync。
 * syncBuiltinESMExports 用于同步内建模块的具名导出。
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
