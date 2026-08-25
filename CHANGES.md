# CHANGES 变更记录

## 2026-08-19 — ✅ 修复「键盘弹出后 WebView 与键盘之间出现空白」（keyboard 修复好）

### 问题现象

DSH 对话页点输入框 → 键盘弹出 → WebView 页面被顶起，但**页面底部与键盘顶部之间
出现一段约一个「底部导航栏 + 手势条」高度的浅色空白**，里面什么都没有。同时手机
浏览器（vivo Chrome）访问同一页面完全正常。

### 根因（两层叠加）

1. **Scaffold 布局语义**：Material3 1.3.x 的 `ScaffoldDefaults.contentWindowInsets`
   不含 ime（已反编译 bytecode 确认，只含 systemBars/displayCutout），因此键盘弹出时
   Scaffold 不会自动为键盘腾出空间。
2. **在 content 区内压缩导致的系统性偏差**：之前所有版本（v1~v5）的键盘压缩逻辑都是
   「在 Scaffold content 区内部减键盘高度」——content 区底边本身比屏幕底高出
   「Tab 导航栏 + 手势条」一段，再减键盘高后，**WebView 底边比键盘顶高出
   「Tab 栏 + 手势条」整整一段**，这段正是用户看到的空白（用户亲自指出的关键洞察：
   空白是「原本插在导航按键那一横行下面的东西」被顶出来了）。

### 已尝试且被否决的方案（避免重蹈覆辙）

| 方案 | 结果 | 否决原因 |
|---|---|---|
| `Modifier.imePadding()`（v1/v3） | 键盘顶起但留空白 | imePadding 在 AndroidView 上行为不可控（Compose 修饰符层与原生 WebView 的断层），且上述「content 区内压缩」偏差仍在 |
| 去掉 imePadding 依赖 Scaffold（v2） | 键盘完全顶不起 | Scaffold 默认 insets 不含 ime（material3 1.3.1 bytecode 确认），无人处理键盘 |
| `WindowInsetsCompat.CONSUMED` 消费 insets（v3） | 空白依旧 | Chromium M139+ 内建 viewport resize 并非空白主因；主因是 content 区压缩偏差 |
| `BoxWithConstraints + WindowInsets.ime` 显式算高（v4） | 无效 | Compose 层 ime insets 读取链路不可靠 |
| 原生 FrameLayout 容器 + insets 监听（v5） | 无效 | OnGlobalLayoutListener 挂在容器上，键盘弹出时容器自身不触发布局，回调不触发 |

### 最终修复（v6，屏幕坐标法）

把 WebView 放进纯原生 FrameLayout 容器 `DshWebContainer`，键盘处理完全在原生 View 层：

```
WebView 高度 = 键盘顶(屏幕坐标) − WebView 顶(屏幕坐标)
```

- **键盘顶**：`decorView.getWindowVisibleDisplayFrame(rect).bottom` 实时测量
  （纯几何量，不依赖 insets 派发，兼容 vivo 等一切 ROM）
- **WebView 顶**：`webView.getLocationOnScreen(loc)[1]` 实时测量
- **触发**：`OnGlobalLayoutListener` 挂在 **decorView** 上（键盘弹/收必触发全局布局），
  并保留 ime insets 监听作为补充；同时消费 ime insets 防 Chromium 内建缩放二次压缩
- **零写死**：所有数值运行时实测，手机/平板/横竖屏/三键或手势导航均自适应

### 涉及文件

| 文件 | 改动 |
|------|------|
| `app/src/main/java/com/dshbox/app/ui/webview/DshWebViewScreen.kt` | 新增原生容器类 `DshWebContainer`（FrameLayout + WebView + 屏幕坐标法键盘处理）；WebView 初始化从 AndroidView factory 迁入容器；其余 UI（进度条/错误层/悬浮双按键/控制面板）保持不变 |

### 状态

✅ 键盘弹起输入框顶起、无空白、自适应（已实测通过）

---

## 2026-08-19 — 🧹 移除「首页网页列表 + 原生测试页」（一切测试正常，回归纯 DSH 页）

### 说明

网页列表功能已完成对照实验使命（用公网页面确诊了滚动问题归属），当前一切功能测试正常，
按用户要求**完整移除**，App 回归「首页 + 固定 DSH 页」的简洁形态。测试遗留的
「原生滚动测试」入口与 `NativeWebViewTestActivity` 一并移除。

### 改动清单（均为移除/回退）

