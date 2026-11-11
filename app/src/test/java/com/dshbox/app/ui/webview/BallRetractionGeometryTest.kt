package com.dshbox.app.ui.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮球自动收边的几何单测：最近边缘判定（[nearestHorizontalEdge]）、
 * 收边后球心 X（[retractedCenterX]）与松手即收判定（[shouldSnapOnRelease]）。
 *
 * 这些纯函数是收边行为的**落点契约**：
 *  - 判缘以球心为准，中缝固定取左（口径回归锁）。
 *  - 收边露出宽度的口径由「可见宽度 = 直径 × f」**反解**得出：收边球心距缘
 *    = r·(1 − 2f)，f 取 0.25 时球心距缘 = +0.5r，即**球心悬在容器边界之外
 *    半个半径** —— 露出部分恰是直径的四分之一。纯函数锁住这条线；最终
 *    观感由渲染层的实际裁切决定。
 *  - 收边坐标**有意**可以在容器边界之外（出界半半径），不被 coerce 夹回；
 *    只有退化输入（容器 0 宽、半径非正）回贴容器边界。
 *  - 松手即收以球心到最近水平缘的**严格小于**距离命中：< 阈值即收、恰等
 *    阈值不收；球心越界用负距离表达，天然命中。
 */
class BallRetractionGeometryTest {

    private val radius = 20f
    private val boardWidth = 400

    // ── nearestHorizontalEdge ─────────────────────────────────────────────

    @Test
    fun leftSideCenterPicksLeftEdge() {
        assertTrue(nearestHorizontalEdge(centerX = 10f, boardWidth))
        assertTrue(nearestHorizontalEdge(centerX = 1f, boardWidth))
    }

    @Test
    fun rightSideCenterPicksRightEdge() {
        assertFalse(nearestHorizontalEdge(centerX = 260f, boardWidth))
        assertFalse(nearestHorizontalEdge(centerX = 399f, boardWidth))
    }

    /** 正中缝取左缘分支（判断式 `<=`），口径固定不漂移（回归锁）。 */
    @Test
    fun exactMiddlePicksLeftByConvention() {
        assertTrue(nearestHorizontalEdge(centerX = 200f, boardWidth))
    }

    /** 容器 0 宽（页签失活零尺寸放置）返回稳定默认，不抛异常。 */
    @Test
    fun zeroWidthPicksStableDefault() {
        assertTrue(nearestHorizontalEdge(centerX = 500f, boardWidth = 0))
        assertTrue(nearestHorizontalEdge(centerX = -50f, boardWidth = 0))
    }

    // ── retractedCenterX ──────────────────────────────────────────────────

    /**
     * 左缘口径：可见宽度 = 直径 × f = 2r·f = 10（f = 0.25），反解收边球心
     * = r·(2f − 1) = −0.5r = −10 —— 球心悬在容器左缘之外半半径。
     */
    @Test
    fun retractedLeftXHalfRadiusOutside() {
        assertEquals(-10f, retractedCenterX(leftEdge = true, boardWidth, radius), 0.0001f)
    }

    /** 右缘镜像：球心 = 宽 + 0.5r。 */
    @Test
    fun retractedRightXMirrors() {
        assertEquals(boardWidth + 10f, retractedCenterX(leftEdge = false, boardWidth, radius), 0.0001f)
    }

    /** 镜像对称：左缘球心出左缘的距离 = 右缘球心出右缘的距离。 */
    @Test
    fun mirrorSymmetry() {
        val left = retractedCenterX(true, boardWidth, radius)
        val right = retractedCenterX(false, boardWidth, radius)
        assertEquals(-left, right - boardWidth, 0.0001f)
    }

    /**
     * 收边可见宽度就是直径的四分之一：球体与容器交集 = 球心 + r = 0.5r
     * = 直径的 0.25。（此处为口径核算，真实裁切由渲染层负责。）
     */
    @Test
    fun visibleWidthIsQuarterOfDiameter() {
        val left = retractedCenterX(true, boardWidth, radius)
        // 可见宽度 = 球体右缘 − 容器左缘 = (left + r) − 0 = 0.5r。
        val visibleLeft = radius + left
        assertEquals(0.25f * (2f * radius), visibleLeft, 0.0001f)
        val right = retractedCenterX(false, boardWidth, radius)
        // 右缘镜像：可见宽度 = 容器右缘 − 球体左缘 = w − (right − r)。
        val visibleRight = boardWidth - (right - radius)
        assertEquals(0.25f * (2f * radius), visibleRight, 0.0001f)
    }

