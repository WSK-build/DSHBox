package developer.access.root

import java.io.File

/**
 * Root 通道的 guest 侧客户端与 App 侧执行约定。
 *
 * ## 为什么客户端脚本由本模块生成
 *
 * `android-root` 是这条通道对 DSH 的**唯一入口**，属于能力本身的实现，故脚本正文
 * 与落盘逻辑都在本模块，宿主只提供写入位置（工作区 CLI 目录）。
 *
 * ## 为什么用 python3 而不是 shell
 *
 * 帧协议是「4 字节大端长度 + JSON」，输出用 base64 承载。shell 处理二进制长度前缀
 * 不可靠（`printf`/`read` 在不同 shell 下行为不一），而运行环境层自带 python3。
 *
 * ## 引号透传
 *
 * 参数逐个用 `shlex.quote` 引用后再拼串。若直接 `" ".join(argv)`，
 * `android-root sh -c "echo a && echo b"` 会在 `su -c` 那一层被打散，
 * 这正是需要避免的嵌套转义失败。
 */
object RootGateway {

    /** 命令名：与 `root` / `su` 区分（那两者在本语境下语义冲突）。 */
    const val CLI_NAME = "android-root"

    /**
     * 把脚本写进给定的 CLI 目录并置可执行位。
     *
     * **先删后写**：该目录里的旧脚本可能已被固化为只读（0555），直接覆写会 EACCES；
     * 删除只依赖目录写权限，因此删掉重建最稳。
     */
    fun provision(binDir: File) {
        binDir.mkdirs()
        val script = File(binDir, CLI_NAME)
        script.delete()
        script.writeText(SCRIPT)
        script.setExecutable(true, false)
    }

    /** 删除脚本：开关关闭时命令彻底不可见（与 ADB 通道的关闭口径对称）。 */
    fun remove(binDir: File) {
        File(binDir, CLI_NAME).delete()
    }

    /** 客户端脚本正文。 */
    val SCRIPT: String = """
        |#!/usr/bin/env python3
        |import base64, json, os, shlex, socket, struct, sys
        |
        |SOCK = "${developer.access.core.ElevationPaths.GUEST_ROOT_SOCKET}"
        |
        |
        |def recv_exact(sock, count):
        |    buf = b""
        |    while len(buf) < count:
        |        chunk = sock.recv(count - len(buf))
        |        if not chunk:
        |            return None
        |        buf += chunk
        |    return buf
        |
        |
        |def read_frame(sock):
        |    head = recv_exact(sock, 4)
        |    if not head:
        |        return None
        |    size = struct.unpack(">I", head)[0]
        |    body = recv_exact(sock, size)
        |    return json.loads(body) if body else None
        |
        |
        |def main():
        |    if len(sys.argv) < 2:
        |        sys.stderr.write("usage: ${CLI_NAME} <command>\n")
        |        return 2
        |    cmd = " ".join(shlex.quote(arg) for arg in sys.argv[1:])
        |    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        |    try:
        |        sock.connect(SOCK)
        |    except OSError as err:
        |        sys.stderr.write("${CLI_NAME}: cannot reach root executor: %s\n" % err)
        |        return 1
        |    payload = json.dumps({"cmd": cmd}).encode("utf-8")
        |    sock.sendall(struct.pack(">I", len(payload)) + payload)
        |    while True:
        |        frame = read_frame(sock)
        |        if frame is None:
        |            return 1
        |        if "data" in frame:
        |            fd = 1 if frame.get("stream") == "stdout" else 2
        |            os.write(fd, base64.b64decode(frame["data"]))
        |        elif "code" in frame:
        |            return int(frame["code"])
        |        elif "error" in frame:
        |            sys.stderr.write("${CLI_NAME}: %s\n" % frame["error"])
        |            return 1
        |
        |
        |if __name__ == "__main__":
        |    sys.exit(main())
        |""".trimMargin()
}