| 文件 | 改动 |
|------|------|
| `app/src/main/java/com/dshbox/app/ui/home/WebSiteStore.kt` | **删除**（网页列表数据模型 + 持久化 + 全局状态） |
| `app/src/main/java/com/dshbox/app/ui/home/HomeScreen.kt` | 移除 `WebSiteListCard` 组件、添加/编辑/删除对话框、`onNavigateToDsh` 参数、网页列表 state/import、诊断按钮 |
| `app/src/main/java/com/dshbox/app/ui/MainScreen.kt` | 移除 `onNavigateToDsh` 接线与参数；`url` 改回 `Constants.DSH_BASE_URL`；删除 `WebSiteStore` import |
| `app/src/main/java/com/dshbox/app/ui/webview/DshWebViewScreen.kt` | 移除 `loadedUrl` 记忆与 `update` 热切换分支（WebView 固定加载 DSH 地址） |
| `app/src/main/java/com/dshbox/app/ui/webview/NativeWebViewTestActivity.kt` | **删除**（原生对照测试页，使命完成） |
| `app/src/main/java/com/dshbox/app/DshApp.kt` | 移除 `WebSiteStore.load(this)` 及 import |
| `app/src/main/AndroidManifest.xml` | 移除 `NativeWebViewTestActivity` 注册 |
| `app/src/main/res/values/strings.xml` | 移除 `home_web_*` 资源段 |

### 保留（不受影响）

- WebView 触摸滚动修复（`requestDisallowInterceptTouchEvent`，WEB 运转正常）
- 移动 UA 固定、零注入、原生双指缩放
- 悬浮双按键：调节器（面板）/ 刷新（转圈）
- 键盘自适应（`imePadding`）
- 沙盒 / 文件 / 终端 / 设置各 tab

---

## 2026-08-19 — ✅ 修复「Compose 内 WebView 触摸滚动失效」（WEB 运转正常）

### 最终结论（已实测通过）

App 内嵌 WebView 的触摸滚动恢复正常，与手机浏览器一致。

### 问题全貌（历史遗留，曾尝试多轮方案）

- 现象：App 内 WebView 加载任何页面（DSH 本机 / 公网移动页）都无法触摸滚动；点击正常。
- 对照：手机浏览器（vivo Chrome）打开同一地址滚动正常；原生 Activity（FrameLayout + WebView，绕过 Compose）滚动正常。
- 曾尝试且无效的方案（按时间顺序）：
  1. 桌面/移动 UA + viewport meta 注入（`initial-scale` 缩放）→ 布局错乱、滚动失效
  2. CSS zoom / transform 缩放 → 破坏 fixed 浮层（侧边栏文字挤成竖列）、点击跳动
  3. 触摸滚动桥接（touchmove → WheelEvent / 手动 scrollTop）→ 无效
  4. `content-visibility: visible` 注入（针对 Open WebUI 长列表）→ 无效
  5. `setSupportZoom` true/false 各种组合 → 无效
  6. `shouldInterceptRequest` 删除 viewport + `setInitialScale` → 布局错乱
  7. `graphicsLayer(scaleX/Y)` View 层缩放 → 仍无法滚动

### 确诊过程（对照实验法）

1. 新增「首页网页列表」功能后，切换到**公网页面**（腾讯元宝 / Qwen Work）同样滑不动
   → 排除 DSH/Open WebUI 页面层问题
2. 按最简配置重建 WebView（零注入、fillMaxSize、基础配置齐全、`setSupportZoom(true)`）
   → 仍滑不动 → 排除 WebView 配置层问题
3. **新增原生对照测试页** `NativeWebViewTestActivity`（纯 FrameLayout + WebView，
   完全绕过 Compose，加载内嵌本地长页，零网络依赖）
   → **能滑！** → 确诊：问题在 **Compose AndroidView 容器层**

### 根因

Compose 的 `AndroidComposeView`（Compose 容器的底层 ViewGroup）**拦截了 WebView 的触摸滚动事件**，导致 WebView 内核收不到滚动手势。`graphicsLayer` 修饰符（即使 scale=1）会为 AndroidView 创建独立渲染层，进一步加重该问题。

### 修复（两处）

1. **`setOnTouchListener + requestDisallowInterceptTouchEvent(true)`**
   ```kotlin
   // DshWebViewScreen.kt，WebView 初始化时
   setOnTouchListener { v, event ->
       v.parent?.requestDisallowInterceptTouchEvent(true) // 强制父容器不拦截
       false // 不消费事件，滚动仍由 WebView 自己处理
   }
   ```
   强制 Compose 父容器不拦截触摸，把滚动事件完整交还 WebView 内核。

2. **移除 AndroidView 的 `graphicsLayer(scaleX/scaleY)` 修饰符**
   （该层是滚动的额外嫌疑；移除后 WebView 恢复为最朴素子视图）

### 附带发现