    /** 可见比例默认值为四分之一，不经过调用处隐式传入。 */
    @Test
    fun defaultVisibleFractionIsQuarter() {
        assertEquals(0.25f, RETRACT_VISIBLE_FRACTION, 0f)
    }

    /**
     * 缩放补偿口径：收边体态把球缩到 s < 1，可见宽随之缩水（不补偿时 0.25
     * 直径变成 (s − 0.5)/2 直径，s = 0.96 即约 0.23）；retractedCenterX 的
     * `scale` 参数按收边终态（BALL_RETRACT_SCALE）反解回四分之一。
     */
    @Test
    fun retractScaleCompensatesVisibleWidth() {
        // 左缘：球心 = r·(2f − s) = 20·(0.5 − 0.96) = −9.2；右缘 = 宽 + 9.2。
        assertEquals(-9.2f, retractedCenterX(true, boardWidth, radius, scale = 0.96f), 0.0001f)
        assertEquals(boardWidth + 9.2f, retractedCenterX(false, boardWidth, radius, scale = 0.96f), 0.0001f)
        // 终态可见宽核算：视觉球心 −0.46r，缩放半径 0.96r，交集 = 0.5r。
        val visible = 0.96f * radius + retractedCenterX(true, boardWidth, radius, scale = 0.96f)
        assertEquals(0.25f * (2f * radius), visible, 0.0001f)
    }

    /** 缩放缺省 1：与原口径完全一致（无缩放感知的旧调用点零行为漂移）。 */
    @Test
    fun defaultScaleKeepsOriginalGeometry() {
        assertEquals(
            retractedCenterX(true, boardWidth, radius),
            retractedCenterX(true, boardWidth, radius, scale = 1f),
            0f,
        )
        assertEquals(
            retractedCenterX(false, boardWidth, radius),
            retractedCenterX(false, boardWidth, radius, scale = 1f),
            0f,
        )
    }

    /** 收边终态体态就是 0.96：调用处反解按此常量传入（口径锁）。 */
    @Test
    fun retractScaleConstantIsPointNineSix() {
        assertEquals(0.96f, BALL_RETRACT_SCALE, 0f)
    }

    /** 容器 0 宽：返回 0（贴边即容器边），不产生 NaN/Infinity。 */
    @Test
    fun zeroWidthClampsToContainerEdge() {
        assertEquals(0f, retractedCenterX(true, 0, radius), 0f)
        assertEquals(0f, retractedCenterX(false, 0, radius), 0f)
        assertEquals(0f, retractedCenterX(true, -100, radius), 0f)
    }

    /** 非正半径：退化贴边，同样不算出负数/NaN。 */
    @Test
    fun nonPositiveRadiusClampsToEdge() {
        assertEquals(0f, retractedCenterX(true, boardWidth, 0f), 0f)
        assertEquals(0f, retractedCenterX(false, boardWidth, -5f), 0f)
    }

    /**
     * 半径大于容器宽（极端小窗/分屏）：收边口径不再夹回——出界半半径本来就是
     * 收边态的本意，左缘球心 = −0.5r、右缘 = 宽 + 0.5r 原样返回。
     */
    @Test
    fun radiusLargerThanWidthFollowsFormula() {
        assertEquals(-60f, retractedCenterX(true, 60, 120f), 0.0001f)
        assertEquals(60f + 60f, retractedCenterX(false, 60, 120f), 0.0001f)
    }

    /** 可见比例 0：完全藏起，球心正好出界整整一个半径。 */
    @Test
    fun zeroVisibleFractionHidesBall() {
        assertEquals(-radius, retractedCenterX(true, boardWidth, radius, visibleFraction = 0f), 0.0001f)
        assertEquals(boardWidth + radius, retractedCenterX(false, boardWidth, radius, visibleFraction = 0f), 0.0001f)
    }

    /** 可见比例 1：不收边，球心贴边内侧 = 完整可见位置。 */
    @Test
    fun fullVisibleFractionKeepsBallFullVisible() {
        assertEquals(radius, retractedCenterX(true, boardWidth, radius, visibleFraction = 1f), 0.0001f)
        assertEquals(boardWidth - radius, retractedCenterX(false, boardWidth, radius, visibleFraction = 1f), 0.0001f)
    }

    // ── shouldSnapOnRelease（松手即收）───────────────────────────────────

    /** 阈值契约：24dp，与实现处调用口参一致，不经过调用处隐式传入。 */
    @Test
    fun edgeSnapThresholdIsTwentyFourDp() {
        assertEquals(24f, BALL_EDGE_SNAP_THRESHOLD.value, 0f)
    }

