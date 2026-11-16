package com.dshbox.app.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前置检查的单元测试。
 *
 * 覆盖的三条规则都来自实测踩过的坑，不是防御性编程：
 *  - ctx-bin 版本**前向不兼容**（编译版本 > 运行时 → create 返回 -1）；
 *  - ctx-bin 分段必须按**精确字节数**校验（曾因此把 4B 的数据误记成 1B）；
 *  - 内存不足时硬跑会挂死（LMKD 回收 mmap 页 → DSP 侧 SMMU 缺页 → 永久等待）。
 */
class NpuPreflightTest {

    @Test
    fun `版本解析取前三段数字`() {
        assertEquals(Triple(2, 42, 0), NpuPreflight.parseVersion("2.42.0"))
        assertEquals(Triple(2, 42, 0), NpuPreflight.parseVersion("2.42.0.251225135753_193295"))
        assertEquals(Triple(2, 51, 0), NpuPreflight.parseVersion("2.51.0.260929181845"))
        assertEquals(Triple(2, 40, 0), NpuPreflight.parseVersion("2.40"))
    }

    @Test
    fun `版本解析对非法输入返回 null`() {
        assertEquals(null, NpuPreflight.parseVersion("abc"))
        assertEquals(null, NpuPreflight.parseVersion("2"))
    }

    @Test
    fun `ctx-bin 版本高于运行时应当拒绝`() {
        // 实测：QAIRT 2.42 编译的包在 2.40 运行时上 create 返回 -1。
        val r = NpuPreflight.checkVersionCompatibility("2.42.0", "2.40.0")
        assertFalse(r.ok)
        assertTrue(r.fatal)
    }

    @Test
    fun `ctx-bin 版本等于运行时应当通过`() {
        val r = NpuPreflight.checkVersionCompatibility("2.40.0", "2.40.0")
        assertTrue(r.ok)
        assertFalse(r.fatal)
    }

    @Test
    fun `ctx-bin 版本低于运行时应当通过`() {
        val r = NpuPreflight.checkVersionCompatibility("2.36.0", "2.40.0")
        assertTrue(r.ok)
    }

    @Test
    fun `版本信息缺失时降级为告警而非失败`() {
        val r = NpuPreflight.checkVersionCompatibility(null, "2.40.0")
        assertTrue(r.ok)
        assertFalse(r.fatal)
    }

    @Test
    fun `内存不足应当致命失败`() {
        val r = NpuPreflight.checkMemory(2L * 1024 * 1024 * 1024)
        assertFalse(r.ok)
        assertTrue(r.fatal)
    }

    @Test
    fun `内存偏紧时通过但给出告警`() {
        val r = NpuPreflight.checkMemory(5L * 1024 * 1024 * 1024)
        assertTrue(r.ok)
        assertFalse(r.fatal)
        assertTrue(r.message.contains("偏紧"))
    }

    @Test
    fun `内存充足时通过`() {
        val r = NpuPreflight.checkMemory(8L * 1024 * 1024 * 1024)
        assertTrue(r.ok)
        assertFalse(r.fatal)
    }
}
