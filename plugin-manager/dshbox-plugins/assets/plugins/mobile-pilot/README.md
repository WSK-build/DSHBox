# @local/mobile-pilot

把宿主应用绑进沙盒的**手机助手能力面**（`/opt/pilot`）封装为两样东西：

1. **命令行入口** `mobile-pilot` —— 解析挂载点并把参数原样转发给 `<挂载点>/bin/pilot`；
2. **一组 DSH 原生工具** `phone_*` —— 让 agent 直接调用，不必自行拼 bash 命令。

## 本包不重实现信箱协议

请求信封、`run/{inbox,processing,outbox}` 的流转、回包字段、错误码与退出码由宿主应用与它的
`bin/pilot` **单点持有**。这里再实现一遍，就会有两份各自漂移的规格：宿主改了字段或加了错误码，
本包不会报错，只会安静地把回包解析错。因此本包只做三件事 —— **找对挂载点、按 argv 原样递过去、
把回包按字段名解析出来**；渲染文案里出现的字段名一律取自回包本身。

## 三层职责

| 层 | 文件 | 职责 | 明确不做 |
|---|---|---|---|
| 挂载层 | `plugin/lib/mount.js` | 解析工具包落在哪里：`MOBILE_PILOT_HOME` → 安装期写入的 `mount.json` → `/opt/pilot` | 不发送请求，不解释回包 |
| 命令层 | `plugin/bin/mobile-pilot` | 一个固定入口：`doctor` / `ui` 由本包实现，其余子命令原样转发给 `<挂载点>/bin/pilot` | 不拼命令行字符串，不改写退出码 |
| 工具层 | `plugin/lib/pilot.js` + `plugin/lib/tools.js` + `plugin/lib/index.js` | 投递与串行、超时夹紧、回包归一；14 个工具的**定义**；把工具注册进 DSH | 不定义新能力，不把危险通路包装成一键工具 |

```
宿主 App（执行器 · 权限闸门 · 确认框）
      │ 绑定目录
沙盒 /opt/pilot：bin/pilot · capabilities.json · USAGE.md · run/
      │ 文件投递 + 轮询（唯一跨界通道，无网络、无共享内存）
@local/mobile-pilot：mount.js 解析 → pilot.js 投递/归一 → tools.js 定义 → index.js 注册
      │ ctx.tools.register(defineTool(spec))
DSH 工具运行时 → 模型工具列表
```

## 工具清单（14 个）

| 工具 | 参数 | 对应能力 | 说明 |
|---|---|---|---|
| `phone_status` | `probe?` | 读清单 | 挂载点、能力条数与可用条数、Shizuku 后端；`probe` 时实发一次 `pkg.query` |
| `phone_call` | `capability*`, `args?`, `timeoutMs?`, `out?` | 任意 | 通用入口；未覆盖的能力与危险通路都在这里显式点名 |
| `phone_observe` | — | `ui.snapshot` | 控件树压缩输出，完整 JSON 落盘并给出路径 |
| `phone_click` | `nodeId? \| selector?`, `long?` | `ui.click` / `ui.longClick` | 点击 / 长按 |
| `phone_setValue` | `nodeId? \| selector?`, `text*` | `ui.setValue` | 写入输入控件 |
| `phone_scroll` | `nodeId? \| selector?`, `direction*`, `times?`, `until?` | `ui.scroll` | 滚动；`dispatched` 与 `advanced` 分列 |
| `phone_node` | `nodeId? \| selector?` | `ui.node` | 单控件状态：`actions` / `range` / 平铺坐标 |
| `phone_waitFor` | `nodeId? \| selector?`, `text?`, `checked?`, `absent?`, `timeoutMs?` | `ui.waitFor` | 等待条件成立（宿主上限 15000ms） |
| `phone_key` | `key*` | `ui.key` | back/home/recents；enter 仅虚拟屏 |
| `phone_launch` | `package*` | `app.launch` | 以 `verified` / `landedOn` 判定落地 |
| `phone_capture` | `out?` | `screen.capture` | 截图并返回本地路径（配合 `read_image`） |
| `phone_intent` | `template*`, `hour?`, `minute?`, `length?`, `message?`, `page?`, `package?`, `number?`, `url?` | `sys.intent` | 表内八条：设闹钟 / 计时器、开闹钟页 / 计时器页、开系统设置页、应用详情、拨号盘、网页；无删除类入口 |
| `phone_surface` | `action*`, `width?`, `height?`, `dpi?` | `surface.virtual` | 后台虚拟屏 create / query / release |
| `phone_ask` | `question*`, `options*`, `timeoutMs?` | `ask` 子命令 | 当面提问（2..3 选项）；答复三支皆为成功：option / reject_all / reask |

