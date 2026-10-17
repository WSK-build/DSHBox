package com.dshbox.browser.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ProcessScopedInstanceTest {
    @Test
    fun serviceRecreationReusesTheProcessCoordinatorWithoutRerunningRecoveryFactory() {
        val scope = ProcessScopedInstance<CoordinatorProbe>()
        var processInitializations = 0
        val firstService = scope.getOrCreate {
            processInitializations++
            CoordinatorProbe()
        }

        // A subsequent bound-service instance must retrieve process state, not construct/recover again.
        val recreatedService = scope.getOrCreate {
            processInitializations++
            CoordinatorProbe()
        }

        assertSame(firstService, recreatedService)
        assertEquals(1, processInitializations)
        assertSame(firstService, scope.getOrNull())
    }

    private class CoordinatorProbe
}
