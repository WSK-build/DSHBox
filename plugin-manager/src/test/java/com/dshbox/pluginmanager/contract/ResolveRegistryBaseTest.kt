package com.dshbox.pluginmanager.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 镜像源覆盖文件的解析规则（判定面单侧）。
 *
 * 覆盖文件位于 guest 可写的挂载点，解析侧必须对输入设限：首行、ASCII 空白、
 * 小写敏感方案、剥尾斜杠，并拒绝指向本机的地址。用例逐条钉死这些约束。
 */
class ResolveRegistryBaseTest {

    @Test
    fun `缺失与空白形态一律未配置`() {
        assertNull(HostContract.resolveRegistryBase(null))
        assertNull(HostContract.resolveRegistryBase(""))
        assertNull(HostContract.resolveRegistryBase("\n"))
        assertNull(HostContract.resolveRegistryBase("  \r\n\t "))
    }

    @Test
    fun `合法基址剥掉尾部全部斜杠`() {
        assertEquals(
            "https://mirrors.cloud.tencent.com/npm",
            HostContract.resolveRegistryBase("https://mirrors.cloud.tencent.com/npm"),
        )
        assertEquals("https://x", HostContract.resolveRegistryBase("https://x/"))
        assertEquals("https://x", HostContract.resolveRegistryBase("https://x///"))
        assertEquals("http://x", HostContract.resolveRegistryBase("http://x\r\n"))
    }

    @Test
    fun `方案前缀小写敏感`() {
        // 大写方案一律判未配置：解析规则不随输入变宽。
        assertNull(HostContract.resolveRegistryBase("HTTPS://x"))
        assertNull(HostContract.resolveRegistryBase("Https://x"))
        assertNull(HostContract.resolveRegistryBase("ftp://x"))
        assertNull(HostContract.resolveRegistryBase("registry=https://x"))
    }

    @Test
    fun `只取首行`() {
        assertEquals(
            "https://a",
            HostContract.resolveRegistryBase("https://a\nhttps://b"),
        )
    }

    @Test
    fun `首行语义固定为按换行切分`() {
        // 孤立 \r 不是行界，整行保留。
        assertEquals(
            "https://a\rhttps://b",
            HostContract.resolveRegistryBase("https://a\rhttps://b"),
        )
        // ASCII 空白集：不间断空格不算空白，判未配置。
        assertNull(HostContract.resolveRegistryBase("\u00A0https://x"))
        // 其余字符不校验，但无 host 的残缺 URL 不可作为基址（落点是本机）。
        assertNull(HostContract.resolveRegistryBase("https://"))
    }

    @Test
    fun `指向本机的基址一律丢弃`() {
        // 覆盖文件位于 guest 可写的挂载点，解析侧不得把宿主请求引向本机。
        assertNull(HostContract.resolveRegistryBase("http://127.0.0.1:4873"))
        assertNull(HostContract.resolveRegistryBase("http://user@127.0.0.1:4873"))
        assertNull(HostContract.resolveRegistryBase("https://localhost:4873/npm"))
        assertNull(HostContract.resolveRegistryBase("http://localhost.:4873"))
        assertNull(HostContract.resolveRegistryBase("http://[::1]:4873"))
        assertNull(HostContract.resolveRegistryBase("http://0.0.0.0:4873"))
        assertNull(HostContract.resolveRegistryBase("http://169.254.1.1"))
        // 本机的其它数值写法：整数、八进制段、十六进制与 IPv6 映射都等价于本机。
        assertNull(HostContract.resolveRegistryBase("http://2130706433:4873"))
        assertNull(HostContract.resolveRegistryBase("http://0177.0.0.1:4873"))
        assertNull(HostContract.resolveRegistryBase("http://0x7f000001:4873"))
        assertNull(HostContract.resolveRegistryBase("http://[::ffff:127.0.0.1]:4873"))
        assertNull(HostContract.resolveRegistryBase("http://[0:0:0:0:0:0:0:1]:4873"))
        // 无 host 的形态：解析落点是本机。
        assertNull(HostContract.resolveRegistryBase("http:///x"))
        assertNull(HostContract.resolveRegistryBase("http://:80/x"))
    }

    @Test
    fun `局域网自建镜像仍然放行`() {
        assertEquals("http://192.168.1.10:4873", HostContract.resolveRegistryBase("http://192.168.1.10:4873"))
        assertEquals("https://10.0.0.5/npm", HostContract.resolveRegistryBase("https://10.0.0.5/npm"))
    }

    @Test
    fun `兜底基址是官方源`() {
        assertEquals("https://registry.npmjs.org", HostContract.DEFAULT_REGISTRY_BASE)
    }
}