**没有专用工具的部分**：`sys.shell`（用户逐条开关的危险能力）与坐标注入 `ui.tap` / `ui.swipe` /
`ui.text` 只能经 `phone_call` 显式点名。给它们配一键工具，等于把闸门从宿主侧挪到本包侧，
而本包没有任何判定依据。工具数量固定为 14 个；其中 `phone_ask` 不对应能力清单里的条目 ——
它走入口的 `ask` 子命令（见「提问通路」一节），凡是被列进能力清单的能力仍只能经 `phone_call`。

## 屏幕的叫法

只使用两个词：**虚拟屏**（本包在后台创建的显示面，`surface.virtual` 返回其 displayId）与
**主屏 / 前台屏**（用户正在看的屏幕）。不用其他自造称呼，因为"哪块屏"直接决定坐标、
控件树与确认框的可用性。写操作在主屏上的宿主界面在前面时会被暂停，读操作不受此限。

## 确认框的三个答复入口

需要用户确认的能力会挂起一张确认框。三个答复入口按可用性**依次只呈现一处**，不并排弹两个：
**助手页内的那张卡**（该页在前台时）→ **悬浮卡** → **通知栏的「允许/拒绝」**；在哪一处答都算数、
也只算一次。宿主界面在最前面时悬浮卡不出现（系统设置一类界面也会盖掉它），后台虚拟屏运行期间也没有
悬浮卡 —— 此时问题落在助手页内那张卡上（该页正在被看时）或通知栏（不在前面时）。等答复的请求停在
宿主的审批队列里（v2 通路下可经 `mobile-pilot status <requestId>` 查询），跟进手段是查询状态与在
可用的答复入口上作答，**重发同一写操作不是收集答复的手段**；等待到点先 status / cancel 跟进
（见「v2 请求通路」一节）。三处载体都不可用时才回 `E_GATE_NO_FOREGROUND`。

## 回包字段读法

字段名以宿主下发的说明与真实回包为准，本包不猜、不补：

- `ui.node` 的节点对象把矩形**平铺**为 `left/top/right/bottom`（已裁到屏内），整颗裁空时只给
  `offscreen:true` —— **没有 `bounds` 这个键**；判"能不能点"看该节点自报的 `actions` 列表，
  `clickable` 只是框架原始位（存在 `clickable:true` 而 `actions` 无 `click` 的控件，点下去回
  `E_NODE_NOT_ACTIONABLE`）。
- `ui.scroll` 分列 `dispatched`（控件接受派发的次数）与 `advanced`（观察到值变化的次数）；
  控件不自报量程时宿主**不写** `advanced`（"量不出"不等于"没动"）。`movementVerified` 只在量得出
  位移且两者一致时为真。给了 `until` 才回 `reached` / `target`，`stepLimit` 与 `requested` 各说各的。
  `until` 的前提是该控件自报量程（`ui.node` 的 `range`）：部分厂商时钟滚轮不报量程，会回
  `E_NODE_NOT_ACTIONABLE` 并指回 `ui.node {range}`。

## 超时

| 能力 | 本层档位 | 实际下发 |
|---|---|---|
| `ui.*` | 20000ms | 20000ms |
| `screen.capture` / `screen.observe` | 90000ms | 90000ms |
| `app.launch/install/stop`、`clip.*` | 60000ms | 60000ms |
| `sys.*`、其他 | 30000ms | 30000ms |
| `screen.record` | 180000ms | **120000ms（夹紧）** |