    /** 贴左缘松手：球心距左缘 < 阈值命中。 */
    @Test
    fun releaseNearLeftEdgeSnaps() {
        assertTrue(shouldSnapOnRelease(centerX = 10f, boardWidth = 360f, threshold = 24f))
        assertTrue(shouldSnapOnRelease(centerX = 23.5f, boardWidth = 360f, threshold = 24f))
        // 距左缘 23.9（浮点追踪）；实现上是 min(左距, 右距) 均取更近者，左近即命中。
        assertTrue(shouldSnapOnRelease(centerX = 0.1f, boardWidth = 360f, threshold = 24f))
    }

    /** 贴右缘松手：球心距右缘 < 阈值命中（右距 = 宽 − 球心）。 */
    @Test
    fun releaseNearRightEdgeSnaps() {
        assertTrue(shouldSnapOnRelease(centerX = 350f, boardWidth = 360f, threshold = 24f))
        assertTrue(shouldSnapOnRelease(centerX = 336.1f, boardWidth = 360f, threshold = 24f))
    }

    /** 中缝与远端松手：两侧距离都够大，不收 —— 停在松手位置重新凑计时。 */
    @Test
    fun releaseFarFromEdgesDoesNotSnap() {
        assertFalse(shouldSnapOnRelease(centerX = 180f, boardWidth = 360f, threshold = 24f))
        assertFalse(shouldSnapOnRelease(centerX = 100f, boardWidth = 360f, threshold = 24f))
        assertFalse(shouldSnapOnRelease(centerX = 260f, boardWidth = 360f, threshold = 24f))
    }

    /**
     * **严格小于**口径：球心距缘恰好等于阈值正好落在边界上，不算贴边 ——
     * 防「等号兼任命中」与「等号意外错过」两种漂移方向的回归锁。
     */
    @Test
    fun releaseExactlyAtThresholdDoesNotSnap() {
        assertFalse(shouldSnapOnRelease(centerX = 24f, boardWidth = 360f, threshold = 24f))
        assertFalse(shouldSnapOnRelease(centerX = 360f - 24f, boardWidth = 360f, threshold = 24f))
    }

    /** 容器 0 宽（页签失活零尺寸放置）没有边缘可言，判定恒为不收。 */
    @Test
    fun zeroWidthNeverSnaps() {
        assertFalse(shouldSnapOnRelease(centerX = 0f, boardWidth = 0f, threshold = 24f))
        assertFalse(shouldSnapOnRelease(centerX = -50f, boardWidth = 0f, threshold = 24f))
        assertFalse(shouldSnapOnRelease(centerX = 500f, boardWidth = 0f, threshold = 24f))
    }

    /** 球心在容器边界之外：负距离天然命中，无论出左界还是出右界。 */
    @Test
    fun centerOutsideBoardSnaps() {
        // 出左界
        assertTrue(shouldSnapOnRelease(centerX = -5f, boardWidth = 360f, threshold = 24f))
        // 出右界：右距 = 360 − 370 = −10 < 阈值
        assertTrue(shouldSnapOnRelease(centerX = 370f, boardWidth = 360f, threshold = 24f))
        // 大幅越界同样命中
        assertTrue(shouldSnapOnRelease(centerX = -1000f, boardWidth = 360f, threshold = 24f))
        assertTrue(shouldSnapOnRelease(centerX = 10_000f, boardWidth = 360f, threshold = 24f))
    }

    /**
     * 阈值为 0（调用方按 0 密度布局换算的退化值）：界内球心永不命中 ——
     * 判定退化为「只在真越界时收」，与负距离口径自洽。
     */
    @Test
    fun zeroThresholdOnlySnapsOutside() {
        assertFalse(shouldSnapOnRelease(centerX = 0f, boardWidth = 360f, threshold = 0f))
        assertFalse(shouldSnapOnRelease(centerX = 360f, boardWidth = 360f, threshold = 0f))
        assertTrue(shouldSnapOnRelease(centerX = -1f, boardWidth = 360f, threshold = 0f))
        assertTrue(shouldSnapOnRelease(centerX = 361f, boardWidth = 360f, threshold = 0f))
    }

    /**
     * 拖动贴死边缘（球心被 clamp 到 r 处）也命中：40dp 球半径 20dp < 24dp
     * 阈值 —— 远离边缘的意图要显式向内拖（球心距缘 ≥ 24dp）才不收。
     */
    @Test
    fun clampedEdgeReleaseSnapsLikeRealDrag() {
        val r = 20f
        assertTrue(shouldSnapOnRelease(centerX = r, boardWidth = 360f, threshold = 24f))
        assertTrue(shouldSnapOnRelease(centerX = 360f - r, boardWidth = 360f, threshold = 24f))
    }
}
