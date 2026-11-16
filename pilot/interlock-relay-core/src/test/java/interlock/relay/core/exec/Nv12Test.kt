package interlock.relay.core.exec

import interlock.relay.core.exec.shizuku.RelayShellService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nv12 的纯 JVM 判据（不碰 Bitmap/Image/MediaCodec）：
 * - `srcAt` 的整除式映射对缩小（1080→405、1080×2400→324×720）、放大与退化尺寸都
 * **覆盖到源的末尾**，且与旧固定步长采样的裁切场景可区分 —— 录屏（fill/Bitmap）
 * 与实时流（fillFromImage/Image）共用这一张映射表，这里是它们的缩放回归；
 * - 通道提取的两条字节序：ARGB int（Bitmap.getPixels，录屏路径）与平面字节序
 * （0xAABBGGRR，实时流路径）各自的 R/G/B 读法，以及同一段红/蓝像素在两条
 * 路径下铺出的 Y/U/V —— 红蓝对调（U/V 反色）在这里就被抓住，不靠设备；
 * - StreamSession 的 watchdog 等待量与编码尺寸（1280 长边、两边取偶、不放大；
 * 720 是编码器配不上时的回退档）。
 */
class Nv12Test {

    // ── 缩放映射 ──

    @Test
    fun `1080 wide down to 405 reaches the right edge of the source, the fixed step did not`() {
        // 旧实现 step=1080/405=2，最后一列采到 808 —— 右侧 272px 被裁掉。
        // 整除式映射最后一列采到 1077，整幅源都在画面里。
        val last = Nv12.srcAt(404, 405, 1080)
        assertEquals(1077, last)
        assertTrue("must reach beyond the old crop point (808)", last > 808)
        // 单调不减、从 0 起
        var previous = -1
        for (col in 0..404) {
            val mapped = Nv12.srcAt(col, 405, 1080)
            assertTrue(mapped >= previous)
            assertTrue(mapped in 0..1079)
            previous = mapped
        }
        assertEquals(0, Nv12.srcAt(0, 405, 1080))
    }

    @Test
    fun `1080x2400 down to 324x720 covers the full height, the fixed step cropped it`() {
        // 旧实现 step=2400/720=3，最后一行采到 2157 —— 底部 243 行被裁掉。
        val lastRow = Nv12.srcAt(719, 720, 2400)
        assertEquals(2396, lastRow)
        assertTrue("must reach beyond the old crop point (2157)", lastRow > 2157)
        val lastCol = Nv12.srcAt(323, 324, 1080)
        assertEquals(1076, lastCol)
        assertTrue(lastCol > 1080 * 323 / 324 - 8)
    }

    @Test
    fun `upscale and degenerate sizes stay in range and hit both ends`() {
        // 放大：目标 8 取源 4 —— 两端都要踩到。
        assertEquals(0, Nv12.srcAt(0, 8, 4))
        assertEquals(3, Nv12.srcAt(7, 8, 4))
        var previous = -1
        for (dest in 0..7) {
            val mapped = Nv12.srcAt(dest, 8, 4)
            assertTrue(mapped >= previous)
            assertTrue(mapped in 0..3)
            previous = mapped
        }
        // 恒等与退化
        assertEquals(719, Nv12.srcAt(719, 720, 720))
        assertEquals(0, Nv12.srcAt(0, 1, 1))
        assertEquals(0, Nv12.srcAt(0, 3, 1))
    }

    // ── 通道提取的两条字节序 ──

    @Test
    fun `argb int path reads r at bits 16-23 like Bitmap getPixels`() {
        // ARGB 红：0xFFFF0000 → r=FF
        assertEquals(0xFF, Nv12.redOf(0xFFFF0000.toInt(), bgraOrder = false))
        assertEquals(0x00, Nv12.greenOf(0xFFFF0000.toInt()))
        assertEquals(0x00, Nv12.blueOf(0xFFFF0000.toInt(), bgraOrder = false))
        // ARGB 蓝：0xFF0000FF → b=FF
        assertEquals(0xFF, Nv12.blueOf(0xFF0000FF.toInt(), bgraOrder = false))
        assertEquals(0x00, Nv12.redOf(0xFF0000FF.toInt(), bgraOrder = false))
    }