入口脚本只接受 `--timeout` 在 **1000..120000ms** 之间，超出部分会被它静默夹紧。本层先夹紧，
并把夹紧与"谁掐断了这次调用"写进摘要：`E_TRANSPORT_TIMEOUT` 是入口在自己的预算内没等到回包，
`E_WRAPPER_TIMEOUT` 才是包装层先失去了耐心。`ui.waitFor` 的宿主上限（15000ms）比 `ui.*` 档位短，
无需本层干预；宿主还会把等待压进本次调用在通道里剩下的预算，并用 `requestedMs` / `cappedToBudget` 报出来。
`screen.record` 的 180s 档位与宿主不冲突也到不了：它把 `seconds` 夹在 1..30，30 秒的内容加编码在 120s 内交得完，
夹紧只是把"入口会自己夹"这件事挪到本层来说清楚。

## v2 请求通路（status / cancel / UNKNOWN）

宿主的 v2 控制通道在位时，`call` 先把请求投进控制信箱并拿到 `requestId`；等待到点而结果未定
时，调用以 `state=UNKNOWN` 收场（退出码 3）—— 不声称成功，也不声称失败。跟进手段是查询，
不是重发：

- `mobile-pilot status <requestId>` 查一个已投递请求的快照；`mobile-pilot cancel <requestId>`
  请求宿主撤销。两条都原样转发给入口，退出码沿用入口那张表（`4` 还表示查无此请求；
  `cancel` 的 `5` 表示只登记了撤销意愿，宿主尚未证实撤销）。
- `state=CANCELLED`：宿主证实执行前已撤销 —— 未执行，不需要再用 status/cancel 跟进。
- `state=UNKNOWN`：无法证实是否已执行。对非幂等动作（写操作、创建类）**不得按原参数重投**：
  先查设备侧可观察结果或交用户判断。工具输出带 `requestId` / `state` 字段（回包有的话；
  两个键在 14 个工具的输出 schema 里固定存在，回包未携带时为空串），
  处置建议会点名这两条跟进命令。

控制通道不在位时走 v1 信箱通路，回包没有 `state` 字段，语义以回包自身字段为准。换通路的
回退只发生在"确定还没投进信箱"的失败上；投递之后失联一律按 UNKNOWN 报告，不换通道重发 ——
那对非幂等动作就是一次无人授权的重复执行。`mobile-pilot doctor --probe` 可向入口的
v2 控制通道发一次心跳，结果附在 `probe` 字段里，不改动 doctor 的静态结论。

## 提问通路（phone_ask）

`phone_ask` 与 CLI 的 `mobile-pilot ask` 是同一条通路的两个入口：宿主把问题摆成一张卡
（与审批卡同款外观，**同屏只允许一张**），用户的答复当场带回。它**不是**能力清单里的条目，
不经 `phone_call`，也不是可用 `status` / `cancel` 跟进的那类请求 —— 它没有副作用。

| 项 | 取值 |
|---|---|
| 载荷 | `{question, options, timeoutMs}`：问题 1..200 字（去首尾空白后非空），选项 2..3 个、每个 1..60 字 |
| 窗口 | 夹在 5000..120000ms（默认 60000）；本层等待预算 = 窗口 + 12000ms |
| 下发 | **不带 `--timeout`**：入口对该子命令明确拒绝它，窗口只由载荷里的 `timeoutMs` 决定，两个来源会互相打架 |
| 用法错 | 在投件之前拒绝（`E_WRAPPER_USAGE`，退出码 1），不产生任何请求文件 |

答复三支**皆为成功**（退出码 0），工具输出按 `choice` 分流：

- `option`：用户选了某一项 —— `choiceIndex` / `choiceLabel` 给出下标与原文；
- `reject_all`：这一组选项都不合适 —— 重新组织选项后再问，**不要原样重问**；
- `reask`：问题本身要换个法子组织 —— 改问法后再问，**不要原样重发**。

