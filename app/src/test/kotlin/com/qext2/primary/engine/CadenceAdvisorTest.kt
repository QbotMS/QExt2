package com.qext2.primary.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CadenceAdvisorTest {
    @Test fun `kolory tylko z modelu roweru`() {
        CadenceAdvisor.load("""{"bikes":{"10625":[[2,0,56,67]]}}""")
        assertEquals(CadenceAdvisor.RED, CadenceAdvisor.color("10625", 180, 240f, 0.5, 50))
        assertEquals(CadenceAdvisor.AMBER, CadenceAdvisor.color("10625", 180, 240f, 0.5, 60))
        assertNull(CadenceAdvisor.color("10625", 180, 240f, 0.5, 70))
        assertNull("brak modelu Grail", CadenceAdvisor.color("27856", 180, 240f, 0.5, 40))
        assertNull("ponizej 50% CP", CadenceAdvisor.color("10625", 100, 240f, 0.5, 40))
        assertNull("zjazd", CadenceAdvisor.color("10625", 180, 240f, -4.0, 40))
    }
}
