package io.github.shaonialife.cardeck.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BaiduCruiseTaskEvidenceTest {
    @Test fun taskOnPhoneBlocksAutomaticCruise() {
        val dump = "ACTIVITY MANAGER ACTIVITIES\nDisplay #0 (activities from top to bottom):\nTask{a #1 A=10200:com.baidu.BaiduMap}\nDisplay #12:"
        assertEquals(BaiduCruiseTaskEvidence(true, 1), baiduCruiseTaskEvidence(true, dump))
    }

    @Test fun taskOnOtherVirtualDisplayAlsoBlocks() {
        val dump = "ACTIVITY MANAGER ACTIVITIES\nDisplay: mDisplayId=15\nActivityRecord{a com.baidu.BaiduMap/.MainActivity}"
        assertEquals(1, baiduCruiseTaskEvidence(true, dump).existingTaskCount)
    }

    @Test fun knownEmptyLayoutAllowsFreshStart() {
        val dump = "ACTIVITY MANAGER ACTIVITIES\nDisplay #0 (activities from top to bottom):\nTask{a #1 A=10200:another.application}"
        assertEquals(BaiduCruiseTaskEvidence(true, 0), baiduCruiseTaskEvidence(true, dump))
    }

    @Test fun failedCommandNeverMeansNoTask() {
        assertFalse(baiduCruiseTaskEvidence(false, "ACTIVITY MANAGER ACTIVITIES\nDisplay #0:").querySucceeded)
    }

    @Test fun unknownOrIncompleteOutputNeverMeansNoTask() {
        listOf("", "Permission Denial", "ACTIVITY MANAGER ACTIVITIES", "Display #0:").forEach {
            assertEquals(BaiduCruiseTaskEvidence(false, -1), baiduCruiseTaskEvidence(true, it))
        }
    }
}