后两支是**合法答案**而不是失败：把它们读成「没答」就丢掉了用户真实表达的意见，
而否的是选项还是问题，两者的下一步不同。

没有答复时按宿主给的原因分流：`E_ASK_TIMEOUT`（等满窗口无人作答，宿主还区分卡片是否丢过焦点）、
`E_ASK_BUSY`（屏上已有一张卡，这一问根本没摆上去）、`E_ASK_NO_SURFACE`（此刻没有可用的呈现面：
无悬浮窗授权或后台模式隐藏浮窗，**需要用户动手**）、`E_ASK_NO_REPLY`（通道在位但没等到回包）。
提问不改动设备状态，重问是安全的；但连续超时说明这条路当前问不到人，该把问题带回用户看得见的对话里。

## 落盘

`phone_observe` 的快照 JSON 与 `phone_capture` 的截图写入同一目录：
`config.outDir` → `MOBILE_PILOT_OUT_DIR` → 当前工作目录 → 系统临时目录。
快照按修改时间**只保留最近 20 份**，清理只针对本包写出的 `pilot-snapshot-*.json`，不递归、不碰
目录里的其他文件。目录需要新建时只建一层，父目录不存在就换下一个候选 —— 落盘目录取不到是降级，
凭空造一棵陌生路径的树不是。每次输出都在 `path` / 摘要里写明完整路径；`data` 预览被截断时会写出
还剩多少字符与字节没显示，据此决定要不要去读文件。

## 退出码

沿用 `bin/pilot` 那张表，本层不自造：

| 码 | 含义 | 处置 |
|---|---|---|
| 0 | 成功 | — |
| 1 | 用法 / 参数错（能力名不存在、未知键、越界值、选择器歧义） | 改调用本身，重试无效 |
| 3 | 超时：入口预算内没等到回包，或 `E_WAIT_TIMEOUT`（界面未进入该状态） | 看 `code` 分辨是通道还是界面 |
| 6 | **本层内部错**：找不到挂载点、起不动入口、拿不到可解析的回包 | 报告，不要循环重试 |

`2`（被用户或规则拒绝）、`4`（需用户在手机上动作）、`5`（瞬时可重试）同样原样透出。
本层绝不把内部错报成 `1`：`1` 会让助手去改自己的命令行，而真因往往是宿主页没开、挂载点没铺好。

## 装配

```bash
bash install.sh                                  # 默认 profile 与 /opt/pilot
bash install.sh /path/.dsh/profiles/web --pilot-home /opt/pilot
```

装配按「阶段目录 → 校验 → 备份 → 替换 → 失败回滚」推进：

1. 先完成全部前置校验（python3、插件源文件、profile 结构、命令入口路径），任何一项不过都不写东西；
   起步时还先自愈上次异常退出留下的残留：活跃包缺失而备份位还在就先复位备份，
   事务目录被上次残留占用时换名重试一次，仍占用就报错停下；
2. 把 `plugin/` 复制进阶段目录 `<profile>/node_modules/@local/.mobile-pilot-stage.$$`，JS 语法检查与
   文件清单核对（7 个必需文件齐全且非空）都在阶段目录上做 —— 验不过就不动活跃包；
3. 旧包整体挪进备份位，阶段目录才改名上位，发布结果再复检一遍清单；
4. 随后写入 `mount.json`（记录挂载点与当时的入口/清单存在性）、把 `@local/mobile-pilot` 追加进
   profile 的 `dsh.profile.bundles`、建立 `<profile>/node_modules/.bin/mobile-pilot` 命令入口
   （一次 `ln` 尝试：能建就是符号链接，个别环境会落成一份复制，`ln` 失败时安装整体退出、
   不做复制降级），最后写所有权清单
   `.mobile-pilot-install.json`（包名、命令入口相对路径、7 个文件清单、安装时刻）；
5. 第 3 步之后任何一步失败：半包撤掉，脚本报错退出。升级场景把备份放回原位 —— **旧版保持可用**，
   bundle 条目与命令链接照旧指向它；首装（没有旧版）场景则把 bundle 条目与命令链接一并摘除，
   profile 回到安装前的状态，不给 loader 留指向不存在包的条目。