    @Test
    fun `plane path reads r at the lowest byte like a little-endian RGBA_8888 buffer`() {
        // 平面字节序 int 0xAABBGGRR：红像素的字节是 R=FF,G=00,B=00,A=FF → int 0xFF0000FF
        assertEquals(0xFF, Nv12.redOf(0xFF0000FF.toInt(), bgraOrder = true))
        assertEquals(0x00, Nv12.greenOf(0xFF0000FF.toInt()))
        assertEquals(0x00, Nv12.blueOf(0xFF0000FF.toInt(), bgraOrder = true))
        // 平面字节序的蓝：B=FF → int 0xFFFF0000
        assertEquals(0xFF, Nv12.blueOf(0xFFFF0000.toInt(), bgraOrder = true))
        assertEquals(0x00, Nv12.redOf(0xFFFF0000.toInt(), bgraOrder = true))
        // 混色：平面 int 0xAABBGGRR = 0x40302010 → R=0x10,G=0x20,B=0x30
        assertEquals(0x10, Nv12.redOf(0x40302010, bgraOrder = true))
        assertEquals(0x20, Nv12.greenOf(0x40302010))
        assertEquals(0x30, Nv12.blueOf(0x40302010, bgraOrder = true))
    }

    // ── 端到端铺装（纯 int 数组 → NV12 平面）──

