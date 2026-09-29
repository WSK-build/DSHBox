/**
 * 测试用变体：强制让「真实 link」以 EACCES 失败，用来在 Linux 上模拟
 * Android app-data 文件系统拒绝硬链接的环境。
 *
 * 除强制失败点外，退化逻辑与生产垫片 (link-shim.mjs) 完全一致。
 * 之所以不给生产垫片加「测试开关」：那会把测试专用路径带进真机代码。
 */
import fsp from "node:fs/promises";
import { constants } from "node:fs";
import { syncBuiltinESMExports } from "node:module";

const LINK_DENIED = new Set(["EACCES", "EPERM", "ENOTSUP", "ENOSYS"]);

/** 模拟 Android：内核/平台直接拒绝硬链接。 */
const realLink = async () => {
  const error = new Error("EACCES: permission denied, link (simulated Android FBE)");
  error.code = "EACCES";
  throw error;
};

export async function linkWithFallback(existing, newPath) {
  try {
    return await realLink(existing, newPath);
  } catch (error) {
    if (!error || !LINK_DENIED.has(error.code)) throw error;
    await fsp.copyFile(existing, newPath, constants.COPYFILE_EXCL);
    try {
      const st = await fsp.stat(existing);
      await fsp.chmod(newPath, st.mode);
    } catch {
      /* ignore */
    }
  }
}

fsp.link = linkWithFallback;
syncBuiltinESMExports();
