package com.dshbox.instance

import com.dshbox.app.common.DshInstance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 应急槽固定装配清单与删除守卫的策略语义。 */
class InstancePolicyTest {

    @Test
    fun emergencyAssembly_isExactlyMobileAdapt() {
        assertEquals(listOf("mobile-adapt"), InstancePolicy.EMERGENCY_ASSEMBLY)
    }

    @Test
    fun emergencyAssembly_rejectsOtherOwnPlugins() {
        assertTrue(InstancePolicy.allowsEmergencyAssembly("mobile-adapt"))
        assertFalse(InstancePolicy.allowsEmergencyAssembly("mobile-pilot"))
        assertFalse(InstancePolicy.allowsEmergencyAssembly("browser-use"))
        assertFalse(InstancePolicy.allowsEmergencyAssembly(""))
    }

    @Test
    fun deleteEmergency_onlyWhenInactive() {
        assertTrue(InstancePolicy.canDeleteEmergency(DshInstance.MAIN))
        assertFalse(InstancePolicy.canDeleteEmergency(DshInstance.EMERGENCY))
    }
}