- 原生 WebView 加载公网页面（https://yuanbao.tencent.com）**极慢/超时**：
  与滚动无关，疑似 DSHBox 沙箱网络层（SandboxService 代理/端口转发）对公网流量的影响，
  **待后续专项排查**。本地 DSH 页面（127.0.0.1:3080）不受影响。

### 当前功能状态（WEB 运转正常，2026-08-19 更新）

- ✅ WebView 触摸滚动正常（Compose 内，`requestDisallowInterceptTouchEvent` 修复）
- ✅ 首页为纯 DSH 卡片（网页列表已移除）
- ✅ 悬浮双按键：调节器（面板）/ 刷新（转圈）
- ✅ 原生双指缩放（WebView 内核）
- ✅ 键盘自适应（`imePadding`，输入框随键盘上移）
- ✅ 移动 UA 固定、零注入（不篡改 viewport / 不注入 JS/CSS）

---

## 2026-08-18 — 新增「首页网页列表 + WebView 热切换」

### 功能说明

首页新增「网页列表」卡片：
- 预置 4 个网址（DSH 本机 + 3 个公网页面），可点击切换 WebView 显示
- 支持用户自行添加 / 编辑 / 删除网址
- 选择网址后热切换 WebView 渲染（无需重启、无需重进页面）
- 当前选中的网址高亮显示，列表与当前地址持久化到 SharedPreferences

预置网址：

| 名称 | 地址 |
|------|------|
| DSH | http://127.0.0.1:3080 |
| Qwen Work | https://qwenwork.cn/app |
| WorkBuddy | https://www.workbuddy.ai/app |
| 腾讯元宝 | https://yuanbao.tencent.com/chat/ |

### 改动文件清单

| 文件 | 改动类型 | 说明 |
|------|----------|------|
| `app/src/main/java/com/dshbox/app/ui/home/WebSiteStore.kt` | **新增** | 网页列表数据模型 + SharedPreferences 持久化 + Compose 全局状态（`items` / `currentUrl`） |
| `app/src/main/java/com/dshbox/app/ui/home/HomeScreen.kt` | 修改 | 新增 `WebSiteListCard` 组件、添加/编辑对话框、删除确认对话框；`HomeScreen` 增加 `onNavigateToDsh` 参数 |
| `app/src/main/java/com/dshbox/app/ui/MainScreen.kt` | 修改 | 接线：`onNavigateToDsh = { selectedTab = 2 }`；`DshWebViewScreen` 的 `url` 改读 `WebSiteStore.currentUrl` |
| `app/src/main/java/com/dshbox/app/ui/webview/DshWebViewScreen.kt` | 修改 | 新增 `loadedUrl` 记忆 + `AndroidView.update` 内 URL 热切换（仅当 url 参数变化时 `loadUrl`，不影响页面内部路由/重定向） |
| `app/src/main/java/com/dshbox/app/DshApp.kt` | 修改 | `onCreate` 中调用 `WebSiteStore.load(this)` 恢复列表与当前地址 |
| `app/src/main/res/values/strings.xml` | 修改 | 新增 14 条网页列表文案（`home_web_*`） |

### 实现要点

- **数据存储**：`WebSiteStore` 为单例 + `mutableStateOf`，持久化用 `app_settings` SharedPreferences（JSON 数组），与 `AppThemeState` 同库，进程内全局共享，改后各页面自动重组。
- **热切换**：`DshWebViewScreen` 的 `update` 回调比较 `loadedUrl != url` 后调用 `loadUrl`；`loadedUrl` 保证页面内部 SPA 路由/重定向不会误触发重新加载。
- **登录态**：不涉及清理 Cookie / localStorage，切网址登录态按域名保留；覆盖安装（同签名）数据保留，卸载重装会清空。

### 回退方法（易插拔）

1. 删除 `WebSiteStore.kt`
2. `HomeScreen.kt`：删除 `WebSiteListCard` 调用与组件、两个对话框、`onNavigateToDsh` 参数及网页列表相关 state/import
3. `MainScreen.kt`：删除 `onNavigateToDsh` 接线与参数；`url` 改回 `Constants.DSH_BASE_URL`；删除 `WebSiteStore` import
4. `DshWebViewScreen.kt`：删除 `loadedUrl` 及 update 中热切换分支
5. `DshApp.kt`：删除 `WebSiteStore.load(this)` 及 import
6. `strings.xml`：删除 `home_web_*` 资源段

---

## 已知问题

- **原生 WebView 加载公网页面极慢/超时**（https://yuanbao.tencent.com 等）：疑似 DSHBox
  沙箱网络层（SandboxService 代理/端口转发）对公网流量的影响，与滚动无关；
  本地 DSH 页面（127.0.0.1:3080）不受影响。**待后续专项排查。**
- ~~WebView 页面触摸滑动失效~~ → 已解决（见上，2026-08-19）