写 profile 配置一律"临时文件 + 原子改名"。卸载：`bash uninstall.sh [profile]`（先从 bundles 摘除，
再按所有权清单删包目录与命令入口，清单里的命令入口路径先归一并确认仍落在 profile 之内才认；
没有清单时只处理两个固定规范路径，不凭文件内容猜归属；不触碰 `/opt/pilot`）。

**替换不是原子操作**：旧包让位到新包上位之间有一个包不完整的窗口，重装或升级前先停 dsh，
装完再启动。

装配的三个时点，互不等同：

| 时点 | 含义 | 由谁证明 |
|---|---|---|
| `ASSEMBLED` | install.sh 退出 0：包、mount.json、bundle、命令入口、所有权清单都在 | 安装脚本的退出码 |
| `LOADED` | dsh 重启后 loader 装载 bundle，`apply` 被调用 | dsh 重启这件事本身 |
| `TOOLS_VISIBLE` | dsh 的工具列表出现 `phone_*` | dsh 工具列表 |

`ASSEMBLED` 不等于当次会话已有工具。全新 profile 的首启补装发生在 dsh 首次启动之后，要等
dsh 再次启动才生效 —— 那一次会话里 `phone_*` 不存在不是故障。

## 从 profile 到模型工具列表

| 步 | 动作 | 依据 |
|---|---|---|
| 1 | profile 的 `package.json` 在 `dsh.profile.bundles` 里列出本包 | `install.sh` 写入 `@local/mobile-pilot` |
| 2 | loader 读包声明的 `dsh.bundle.patch` → `plugin/cordis.patch.yml` | `package.json` |
| 3 | 该 patch 向配置树 insert 一个 cordis 条目（id 稳定，name 即包名） | `cordis.patch.yml` |
| 4 | loader 以 name 解析包 → `main: lib/index.js` | `package.json` |
| 5 | 取模块导出的 `name / inject / apply`，等 `inject` 声明的服务就绪后调 `apply(ctx, config)` | `inject: ['tools']` |
| 6 | `apply` 内逐条 `ctx.tools.register(defineTool(spec))` | 14 个工具 |

条目本身不带配置：挂载点自动解析，可选的落盘目录由 `config.outDir` 传入（`lib/index.js` 读它）。

**第 3～6 步的宿主半边在本仓库内不可读**（`/opt/dshapp/runtime` 下没有 `@cordisjs`），
所以这张表的 1、2 与 6 是由包内代码与离线装卸验过的，3～5 描述的是参照包
`@local/dsh-mobile-adapt` 已跑通的那条装配路径 —— 上真机 profile 之前，别把这张表当已证事实。

### 三种入口，用途不同、互不替代

| 入口 | 使用者 | 声明位置 |
|---|---|---|
| 原生工具 `phone_*` | 模型 / agent | `lib/index.js` 里的 `ctx.tools.register` |
| CLI `mobile-pilot` | 沙盒 shell / 脚本 | `package.json` 的 `bin`（`install.sh` 链到 `node_modules/.bin`） |
| 浏览器客户端半边 | Web UI | `package.json` 的 `dsh.client` —— **本包不声明**，因此不注入任何界面代码 |

### 一次调用的路径

```
模型调用
 → 运行时按 parameters 校验实参
   → execute(args)
     → PilotChannel.call()（入队，同一时刻仅一个在途）
       → spawn node <挂载点>/bin/pilot call <能力> --json '…' --timeout <分档>
         → CLI 写 run/inbox/<id>.json（先写 .part 再改名）
           → 宿主消费 → 写 run/outbox/<id>.json
         → CLI 读回并输出 JSON（退出码取自响应中的 error.exitCode）
     → 归一：ok / code / retryable / exitCode / surface / degraded / data / artifacts
   → 组装 { ok, code, text, path }
 → 运行时按 output.schema 校验 → render → 返回模型
```

