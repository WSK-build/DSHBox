package dshbox.adapter

import androidx.compose.ui.graphics.ImageBitmap
import dshbox.adapter.ui.ProtectedAppUiModel
import dshbox.adapter.ui.filterProtectedApps
import dshbox.adapter.ui.isValidPackageName
import dshbox.adapter.ui.mergeProtectedApps
import dshbox.adapter.ui.protectedEntryStatusRes
import dshbox.adapter.ui.toggledProtectedPackages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「受保护应用」呈现判据：入口状态词、勾选集合的翻转与搜索过滤。
 * 名单的读写正本在内核（ProtectedAppsStore 那组测试），这里钉住的是界面消费的
 * 这半段——过滤只认名称与包名、忽略大小写；勾选是对称翻转；0 与非 0 各有各的词。
 */
class ProtectedAppsPresentationTest {

    private fun app(label: String, pkg: String) = ProtectedAppUiModel(label = label, packageName = pkg, icon = null as ImageBitmap?)

    // ── 搜索过滤 ──

    @Test
    fun blankQueryReturnsEverythingUntouched() {
        val apps = listOf(app("WeChat", "com.tencent.mm"), app("支付宝", "com.eg.android.AlipayGphone"))
        assertEquals(apps, filterProtectedApps(apps, ""))
        assertEquals(apps, filterProtectedApps(apps, "   "))
        // 空查询返回同一份列表，不额外拷贝。
        assertTrue(filterProtectedApps(apps, "") === apps)
    }

    @Test
    fun filterMatchesLabelOrPackageIgnoringCase() {
        val apps = listOf(
            app("WeChat", "com.tencent.mm"),
            app("支付宝", "com.eg.android.AlipayGphone"),
            app("Shizuku", "moe.shizuku.privileged.api"),
        )
        // 名称匹配不分大小写，也不认全名。
        assertEquals(listOf("WeChat"), filterProtectedApps(apps, "wechat").map { it.label })
        assertEquals(listOf("WeChat"), filterProtectedApps(apps, "CHA").map { it.label })
        // 包名同样参与匹配、不分大小写。
        assertEquals(listOf("Shizuku"), filterProtectedApps(apps, "MOE.SHIZUKU").map { it.label })
        assertEquals(listOf("支付宝"), filterProtectedApps(apps, "alipay").map { it.label })
        // 名称与包名都命不上的查询给出空结果，不退化为不过滤。
        assertTrue(filterProtectedApps(apps, "nomatch").isEmpty())
        // 首尾空白先剥掉再匹配。
        assertEquals(listOf("WeChat"), filterProtectedApps(apps, " wechat ").map { it.label })
    }

    // ── 按包名添加的形状校验 ──

    @Test
    fun packageNameShapeMirrorsTheKernelStore() {
        // 合法：字母开头 + 字母数字下划线点；首尾空白先剥掉。
        assertTrue(isValidPackageName("com.example.app"))
        assertTrue(isValidPackageName("Shizuku"))
        assertTrue(isValidPackageName(" moe.shizuku.privileged.api "))
        assertTrue(isValidPackageName("a_1.b"))
        assertTrue(isValidPackageName("a".repeat(128)))
        // 非法：空、不以字母开头、夹带其他符号、超长（内核 PACKAGE_SHAPE 上限 128）。
        assertTrue(!isValidPackageName(""))
        assertTrue(!isValidPackageName("   "))
        assertTrue(!isValidPackageName("1com.example"))
        assertTrue(!isValidPackageName(".com.example"))
        assertTrue(!isValidPackageName("com.exa mple"))
        assertTrue(!isValidPackageName("com/exa:mple"))
        assertTrue(!isValidPackageName("a".repeat(129)))
        // 内核的形状不检查点分段的健全性（`com.example.`、`com..example` 也放行）：
        // 这把尺子只镜像内核，不做更严的收紧——否则界面会拦下内核本来接受的输入。
        assertTrue(isValidPackageName("com.example."))
        assertTrue(isValidPackageName("com..example"))
    }

    // ── 未列出已保护包的合并 ──

    @Test
    fun protectedPackagesMissingFromInstalledListAppearAsUnlistedRows() {
        val installed = listOf(app("WeChat", "com.tencent.mm"), app("Shizuku", "moe.shizuku.privileged.api"))
        val merged = mergeProtectedApps(installed, setOf("com.tencent.mm", "com.example.hidden"))
        // 手动添加的包排最前、应用名回退包名、带 unlisted 标记供界面标注小字。
        assertEquals(listOf("com.example.hidden", "WeChat", "Shizuku"), merged.map { it.label })
        assertEquals(
            listOf(true, false, false),
            merged.map { it.unlisted },
        )
    }

    @Test
    fun multipleUnlistedPackagesSortByPackageNameAndInstalledOrderStaysPut() {
        val installed = listOf(app("B app", "com.b"), app("A app", "com.a"))
        val merged = mergeProtectedApps(installed, setOf("zz.upmost", "aa.upmost", "com.a"))
        // 未列出的已保护包按包名排序；已安装行的顺序不动（不重新排）。
        assertEquals(
            listOf("aa.upmost", "zz.upmost", "B app", "A app"),
            merged.map { it.label },
        )
    }

    @Test
    fun mergeIsIdentityWhenNothingIsUnlisted() {
        val installed = listOf(app("WeChat", "com.tencent.mm"))
        // 没有名单外包 → 原列表原样返回（同一份引用，不白拷）。
        assertTrue(mergeProtectedApps(installed, setOf("com.tencent.mm")) === installed)
        assertTrue(mergeProtectedApps(installed, emptySet()) === installed)
        // 名单里的包不在已安装清单 → 即便它已卸载，也要列出来保证取消得掉。
        assertEquals(
            listOf("com.gone.app"),
            mergeProtectedApps(emptyList(), setOf("com.gone.app")).map { it.packageName },
        )
    }

    // ── 勾选集合的翻转 ──

    @Test
    fun toggleAddsAndRemovesExactlyTheNamedPackage() {
        val current = setOf("com.a", "com.b")
        assertEquals(setOf("com.a", "com.b", "com.c"), toggledProtectedPackages(current, "com.c", protect = true))
        assertEquals(setOf("com.a"), toggledProtectedPackages(current, "com.b", protect = false))
        // 重复勾选不重复入集，取消不存在的包也保持原集不变（对称、幂等）。
        assertEquals(current, toggledProtectedPackages(current, "com.a", protect = true))
        assertEquals(current, toggledProtectedPackages(current, "com.x", protect = false))
    }

    @Test
    fun togglingCanBuildAndEmptyTheList() {
        // 空名单可以加出第一项（空状态只影响展示，不影响勾选）。
        assertEquals(setOf("com.a"), toggledProtectedPackages(emptySet(), "com.a", protect = true))
        // 逐个取消最终回到空集——空集是内核的合法输入，语义是「清空名单」。
        assertEquals(emptySet<String>(), toggledProtectedPackages(setOf("com.a"), "com.a", protect = false))
    }

    // ── 入口状态词 ──

    @Test
    fun zeroCountMeansUnsetAndPositiveCountMeansTheCountedLabel() {
        assertEquals(R.string.pilot_protected_status_unset, protectedEntryStatusRes(0))
        assertEquals(R.string.pilot_protected_status_count, protectedEntryStatusRes(1))
        assertEquals(R.string.pilot_protected_status_count, protectedEntryStatusRes(40))
        // 两个词不是同一个：0 不得伪装成「已保护 N 个」。
        assertTrue(protectedEntryStatusRes(0) != protectedEntryStatusRes(3))
    }
}
