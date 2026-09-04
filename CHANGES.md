# CHANGES — v1.1.1 变更记录（相对 v1.1.0）

> 本文件只记录 v1.1.1（versionCode 4）相对 v1.1.0 的变更；完整历史见发布包根文档 `CHANGELOG.md`。

## 修复

- **在线更新点「安装」后整个 App 闪退（1.1.0 必现）**。安装进度视图 `InstallProgressView` 的根 Column 在更新页外层 `Column(verticalScroll)` 之内又挂了一个 `verticalScroll`，被以无限最大高度约束测量，首次组合即抛 `IllegalStateException`（真机 `FATAL EXCEPTION: main` 实证，vivo V2282A / Android 15 复现 5 次）。修复：去掉内层 `verticalScroll`——外层页面 Column 已可滚，日志区由固定 260dp 的 LazyColumn 自行滚动。
- **换层前旧 DSH 进程从未被主动停掉（死代码）**。`updateDsh` 的停机条件 `dshState == RUNNING` 恒为假——状态机从不产出 `RUNNING`（只有 STARTING/READY/ERROR/STOPPED），旧 DSH 的 PRoot 进程在整段安装期间一直存活，直到收尾 `restartDsh()` 才被终结。修复：改为状态机全部「在线」态（STARTING/RUNNING/READY）都先 `stopDshLocked()`。
- **「取消安装」无效**。原实现只对 proot 发 SIGTERM（`Process.destroy()`），而 PRoot 会把 SIGTERM 转发给 guest、自身不退出，安装进程树继续存活，`waitFor()` 永不返回，取消看起来没反应。修复：取消时按 `/proc` 枚举 proot 整棵 guest 进程树，SIGKILL 叶子优先逐个结束（与沙箱/DSH 停机同套路），`destroyForcibly()` 兜底；进程树清理移至后台线程，点击即时响应不再冻结界面。
- **npm 下载缓存占满红线区空间且清不掉**。npm 默认把缓存写在 guest `~/.npm`（= base rootfs 内），属「运行环境本体」红线、设置页清理按设计不触碰——实测多次失败安装残留 446MB（沙盒数据 1.4G→1.7G）。修复：安装用 guest 命令的 PRoot 增加 `--bind=<cacheDir>/npm-cache:/root/.npm`，下载缓存落入宿主 cacheDir（归「应用缓存」类别可一键清理，还能加速下次安装）；升级后首次启动幂等删除旧 `base/root/.npm` 残留释放空间。DSH 装入段与各红线不变。
- **换层后 DSH 起不来（EADDRINUSE）**。停机路径依赖 `dshProcess` 句柄，句柄丢失时旧 DSH 进程没被杀、占着 3080，新 DSH/健康重启全部撞端口（真机反复 EADDRINUSE）。修复：三处停机/重启路径（stopDsh / stopSandbox / 健康循环重启）统一加 cmdline 树杀兜底，并在健康超时/达上限置 ERROR 前清掉存活进程。
- **DSH 0.1.2-rc.1 兼容**（在线更新后）：① 健康检查原只认 HTTP 200-299，新版 webserver 对无 token 请求返回 401 被误判"未就绪"——放宽为任何 HTTP 响应即存活；② 网页启用会话认证（进程级 launchToken + 签名 cookie），WebView 从 DSH 启动 URL 解析 launchToken（日志打码前、仅内存）并带 token 完成首次 cookie 交换，之后凭持久 cookie 访问（跨重启有效）。


## 增强（T1-T3，2026-09-04 晚）

- **首次安装下载阶段进度提示**：显示「已用时」与预计/后台继续说明（非遮挡，安装后台照常，可离开页面用其他功能）。
- **装配 DSH 移动端适配包支持一键移除**：弹窗显示当前装配状态，「装配」/「移除」按钮随状态切换，支持安装/移除/再安装循环。
- **guest 命令等待超时兜底（T4）**：proot/guest 异常卡死不再无界等待（装配状态检查曾被卡住、界面永转「正在检查…」），10 分钟超时自动终止。
- **日志保留策略 A + 诊断页升级**：进程日志单文件 2MB、超限滚动保留最近两代（.prev）；诊断页展示 DSH / 沙箱 / 访客命令三条目各 150 行（可滚动，DSH 启动日志为重点），导出合并全部（含 .prev）。

## 测试

v1.1.1 全量 JVM 单测 + `assembleRelease` 构建通过（详见 `MODIFICATION_LOG.md` 文末验证记录）。