本包既不解析宿主协议，也不生成屏幕动作：协议与动作都由宿主单点持有（见开头「本包不重实现信箱协议」）。

## 配置

| 名称 | 作用 |
|---|---|
| `MOBILE_PILOT_HOME` | 指定挂载点，优先于 `mount.json` 与默认路径 |
| `<profile>/node_modules/@local/mobile-pilot/mount.json` | 安装期缓存的挂载点 |
| `MOBILE_PILOT_OUT_DIR` | 截图与快照的默认落盘目录 |
| 插件 `config.outDir` | 同上，由 loader 配置给出，优先级最高 |

## 优雅降级

`@deepseek-ai/dsh-tools` 拿不到时（未装、非 CJS 可 require、loader 未给 `tools` 服务），
`apply()` 只写一行「未找到 dsh-tools，工具未注册」并返回，不抛异常：
CLI 那一层不依赖它，装完立刻可用。那一行会带上**卡在哪一步**：`resolve`（这个包根本不在
本包的可解析路径上）/ `shape`（解析到了，导出的 `defineTool` 不是函数）/ `require`
（纯 ESM 且运行时不支持 require(esm)）。require 走不通但解析到了绝对路径时，动态 import
会按该路径的 file URL 重试；所有解析路径都落空才退回裸包名 import。

注册是逐项进行的，**没有批次回滚**：第 N 项注册抛错时停下，并报
`PARTIAL_REGISTRATION: registered=[...] failed=<工具名> (<错误>)`，点名已注册的清单与
失败的那一项，明示重启 dsh 后重试装配或先按部分工具继续使用 —— 不用一句模糊的
"注册失败"把已注册清单吞掉。构造期（14 个工具全量构造）抛错时一个都不会注册。

## 生效条件

工具在服务端装载阶段注册：装配或修改插件后需重载/重启 DSH，`phone_*` 才会出现在调用方的
工具列表中。CLI 不受此限制。`ASSEMBLED`（装配完成）不等于 `LOADED`（本次 dsh 已加载），
更不等于 `TOOLS_VISIBLE`（工具列表已出现，见「装配」一节的三个时点）；全新 profile 的
首启补装发生在 dsh 首次启动之后，要等 dsh 再次启动才生效。

工具一条都不出现时按四段顺序对账，每一段都有各自的读数（不要跳到第四段猜）：

1. **随包资产铺到暂存区**：`user-data/.dsh/dshbox/dshbox-plugins/mobile-pilot/` 下有没有
   `plugin/lib/{index,mount,pilot,tools}.js`。缺是宿主侧复制没成。
2. **装进 profile**：`install.sh` 跑没跑成、profile 的 `package.json` 里
   `dsh.profile.bundles` 有没有 `@local/mobile-pilot`。这一步要 python3，缺 python3 会**中止且不写半截**。
3. **工具面接没接上**：沙盒里 `mobile-pilot doctor` 看 `toolsApi` —— `ok:false` + `stage:"resolve"`
   就是 `@deepseek-ai/dsh-tools` 不在本包可解析路径上（这条与挂载点无关，屏没挂载也照样报）。
4. **重启之后**：DSH 装载期才注册，`inject:['tools']` 由 loader 满足；没重启就看不到。


## 测试

```bash
node --test test/mobile-pilot.test.mjs test/pilot-tools.test.mjs test/device-readings.test.mjs
node --test test/install-contract.test.mjs
```

- `test/mobile-pilot.test.mjs`：挂载解析与 CLI 转发。"无挂载点"类用例以"本机未挂载
  `/opt/pilot`"为前提，该前提不成立时显式跳过（不静默通过）。
- `test/pilot-tools.test.mjs`：通道层（argv 透传、码与退出码透出、无回包、超时归属与夹紧、串行）、
  工具层渲染（用假回包断言真实坐标与 `advanced` / `reached` 出现在输出里）、产物交付失败不假成功、
  落盘保留份数、14 个工具的形状与命名边界、提问通路的载荷校验与三支答复、屏幕叫法统一、注释不引用内部文档、
  缺 `defineTool` 时的降级、注册途中部分失败的点名报告，以及动态 import 按 file URL 重试已解析路径。
