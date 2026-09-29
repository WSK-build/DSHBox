package com.dshbox.pluginmanager.market.data

import com.dshbox.pluginmanager.layer.HostInfrastructure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宿主自带包的判定。
 *
 * 判据**基于包名**（loader 条目 id 与包名常常不同名），且官方集合由
 * `HostPackages` 在运行时推导（安装层实际有什么）+ 两条永不陈旧的前缀规则组成。
 * 这里把四种情形都钉住：命名空间、loader 命名空间、推导命中、判不出。
 */
class HostInfrastructureWhitelistTest {

    /** 模拟推导结果：安装层里实际存在的包（含不带官方前缀的）。 */
    private val installed = setOf("@deepseek-ai/dsh-settings", "cordis", "cosmokit")

    @Test
    fun loaderSyntheticEntriesAreProtected() {
        assertTrue(HostInfrastructure.isProtected("include", "cordis:include", installed))
        assertTrue(HostInfrastructure.isProtected("loader", "cordis:loader", installed))
    }

    @Test
    fun officialNamespaceIsProtected() {
        assertTrue(HostInfrastructure.isProtected("settings", "@deepseek-ai/dsh-settings", installed))
        assertTrue(HostInfrastructure.isProtected("tools", "@deepseek-ai/dsh-tools", installed))
        assertTrue(HostInfrastructure.isProtected("web", "@deepseek-ai/dsh-web", installed))
    }

    @Test
    fun packagesDerivedFromTheInstallationAreProtected() {
        // 名字不带官方前缀，但安装层里确实有它——只有推导能覆盖这种。
        assertTrue(HostInfrastructure.isProtected("cordis", "cordis", installed))
        assertTrue(HostInfrastructure.isProtected("cosmokit", "cosmokit", installed))
    }

    @Test
    fun unknownPackageNameIsProtected() {
        // 信息不足就不动手：误关官方条目会让整机起不来，代价远大于少隔离一个插件。
        assertTrue(HostInfrastructure.isProtected("whatever", null, installed))
        assertTrue(HostInfrastructure.isProtected("", "dshmarket", installed))
    }

    @Test
    fun thirdPartyPluginsAreNotProtected() {
        assertFalse(HostInfrastructure.isProtected("dsh-market", "dshmarket", installed))
        assertFalse(HostInfrastructure.isProtected("drop-caret", "dsh-drop-caret", installed))
        assertFalse(HostInfrastructure.isProtected("pomodoro-timer", "@community/pomodoro-timer", installed))
        // 名字里含 host 常用词也不算受保护（不做子串匹配）
        assertFalse(HostInfrastructure.isProtected("dsh-storage-s3", "@acme/dsh-storage-s3", installed))
    }

    @Test
    fun thirdPartyJudgementExcludesOfficialAndLocalAssets() {
        assertFalse(HostInfrastructure.isThirdParty("@deepseek-ai/dsh-tools", installed))
        assertFalse(HostInfrastructure.isThirdParty("cordis", installed))
        assertFalse(HostInfrastructure.isThirdParty("@local/dsh-mobile-adapt", installed))
        assertTrue(HostInfrastructure.isThirdParty("@anysearch/anysearch-dsh", installed))
        assertTrue(HostInfrastructure.isThirdParty("dsh-drop-caret", installed))
        assertFalse(HostInfrastructure.isThirdParty(null, installed))
    }
}
