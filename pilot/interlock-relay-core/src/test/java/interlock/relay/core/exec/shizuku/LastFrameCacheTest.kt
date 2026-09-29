package interlock.relay.core.exec.shizuku

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「上一帧」缓存的判据：同一块屏才给、给得出就要说得出多久之前、超上限就不留。
 * 取帧本身要真机与用户服务，不在本测试范围内。
 */
class LastFrameCacheTest {

    private fun tempFile(): File = Files.createTempDirectory("last-frame").resolve("last-frame.png").toFile()

    @Test
    fun `a remembered frame is handed back with the age measured on the same clock`() {
        val file = tempFile()
        var now = 1_000L
        val cache = LastFrameCache(file, now = { now })
        assertTrue(cache.remember(7, byteArrayOf(1, 2, 3)))
        now += 4_500L
        val hit = cache.peek(7)
        assertEquals(listOf<Byte>(1, 2, 3), hit?.first?.toList())
        assertEquals(4_500L, hit?.second)
    }

    @Test
    fun `nothing is served for a display that never produced a frame`() {
        val cache = LastFrameCache(tempFile(), now = { 0L })
        assertNull(cache.peek(3))
    }

    @Test
    fun `another display id gets nothing`() {
        val cache = LastFrameCache(tempFile(), now = { 0L })
        cache.remember(7, byteArrayOf(9))
        assertNull("a frame from the previous display must not speak for this one", cache.peek(8))
    }

    @Test
    fun `an oversized frame is dropped together with whatever was cached before it`() {
        val file = tempFile()
        val cache = LastFrameCache(file, now = { 0L }, maxBytes = 4L)
        cache.remember(7, byteArrayOf(1, 1, 1))
        assertEquals(false, cache.remember(7, ByteArray(5)))
        assertNull(cache.peek(7))
        assertEquals(false, file.exists())
    }

    @Test
    fun `a clock that moved backwards serves nothing instead of a negative age`() {
        var now = 10_000L
        val cache = LastFrameCache(tempFile(), now = { now })
        cache.remember(7, byteArrayOf(1))
        now = 5_000L
        assertNull(cache.peek(7))
    }

    @Test
    fun `clear removes the cached bytes so the next miss answers unreachable again`() {
        val file = tempFile()
        val cache = LastFrameCache(file, now = { 0L })
        cache.remember(7, byteArrayOf(1, 2))
        cache.clear()
        assertNull(cache.peek(7))
        assertEquals(false, file.exists())
    }
}
