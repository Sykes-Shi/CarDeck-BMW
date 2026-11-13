package io.github.shaonialife.cardeck.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardMapRequestsTest {
    @Test fun repeatedBackAndPagerSettleShareOneMapCheckPerReturn() {
        val requests = DashboardMapRequests()
        assertTrue(requests.claim(53))
        assertFalse(requests.claim(53))
        assertTrue(requests.claim(56))
        assertFalse(requests.claim(53))
        assertFalse(requests.claim(56))
        assertTrue(DashboardMapRequests().claim(56))
    }
}
