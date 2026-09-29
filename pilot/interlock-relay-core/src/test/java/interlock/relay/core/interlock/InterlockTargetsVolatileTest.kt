package interlock.relay.core.interlock

import interlock.relay.core.protocol.CapabilityId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这一答能不能留给下一次同参数的重试」的判据。
 *
 * 走错的代价不对称：把钉得住的说成钉不住，只是下一次重试重新问一遍；把钉不住的说成
 * 钉得住，是用户对 A 点的那一下被花到 B 上。所以凡效果要等执行那一刻去屏上现解的目标，
 * 一律算钉不住 —— 包括那些参数看起来写得非常具体的。
 */
class InterlockTargetsVolatileTest {

    @Test
    fun treeResolvedWritesAreVolatileEvenWithAnExactSelector() {
        // RecyclerView 里 `id` 与 `text` 每行都重复：同一时刻只有一行在屏上，命中的仍然
        // 恰好是一个，却是另一行的那一颗。滚动之后重试，那一下「允许」就换了一个目标。
        assertTrue(InterlockTargets.volatileFor(CapabilityId.UI_CLICK, hasTreeOrPointTarget = true))
        assertTrue(InterlockTargets.volatileFor(CapabilityId.UI_SET_VALUE, hasTreeOrPointTarget = true))
        assertTrue(InterlockTargets.volatileFor(CapabilityId.UI_DISMISS, hasTreeOrPointTarget = true))
    }

    @Test
    fun coordinateAndFocusTargetsAreVolatile() {
        // 「此刻这块屏上的这里」与「此刻聚焦的那个输入框」都不跨调用。
        assertTrue(InterlockTargets.volatileFor(CapabilityId.UI_TAP, hasTreeOrPointTarget = false))
        assertTrue(InterlockTargets.volatileFor(CapabilityId.UI_TEXT, hasTreeOrPointTarget = false))
        assertTrue(InterlockTargets.volatileFor(CapabilityId.SCREEN_CAPTURE, hasTreeOrPointTarget = false))
    }

    /** 参数说全了"读到的是什么"的调用才留得住一份迟到的同意。 */
    @Test
    fun dataTargetsStayParkable() {
        assertFalse(InterlockTargets.volatileFor(CapabilityId.CONTACT_WRITE, hasTreeOrPointTarget = false))
        assertFalse(InterlockTargets.volatileFor(CapabilityId.SECURE_SETTINGS, hasTreeOrPointTarget = false))
        assertFalse(InterlockTargets.volatileFor(CapabilityId.APP_LAUNCH, hasTreeOrPointTarget = false))
        assertFalse(InterlockTargets.volatileFor(CapabilityId.APPOPS_SET, hasTreeOrPointTarget = false))
    }

    /**
     * 参数只说"读哪一格"、没说"读到什么"的那一类：剪贴板、位置、通知列表、麦克风，
     * 递交上去的那个文件的字节，以及四条"把此刻设备上有什么列出来"的读。
     * 一份迟到 49 秒的同意放行的是另一段时间、另一个内容 —— `{"kind":"image","limit":20}`
     * 说全了"最近 20 张"，没说全是哪 20 张：中间有人拍一张，换掉的那一格用户从未见过。
     */
    @Test
    fun liveSourceReadsAreVolatile() {
        assertTrue(InterlockTargets.volatileFor(CapabilityId.CLIPBOARD_READ, hasTreeOrPointTarget = false))
        assertTrue(InterlockTargets.volatileFor(CapabilityId.LOCATION_READ, hasTreeOrPointTarget = false))
        assertTrue(InterlockTargets.volatileFor(CapabilityId.NOTIFY_READ, hasTreeOrPointTarget = false))
        assertTrue(InterlockTargets.volatileFor(CapabilityId.AUDIO_CAPTURE, hasTreeOrPointTarget = false))
        assertTrue(InterlockTargets.volatileFor(CapabilityId.PKG_INSTALL, hasTreeOrPointTarget = false))
        assertTrue(InterlockTargets.volatileFor(CapabilityId.MEDIA_WRITE, hasTreeOrPointTarget = false))
        assertTrue(InterlockTargets.volatileFor(CapabilityId.PKG_QUERY, hasTreeOrPointTarget = false))
        assertTrue(InterlockTargets.volatileFor(CapabilityId.MEDIA_READ, hasTreeOrPointTarget = false))
        assertTrue(InterlockTargets.volatileFor(CapabilityId.CONTACT_READ, hasTreeOrPointTarget = false))
        assertTrue(InterlockTargets.volatileFor(CapabilityId.CALENDAR_READ, hasTreeOrPointTarget = false))
    }

    /** 参数里给了树或坐标之外的目标形状时，任何能力都按钉不住算。 */
    @Test
    fun treeTargetMakesEvenUnknownCapabilitiesVolatile() {
        assertTrue(InterlockTargets.volatileFor(CapabilityId.APP_LAUNCH, hasTreeOrPointTarget = true))
    }

    /**
     * 全量对账：注册表里每一条能力都必须在这里被显式判过一次。
     *
     * 判据本身不报错，漏标才报错 —— 一条新能力没进这个集合时它默认按"留得住"走，
     * 而那正是最坏的一侧（用户对 A 点的那一下被花到 B 上）。新增能力时这条会红，
     * 逼写的人当场判一次：内容有没有被参数说尽。
     */
    @Test
    fun everyCapabilityIsDeclaredEitherVolatileOrParkable() {
        val volatile = CapabilityId.values()
            .filter { InterlockTargets.volatileFor(it, hasTreeOrPointTarget = false) }
            .map { it.wire }
            .toSet()
        assertEquals(VOLATILE_WIRE_IDS, volatile)
        assertEquals(
            "每条能力只能判一次",
            CapabilityId.values().map { it.wire }.toSet(),
            volatile + PARKABLE_WIRE_IDS,
        )
    }

    private companion object {
        /** 效果落在屏上（节点、坐标、那块屏的画面）与落在"当下那份数据"上的那些。 */
        val VOLATILE_WIRE_IDS = setOf(
            "ui.node", "ui.click", "ui.longClick", "ui.select", "ui.dismiss", "ui.scroll",
            "ui.setValue", "ui.setProgress", "ui.imeAction", "ui.waitFor",
            "ui.tap", "ui.swipe", "ui.text", "ui.key", "ui.snapshot",
            "screen.capture", "screen.observe", "screen.record",
            "audio.capture", "clip.read", "loc.read", "notify.read",
            "app.install", "media.write", "pkg.query", "media.read", "contact.read", "cal.read",
        )

        /** 参数把目标说尽了的那些：一次迟到同意花的仍是同一件事。 */
        val PARKABLE_WIRE_IDS = setOf(
            "surface.virtual", "app.launch", "app.stop", "notify.post",
            "clip.write", "contact.write", "cal.write",
            "sys.settings.write", "appops.set", "sys.intent", "sys.shell",
        )
    }
}
