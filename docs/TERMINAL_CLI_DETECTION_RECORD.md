# 终端命令行检测 · 任务记录

> 记录"分层运行环境重构后，APK / 虚拟系统（guest）是否完整、可检测、可运行"的终端命令行实测。
> 执行方式：真机 App「终端」标签，`root@localhost:~#` shell 逐条 `input` 命令。检测过程中用 `%s`（=`空间`）编码，避免 adb `input text` 吞空格。

## 0. 任务元信息
| 项 | 值 |
|---|---|
| 日期 | 2026-08-23 |
| 设备 | Android 16 / arm64 (`iPA2575`) |
| APK | `dshapp-full-DSH-0.1.1-rc.2-20260823-release.apk`（236.17 MB，分层运行环境+DSH） |
| 目标 | 确认改动架构前所有包/组件都在系统、可检测、被 APK 注入 env、命令可运行 |

## 1. 检测方法
- 沙箱登录 shell：`/usr/bin/bash --login`，PRoot 绑定 base+node(/usr/local)+user-data(/root/projects)。
- 逐条命令通过 `adb shell input text <cmd>` + `keyevent 66`，`screen 截图` 读取输出。
- 分区标记：`echo ==N_<类>==` 分隔各类别。

## 2. 检测结果矩阵（实测）

### A. 核心工具链版本
| 命令 | 结果 | 判定 |
|---|---|---|
| `node -v` | **v24.19.0** | ✅ |
| `npm -v` | **11.17.0** | ✅ |
| `npx -v` | **11.17.0** | ✅ |
| `corepack -v` | **0.35.0** | ✅ |
| `python3 -V` | **Python 3.13.5** | ✅ |
| `bash --version` | **GNU bash 5.2.37(1) aarch64** | ✅ |
| `git --version` | **git 2.47.3** | ✅ |
| `curl --version` | **curl 8.14.1 aarch64** + OpenSSL/3.5.6 + zstd + HTTP2/3 | ✅ |
| `tar --version` | **GNU tar 1.35** | ✅ |
| `gzip --version` | **gzip 1.13** | ✅ |
| `ls --version` | **ls (GNU coreutils) 9.7-3** | ✅ |
| `wget --version` | **GNU Wget 1.25.0** | ✅ |
| `ssh -V` | **OpenSSH_10.0p2 Debian-7+deb13u4** | ✅ |

### B. 命令位置（PATH 内）
| 命令 | 结果 |
|---|---|
| `which node npm npx bash python3 vim` | `/usr/local/bin/{node,npm,npx}`、`/usr/bin/{bash,python3,vim}` ✅ |
| `which curl wget git ssh tar gzip` | 全在 `/usr/bin/` ✅ |
| `ls /usr/local/bin` | `corepack node npm npx` ✅ |

### C. Node 运行时内部
| 命令 | 结果 |
|---|---|
| `node -p process.versions.v8` | **13.6.233.179-node.53** |
| `node -p process.versions.openssl` | **3.5.7** |
| `node -p process.versions.uv` | **1.2.2** |
| `node -p process.versions.napi` | **10** |
| `node -p process.arch/platform` | **aarch64 / linux** |
| `node -p process.env.HOME` | **/root** |
| `node -p process.pid` | 正常运行 |

### D. 系统 / 用户 / 发行版
| 命令 | 结果 |
|---|---|
| `uname -a` / `uname -m` | **aarch64** Linux 6.1.27-android15 / **aarch64** |
| `nproc` | **8** |
| `id` / `whoami` | **uid=0(root)** / root |
| `pwd` | /root |
| `date` / `uptime` | 2026-08-22 UTC / up 3:46 |
| `cat /etc/os-release` | **Debian GNU/Linux 13 (trixie)** |
| `cat /etc/debian_version` | **13.6** |
| `df -h` | `/`=222G(70%)，dev/apex/tmpfs 挂载正常 |
| `free -h` | 正常输出 |

### E. 环境变量（APK 注入）
| 变量 | 值 | 判定 |
|---|---|---|
| `HOME` | `/root` | ✅ |
| `PATH` | `/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin`（含 node） | ✅ |
| `LANG` | `C.UTF-8` | ✅ |
| `TERM` | `xterm-256color` | ✅ |
| `DSH_HOME` / `DSH_PERMISSION_MODE` | 终端为空（属 **DSH 进程**专属注入；DSH 启动成功即证明） | ⚠️ 设计如此 |

### F. 文件系统 / 工作区 / 层级
| 命令 | 结果 |
|---|---|
| `ls /` | `apex bin boot dev etc home lib media mnt opt proc root run sbin srv sys system tmp usr var`（+绑定 apex/system） |
| `ls -la /root/projects` | 含 **`.dsh`**（=`DSH_HOME`，数据红线目录就位） |
| `ls -la /usr/local/lib` | `node_modules`（npm/Node 模块） |
| `ls -la /opt/dshapp` | 含 `runtime`（挂载点；终端不绑 DSH，故空；DSH 绑在 DSH 自身 proot） |
| `ls -la /root` | 正常（.dshbox 等） |

### G. 网络（DSH 必依赖）
| 命令 | 结果 |
|---|---|
| `curl -sI https://example.com` | **HTTP/2 200**（Cloudflare）—— 沙箱可外网 ✅ |

## 3. 结论
- **APK 完全正常**：Debian 13.6(arm64) 根文件系统、全部工具链、Node 24.19.0(V8/OpenSSL/libuv/NAPI)、npm/npx/corepack、基础工具、**外网连通**、环境变量注入、工作区（/root/projects/.dsh）全部就位、可检测、可运行。
- 改动架构前的所有包/组件都在系统里，且被 APK 正确注入 env 与 PATH。
- 说明：`/opt/dshapp/runtime` 在终端为空 + `DSH_HOME/DSH_PERMISSION_MODE` 在终端为空 = **设计如此**（DSH 层与 DSH 专属 env 由 DSH 自身 proot 绑定/注入，见 `TerminalCommandFactory` 注释 "dsh layer is bound by the DSH proot command"）。DSH 曾成功 boot（`node …/bin.js --profile web` → `127.0.0.1:3080`），证明 DSH 层与原生组件（node-pty/koffi/sharp）随 DSH 正常加载。

## 4. 备注 / 已知读取边界
- adb `input text` 会吞空格：用 `%s` 编码空间可解；`;`/`&`/`|`/引号/括号会被 shell 层干扰（非 APK 问题），已用 `-p`/`-v`/`--version` 无括号形式替代。
- `node`（无参）进入 REPL；要打印退出用 `node -v`/`-p`/`--version`。
- `/proc/<pid>/environ` 被 adb shell 权限阻挡（未直接读，改以 DSH 启动成功作注入佐证）。
