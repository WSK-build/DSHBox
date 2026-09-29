package com.dshbox.pluginmanager.market.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数据源表本身的不变量。
 *
 * 看着琐碎，但它们决定"选择存不住的时候会怎样"：认不出的 id 必须回落默认源；
 * 每个源的地址表必须非空 —— 空地址表会让取数点一次请求都不发就宣称失败，
 * 那种失败连原因都说不清。
 */
class CatalogSourcesTest {

    @Test
    fun byIdOnlyAcceptsKnownIdsAndFallsBackOtherwise() {
        assertEquals(CatalogSource.MOBILE, CatalogSource.byId("awesome-dsh-mobile-plugins"))
        assertEquals(CatalogSource.OFFICIAL, CatalogSource.byId("awesome-dsh-plugin"))
        assertEquals(CatalogSource.DEFAULT, CatalogSource.byId(null))
        assertEquals(CatalogSource.DEFAULT, CatalogSource.byId(""))
        assertEquals(CatalogSource.DEFAULT, CatalogSource.byId("no-such-source"))
    }

    @Test
    fun defaultIsTheMobileCatalog() {
        assertEquals(CatalogSource.MOBILE, CatalogSource.DEFAULT)
    }

    @Test
    fun everySourceHasAtLeastOneHttpsAddress() {
        CatalogSource.entries.forEach { source ->
            assertTrue(source.id, source.addresses.isNotEmpty())
            source.addresses.forEach { url ->
                assertTrue("${source.id}: $url", url.startsWith("https://"))
            }
        }
    }

    @Test
    fun idsAreDistinct() {
        assertEquals(
            CatalogSource.entries.size,
            CatalogSource.entries.map { it.id }.distinct().size,
        )
    }
}
