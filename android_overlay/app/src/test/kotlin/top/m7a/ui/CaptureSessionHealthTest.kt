package top.m7a.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSessionHealthTest {
    @Test fun distinguishesAbnormalProcessRestartFromNormalStop() {
        val lost = CaptureSessionHealth.explain("capturing", false, "", 2)
        assertTrue(lost.contains("2 帧"))
        assertTrue(lost.contains("进程曾重新创建"))
        assertTrue(!lost.contains("已手动停止"))

        val ongoing = CaptureSessionHealth.explain("capturing", true, "", 2)
        assertTrue(ongoing.contains("当前进程"))

        val stopped = CaptureSessionHealth.explain("finished", false, "系统已结束屏幕共享", 2)
        assertTrue(stopped.contains("系统已结束屏幕共享"))
        assertTrue(!stopped.contains("进程曾重新创建"))
    }

    @Test fun emptyHistoryIsNotReportedAsCrash() {
        assertTrue(CaptureSessionHealth.explain("", false, "", 0)
            .contains("没有上次会话记录"))
    }
}
