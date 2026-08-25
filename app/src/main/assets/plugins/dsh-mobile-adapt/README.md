# dsh-mobile-adapt — DeepSeek Harness 移动端适配插件

为 **DeepSeek Harness (dsh)** WebUI 提供移动端体验适配的 cordis 插件。**纯插件层实现,不改动官方源码**(浏览器端注入 CSS + DOM,并调用官方 `layout` 服务)。

> 适用版本:dsh 0.1.x(已在 0.1.0-rc.8 与 0.1.1-rc.2 验证)。dsh 版本升级通常不破坏本插件(选择器基于稳定的哈希子串,见「技术说明」)。

---

## 一、插件功能

| 功能 | 说明 |
|------|------|
| **全屏抽屉侧栏** | 手机上侧栏完全隐藏,点左上角 ☰ 汉堡以全屏抽屉弹出;点遮罩/再点汉堡关闭 |
| **对话区满宽** | 隐藏 56px 图标轨与 details 栏,对话区自适应填满屏宽,不横向滑动 |
| **设置两步式** | 点 ⚙️ 设置 → 导航页全屏;点分类 → 内容页整屏覆盖,左下角「← 返回」回导航页/主对话 |
| **移动/官方热切换开关** | 设置面板底部有「移动端适配 [开/关]」开关:关 = 恢复 dsh 官方原版布局,开 = 移动适配。选择存 localStorage,刷新保持 |
| **键盘适配** | 输入框聚焦唤出键盘时,自动将输入框上移到键盘上方(visualViewport 监听) |
| **模型选择贴发送键** | 模型/思考强度控件紧贴发送键左侧,下拉菜单超高时内部滚动不弹出屏外 |
| **品牌标识** | 侧栏顶部显示「DSH mobile」(移动模式)/ 官方品牌(官方模式) |

---

## 二、包结构

```
dsh-mobile-adapt/
├── install.sh          # 一键装配脚本
├── uninstall.sh        # 卸载脚本
├── README.md           # 本文档
└── plugin/             # 插件本体(cordis 插件包)
    ├── package.json    # dsh.client / dsh.bundle 声明
    ├── cordis.patch.yml# 注册 host half 为 Loader entry
    └── lib/
        ├── index.js    # host half(无操作入口)
        └── client.js   # 浏览器 half(全部 CSS + 逻辑,~27KB)
```

---

## 三、安装(任选其一)

### 方式 A:一键脚本(推荐)

```bash
# 在解压后的 dsh-mobile-adapt 目录内:
bash install.sh                          # 自动定位 /workspace/.dsh/profiles/web
# 或指定 profile:
bash install.sh /path/to/.dsh/profiles/web
```

脚本会:① 复制 `plugin/` 到 `node_modules/@local/dsh-mobile-adapt`;② 把 `@local/dsh-mobile-adapt` 追加进 profile `package.json` 的 `dsh.profile.bundles`。

### 方式 B:手动

```bash
# 1) 放插件
mkdir -p <profile>/node_modules/@local
cp -r plugin <profile>/node_modules/@local/dsh-mobile-adapt

# 2) 注册 bundle
# 编辑 <profile>/package.json,把 dsh.profile.bundles 改为:
#   "bundles": ["@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app", "@local/dsh-mobile-adapt"]
```

### 3) 重启 dsh 并验收

```bash
# 重启 dsh(以你惯用的方式,如 pnpm dsh web / start 脚本)
# 手机浏览器务必用【无痕模式】打开(避开缓存),验收:
#   - 主页左上角 ☰ 汉堡(点击弹出全屏抽屉侧栏)
#   - 设置面板底部「移动端适配 [开/关]」开关
#   - 对话区满宽、不横向滑动;键盘唤出时输入框自动上移
```

---

## 四、使用说明

- **热切换开关**:进设置 → 底部「移动端适配」;开 = 移动适配,关 = dsh 官方原版布局。状态保存在浏览器 `localStorage['dsh-mobile-enabled']`,默认开。
- **注意**:切换后浏览器进程级缓存可能让你看到旧样式,**务必无痕模式或关页重开**。
- dsh 更新(升级内核)后插件无需重新安装;若官方 DOM 结构大改,个别选择器可能需要微调(见下)。

---

## 五、卸载

```bash
bash uninstall.sh                       # 或 bash uninstall.sh /path/to/.dsh/profiles/web
# 重启 dsh 生效
```

---

## 六、技术说明(重要)

1. **纯插件实现,零源码改动**:所有效果来自浏览器端注入的 `<style>` + DOM + 事件监听,调用官方真实 `ctx.layout.toggleSidebar()` 服务。官方源码零修改。
2. **选择器策略**:dsh 用 CSS Modules,运行时类名带哈希前缀(如 `FDk7aW_centerCol`)。本插件全部使用 **子串属性选择器** `[class*="centerCol"]` 与稳定结构选择器(`[role="dialog"]:has(> nav)`、`[data-composer-card]`),因此 dsh 构建后哈希前缀变化不影响,但**官方若改变 DOM 结构/类名语义**,需对应微调。
3. **依赖**:插件 client half 注入 `@deepseek-ai/dsh-client-runtime` 与 `@deepseek-ai/dsh-client-ui-layout`(profile 标准 bundle `dsh-base`/`dsh-web-app` 的依赖链已包含)。
4. **与本部署其它补丁的关系**:Basic Auth 代理场景下,`packages/client/connection` 还有一处独立的 `AUTH-PROXY-PATCH`(WebSocket 信任放行),那属于部署层补丁,**不在本插件包内**,需在部署源码时单独维护。
5. **已知限制**:设置内容页个别分类若存在固定宽度组件,本插件通过 `max-width:100%; min-width:0` 让其收缩;若某控件仍异常,建议反馈具体分类。

---

## 七、版本记录

| 版本 | 说明 |
|------|------|
| 0.1.0 | 首版发布:抽屉/两步式设置/热切换开关/键盘适配/模型贴发送/品牌标识 |
