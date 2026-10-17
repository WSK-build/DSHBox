# 许可文本与来源

本目录集中保存随 APK 交付的许可原文。原文保持各自授权，不以应用整体 GPLv3 替换第三方许可。

| 文件 | 来源 / 适用范围 |
|---|---|
| GPL-3.0.txt | 仓库根 LICENSE；DSHBox 及三个自有插件的原有 GPLv3 授权 |
| Apache-2.0.txt | pilot/interlock-relay-core/LICENSE；AndroidX、Kotlin 等 Apache-2.0 组件的许可全文，组件自己的 NOTICE 另行保留 |
| Tabler-LICENSE.txt | common/LICENSE-TABLER.txt；导航与 Browser 图标，含原版权声明 |
| LGPL-2.1.txt | https://raw.githubusercontent.com/spdx/license-list-data/main/text/LGPL-2.1-only.txt ；LGPL 2.1 原文副本，不表示将 LGPL-2.1-or-later 上游收窄授权 |
| Sora-Editor-LICENSE.txt | https://raw.githubusercontent.com/Rosemoe/sora-editor/0.23.5/LICENSE ；保留上游原文 |
| Shizuku-API-LICENSE.txt | https://raw.githubusercontent.com/RikkaApps/Shizuku-API/master/LICENSE ；适用于所用 13.1.5 制品，POM 声明 MIT，原文含 Rikka 版权 |
| Zstd-JNI-LICENSE.txt | https://raw.githubusercontent.com/luben/zstd-jni/v1.5.7-15/LICENSE ；JNI 包装层 BSD-2-Clause |
| Zstd-LICENSE.txt | https://raw.githubusercontent.com/facebook/zstd/v1.5.7/LICENSE ；Zstandard 内核 BSD-3-Clause |
| CommonMark-LICENSE.txt | https://raw.githubusercontent.com/commonmark/commonmark-java/commonmark-parent-0.13.0/LICENSE.txt ；CommonMark 0.13.0 BSD-2-Clause |
| XZ-COPYING.txt | https://raw.githubusercontent.com/tukaani-project/xz-java/v1.9/COPYING ；XZ for Java 1.9 的原始公共领域与免责声明 |
| Bouncy-Castle-LICENSE.html | https://raw.githubusercontent.com/bcgit/bc-java/r1rv78/LICENSE.html ；保留 Bouncy Castle 1.78 系列许可与版权原文 |
| GPL-2.0.txt / LGPL-3.0.txt | SPDX license-list-data 的 GPL-2.0-only / LGPL-3.0-only 原文；PRoot、talloc 许可副本，不改变其允许后续版本的原声明 |

构建还会从实际 runtime classpath 的 JAR/AAR（含内嵌 classes.jar）收集 LICENSE、NOTICE、COPYING、版权声明，按依赖坐标隔离存储，避免同名文件互相覆盖；不收集 JVM 单测依赖。三个插件的 LICENSE 与 NOTICE 放在各自 plugin/ 中，随原有目录复制流程进入 APK 与 DSH。

生成结果位于 APK 的 assets/licenses/，可通过设置 → 关于 → 开源许可离线查看。DEPENDENCIES.txt 记录实际制品坐标，INDEX.txt 列出文本路径。此收集过程保留已有声明，不自动替缺失声明的上游推定许可，也不能替代对应源码的再分发义务。

构建后可运行 `python tools/check_license_bundle.py --apk app/build/outputs/apk/release/app-release.apk`，检查来源哈希、插件随包副本、许可索引和运行依赖范围；不传 `--apk` 时只核对源文件。这是文件交付检查，不是完整法律合规认证。

## 源码与再构建

- DSHBox 对应源码与构建说明：https://github.com/WSK-build/DSHBox 。发布二进制时须提供与其匹配的版本源码，不能只指向持续变动的分支。
- Sora Editor 0.23.5 上游源码：https://github.com/Rosemoe/sora-editor/tree/0.23.5 。项目以未修改 AAR 引入，保留 LGPL-2.1-or-later；不能仅凭应用整体 GPLv3 就宣称全部义务已满足。
- 重新构建或替换 Sora：获取对应上游源码，按上游构建说明生成 AAR，以 app/build.gradle.kts 中 libs.sora.editor 的位置替换为本地 AAR 或自建 Maven 同坐标制品，再构建 DSHBox。构建结果可用开发者自己的密钥签名；原项目不要求提供正式签名私钥。
- 发布者须依适用条款提供对应库源码与重建材料或同处可获取的源码交付；本目录许可文本和上游链接本身不构成完整源码交付声明。PRoot、talloc、Debian、Node 与随包 DSH 的源码及子组件许可也须按各自分发方式保留，详见 THIRD_PARTY_NOTICES.md。