- `test/device-readings.test.mjs`：设备回包读数对应的回归（数值参数归一、选择框建议、降级单独成键）。
`node --test` 的摘要把 `skipped` 与 `pass` 分开计数：**跳过不是通过**。v2 端到端那批在真实通道
在位的机器上整批跳过（mock 不能占用真通道），此时 `pilot-tools.test.mjs` 末尾的现场自检会把它
报成**失败**并写清在设备上取证的做法，同时打印「实跑 N 条 / 跳过 M 条」——
一次全是跳过的运行不能被读成「端到端验过了」。`mobile-pilot.test.mjs` 末尾同样有现场自检，
报出「无挂载点」那批本机是整批实跑还是整批跳过。

- `test/install-contract.test.mjs`：用 bash 子进程对临时 fixture profile 跑真实的装卸脚本：
  全新安装的包结构与所有权清单、升级替换、损坏安装源被挡下且旧包完好、发布后注册失败的
  回滚、首装发布后失败的连带摘除（bundle 条目与命令链接一并撤、package.json 可解析）、
  按所有权清单卸载与无清单时的规范路径卸载、清单里的命令入口越出 profile 时按不可信处理、
  邻居包不受伤。前提是本机 bash 里有 python3 与 node，缺一则显式跳过。

## 结构

| 路径 | 职责 |
|---|---|
| `plugin/lib/mount.js` | 挂载点解析（有序回退，`env` / `mountFile` / `platform` 可注入） |
| `plugin/lib/pilot.js` | 通道客户端：argv 投递、串行、超时分档与夹紧、回包归一、处置建议 |
| `plugin/lib/tools.js` | 14 个工具的定义：参数 schema、输出 schema、回包渲染、落盘与保留份数 |
| `plugin/lib/index.js` | Loader 入口：`name` / `inject:['tools']` / `apply`；并暴露 `uiCapabilities()`、`PilotChannel` |
| `plugin/bin/mobile-pilot` | CLI：`doctor`（`--probe` 附 v2 心跳）/ `ui` 本地实现，其余原样转发 |
| `plugin/cordis.patch.yml` | 把本包注册为 cordis bundle 条目 |
| `install.sh` / `uninstall.sh` | 装配（阶段目录→校验→备份→替换→失败回滚 + 所有权清单）/ 卸载（按所有权清单删除） |
| `test/` | `node --test` 测试 |

## 边界

- 不打包、不复制、不修改 `/opt/pilot` 里的任何东西；那棵树归宿主应用所有，由它自己重铺与回收。
- 挂载点此刻不存在也能装：`install.sh` 会在 `mount.json` 里如实记下 `entryPresent:false`，
  `doctor` 之后报同一个事实。
- `doctor` 只回答"这组文件在不在、成不成形状"，不证明通道活着 —— 通道是否在读信箱，
  要看一次真实调用有没有回包。入口脚本没有可用执行位时，包装层改用当前 node 解释器跑它。

## 读数容易误判的两处

- **`doctor` 的 `toolsApi.ok=false` 不等于插件坏了。** 本包随 profile 复制进沙盒（工程根实测是
  `/root/projects`，不是 `/workspace`），而 `@deepseek-ai/dsh-tools` 装在 dsh 自己的 runtime 树里；
  探测会依次从本包目录、当前工作目录、入口脚本目录与 node 可执行文件目录解析，全部落空时读数仍是
  `false`。工具在不在，以 dsh 的工具列表里有没有 `phone_*` 这 14 条为准。只装包而未重启 dsh，
  得到的也是同一个读数——重启之后才会注册上。
- **宿主铺进沙盒的那一份 `bin/mobile-pilot` 可能没有执行位。** 直接执行会得到退出码 126，
  用 `bash` 执行会得到 `use strict: command not found`（它是 Node 脚本）。这两种都改用
  `node <路径>/bin/mobile-pilot doctor` 执行；`install.sh` 复制的那一份会补上执行位。
