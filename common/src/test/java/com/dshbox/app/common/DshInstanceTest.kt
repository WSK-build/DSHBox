package com.dshbox.app.common

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** 实例槽位模型的路径解析与持久化值回落语义。 */
class DshInstanceTest {

    @Test
    fun guestHome_resolvesUnderWorkspace() {
        assertEquals("/root/projects/.dsh", DshInstance.MAIN.guestHome)
        assertEquals("/root/projects/.dsh-emergency", DshInstance.EMERGENCY.guestHome)
    }

    @Test
    fun hostHome_resolvesUnderUserData() {
        val userData = File("test-user-data")
        assertEquals(File(userData, ".dsh"), DshInstance.MAIN.hostHome(userData))
        assertEquals(File(userData, ".dsh-emergency"), DshInstance.EMERGENCY.hostHome(userData))
    }

    @Test
    fun fromHomeLeaf_roundTrips() {
        DshInstance.entries.forEach { assertEquals(it, DshInstance.fromHomeLeaf(it.homeLeaf)) }
    }

    @Test
    fun fromHomeLeaf_unknownFallsBackToMain() {
        assertEquals(DshInstance.MAIN, DshInstance.fromHomeLeaf(null))
        assertEquals(DshInstance.MAIN, DshInstance.fromHomeLeaf(""))
        assertEquals(DshInstance.MAIN, DshInstance.fromHomeLeaf(".dsh1"))
        assertEquals(DshInstance.MAIN, DshInstance.fromHomeLeaf("garbage"))
    }
}