    /** BT.601 limited range 的手算基准（与实现同一组系数，数值独立核对过）。 */
    private fun expectedY(r: Int, g: Int, b: Int): Int =
        (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(16, 235)

    private fun expectedU(r: Int, g: Int, b: Int): Int =
        (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(16, 240)

    private fun expectedV(r: Int, g: Int, b: Int): Int =
        (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(16, 240)

    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `gray pixels pack to the neutral chroma plane regardless of byte order`() {
        val plane = ByteArray(4 * 2 + 4) // stride=4，两行 Y + 一行 UV
        val gray = argb(128, 128, 128)
        // 平面布局：stride=4、sliceHeight=2 → 两行 Y 在 0..7，UV 从 chromaBase=8 起。
        // ARGB 路
        Nv12.fillRgba(intArrayOf(gray, gray, gray, gray), rowInts = 2, srcWidth = 2, srcHeight = 2,
            plane = plane, stride = 4, sliceHeight = 2, width = 2, height = 2, bgraOrder = false)
        assertEquals(expectedY(128, 128, 128).toByte(), plane[0])
        assertEquals(128.toByte(), plane[8]) // U
        assertEquals(128.toByte(), plane[9]) // V
        // 平面路（同一灰值字节序下恰好同一个 int）
        java.util.Arrays.fill(plane, 0)
        Nv12.fillRgba(intArrayOf(gray, gray, gray, gray), rowInts = 2, srcWidth = 2, srcHeight = 2,
            plane = plane, stride = 4, sliceHeight = 2, width = 2, height = 2, bgraOrder = true)
        assertEquals(expectedY(128, 128, 128).toByte(), plane[0])
        assertEquals(128.toByte(), plane[8])
        assertEquals(128.toByte(), plane[9])
    }

    @Test
    fun `red and blue pack to distinct chroma and the plane order does not swap them`() {
        // ARGB 红：r=255 → Y=82, U=90, V=240
        run {
            val plane = ByteArray(4 * 2 + 4)
            Nv12.fillRgba(intArrayOf(argb(255, 0, 0), argb(255, 0, 0), argb(255, 0, 0), argb(255, 0, 0)),
                rowInts = 2, srcWidth = 2, srcHeight = 2,
                plane = plane, stride = 4, sliceHeight = 2, width = 2, height = 2, bgraOrder = false)
            assertEquals(expectedY(255, 0, 0).toByte(), plane[0])
            assertEquals(expectedU(255, 0, 0).toByte(), plane[8])
            assertEquals(expectedV(255, 0, 0).toByte(), plane[9])
            // 关键对照：同一个 int 若被按平面字节序读，得到的是蓝像素的 Y —— 两条路必须不同
            assertTrue(expectedY(255, 0, 0) != expectedY(0, 0, 255))
        }
        // 平面字节序的红：字节 R=FF,G=00,B=00,A=FF → int 0xFF0000FF，bgraOrder=true
        run {
            val plane = ByteArray(4 * 2 + 4)
            Nv12.fillRgba(intArrayOf(0xFF0000FF.toInt(), 0xFF0000FF.toInt(), 0xFF0000FF.toInt(), 0xFF0000FF.toInt()),
                rowInts = 2, srcWidth = 2, srcHeight = 2,
                plane = plane, stride = 4, sliceHeight = 2, width = 2, height = 2, bgraOrder = true)
            assertEquals(expectedY(255, 0, 0).toByte(), plane[0])
            assertEquals(expectedU(255, 0, 0).toByte(), plane[8])
            assertEquals(expectedV(255, 0, 0).toByte(), plane[9])
        }
        // ARGB 蓝：r=0,b=255 → Y=41, U=54, V=110 —— 与红逐项可区分
        run {
            val plane = ByteArray(4 * 2 + 4)
            Nv12.fillRgba(intArrayOf(argb(0, 0, 255), argb(0, 0, 255), argb(0, 0, 255), argb(0, 0, 255)),
                rowInts = 2, srcWidth = 2, srcHeight = 2,
                plane = plane, stride = 4, sliceHeight = 2, width = 2, height = 2, bgraOrder = false)
            assertEquals(expectedY(0, 0, 255).toByte(), plane[0])
            assertEquals(expectedU(0, 0, 255).toByte(), plane[8])
            assertEquals(expectedV(0, 0, 255).toByte(), plane[9])
        }
    }

    @Test
    fun `downscale maps the whole source width - a 5-wide source to 3 samples cols 0,1,3 not 0,1,2`() {
        // 5 列灰阶递增的源 → 3 列目标：整除式映射取源列 0,1,3；
        // 旧固定步长（5/3=1）取 0,1,2 —— 第三列的差别就是裁切与缩放的分界。
        // 单行源（rowInts=5, srcHeight=1），行距由 rowInts 携带对齐的语义。
        val src = intArrayOf(argb(10, 10, 10), argb(20, 20, 20), argb(30, 30, 30), argb(40, 40, 40), argb(50, 50, 50))
        val plane = ByteArray(8 * 2 + 8)
        Nv12.fillRgba(src, rowInts = 5, srcWidth = 5, srcHeight = 1,
            plane = plane, stride = 8, sliceHeight = 2, width = 3, height = 2, bgraOrder = false)
        assertEquals(expectedY(10, 10, 10).toByte(), plane[0])
        assertEquals(expectedY(20, 20, 20).toByte(), plane[1])
        assertEquals("third sample must come from source column 3 (40), not the cropped column 2 (30)",
            expectedY(40, 40, 40).toByte(), plane[2])
    }

    // ── StreamSession 的纯判据 ──

    @Test
    fun `watchdog waits the remaining session budget and clamps to a positive floor`() {
        val max = 30L * 60L * 1_000L
        // 刚开会话：等满上限
        assertEquals(max, RelayShellService.StreamSession.watchdogDelayMs(startedAtMs = 1_000L, nowMs = 1_000L))
        // 快到期：等余量
        assertEquals(5L, RelayShellService.StreamSession.watchdogDelayMs(startedAtMs = 0L, nowMs = max - 5L))
        // 已过点（时钟回拨/迟到的 watchdog）：至少再给 1ms，不抛不挂零
        assertEquals(1L, RelayShellService.StreamSession.watchdogDelayMs(startedAtMs = 0L, nowMs = max + 10L))
    }

    @Test
    fun `encode sizes are even, capped at the 1280 long side and never upscaled`() {
        val session = RelayShellService.StreamSession
        // 1080×1920 → 长边 1280：720×1280
        assertEquals(720 to 1280, session.encodeSize(1080, 1920))
        // 1080×2400 → 576×1280
        assertEquals(576 to 1280, session.encodeSize(1080, 2400))
        // 2560×2064 → 1280×1032
        assertEquals(1280 to 1032, session.encodeSize(2560, 2064))
        // 小于 1280：只取偶、不放大
        assertEquals(640 to 360, session.encodeSize(640, 360))
        // 奇数边长修成偶数
        assertEquals(2 to 2, session.encodeSize(1, 1))
        // 720 回退档：同一张映射，目标换成 720
        assertEquals(404 to 720, session.encodeSize(1080, 1920, 720))
        assertEquals(640 to 360, session.encodeSize(640, 360, 720))
    }
}
