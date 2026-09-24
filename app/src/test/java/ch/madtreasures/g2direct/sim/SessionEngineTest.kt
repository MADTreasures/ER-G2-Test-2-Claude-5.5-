package ch.madtreasures.g2direct.sim

import ch.madtreasures.g2direct.ble.ArmPhase
import ch.madtreasures.g2direct.ble.ArmTarget
import ch.madtreasures.g2direct.ble.ConnectRequest
import ch.madtreasures.g2direct.ble.PageState
import ch.madtreasures.g2direct.ble.SessionEngine
import ch.madtreasures.g2direct.ble.SessionPhase
import ch.madtreasures.g2direct.ble.Side
import ch.madtreasures.g2direct.protocol.CursorLayers
import ch.madtreasures.g2direct.protocol.OsEvent
import ch.madtreasures.g2direct.protocol.ServiceId
import ch.madtreasures.g2direct.protocol.TestPage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

@OptIn(ExperimentalCoroutinesApi::class)
class SessionEngineTest {

    private val request = ConnectRequest(
        "G2 Test",
        right = ArmTarget("AA:BB:CC:00:00:02", "Even G2_32_R_000002", bonded = true),
        left = ArmTarget("AA:BB:CC:00:00:01", "Even G2_32_L_000001", bonded = true),
    )

    private class Rig(val scope: TestScope, val glasses: FakeGlasses, val engine: SessionEngine)

    private fun TestScope.rig(configure: FakeGlasses.() -> Unit = {}): Rig {
        val glasses = FakeGlasses(backgroundScope).apply(configure)
        val engine = SessionEngine(backgroundScope, glasses.env(testScheduler))
        return Rig(this, glasses, engine)
    }

    private fun TestScope.step(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun Rig.cursorGlyphs() = glasses.glyphs().filter { it.ch == '╋' || it.ch == '◎' }

    /** Checks that the glasses show exactly one cursor, where the engine says it is. */
    private fun Rig.assertCursorShownAtReportedPosition() {
        val style = engine.state.value.style
        val glyphs = cursorGlyphs()
        assertEquals("genau ein Cursor sichtbar: $glyphs", 1, glyphs.size)
        val g = glyphs.single()
        val s = engine.state.value
        // The hot glyph (╋ or ◎) is 20 px wide and sits on the hot line of the block.
        assertEquals("$style x", s.cursorX, g.x + 10)
        assertEquals("$style y", s.cursorY, g.y + CursorLayers.LINE_HEIGHT / 2)
    }

    private fun Rig.assertNoViolations() {
        assertTrue("Protokollverletzungen: ${glasses.violations}", glasses.violations.isEmpty())
    }

    @Test
    fun connectsAuthenticatesBuildsPageAndShowsCursorInTheCentre() = runTest {
        val r = rig()
        r.engine.connect(request)
        step(10_000)

        val s = r.engine.state.value
        assertEquals(SessionPhase.READY, s.phase)
        assertEquals(PageState.ACTIVE, s.page)
        assertEquals(ArmPhase.READY, s.left.phase)
        assertEquals(ArmPhase.READY, s.right.phase)
        assertTrue(s.left.authenticated && s.right.authenticated)

        // Start-up sequence as MentraOS sends it.
        val rightDev = r.glasses.messages(Side.RIGHT, ServiceId.DEVICE_SETTINGS).mapNotNull { it.int(1) }
        assertEquals(listOf(4, 5, 128), rightDev.take(3))
        val leftDev = r.glasses.messages(Side.LEFT, ServiceId.DEVICE_SETTINGS).mapNotNull { it.int(1) }
        assertEquals(listOf(4, 128), leftDev)
        assertEquals(1, r.glasses.messages(Side.RIGHT, ServiceId.ONBOARDING).size)
        assertEquals(1, r.glasses.creates)
        assertEquals(TestPage.textContainers(288, 144).size, r.glasses.texts.size)
        assertEquals(77, s.battery)

        r.assertCursorShownAtReportedPosition()
        assertTrue(abs(s.cursorX - 288) <= 3 && abs(s.cursorY - 144) <= 3)
        r.assertNoViolations()
    }

    @Test
    fun relativeMovesMoveTheCursorOnTheGlasses() = runTest {
        val r = rig()
        r.engine.connect(request)
        step(10_000)
        var expectedX = 288f
        var expectedY = 144f
        val moves = listOf(40f to 0f, 0f to 30f, -120f to -60f, 200f to 7f, 3f to 3f, 500f to 500f, -900f to -900f)
        for ((dx, dy) in moves) {
            r.engine.moveCursorBy(dx, dy)
            val st = r.engine.state.value.style
            expectedX = (expectedX + dx).coerceIn(CursorLayers.minX(st).toFloat(), CursorLayers.maxX(st).toFloat())
            expectedY = (expectedY + dy).coerceIn(CursorLayers.minY(st).toFloat(), CursorLayers.maxY(st).toFloat())
            step(1_000)
            val s = r.engine.state.value
            assertTrue("x ${s.cursorX} vs $expectedX", abs(s.cursorX - expectedX) <= 2.5f)
            assertTrue("y ${s.cursorY} vs $expectedY", abs(s.cursorY - expectedY) <= 3.5f)
            r.assertCursorShownAtReportedPosition()
        }
        // The frame line shows the final position.
        assertTrue(r.glasses.texts.getValue(TestPage.FRAME_ID).content.contains("X%03d".format(r.engine.state.value.cursorX)))
        r.assertNoViolations()
    }

    @Test
    fun manySmallMovesAreCoalescedAndPaced() = runTest {
        val r = rig()
        r.engine.connect(request)
        step(10_000)
        val before = r.glasses.textUpdates
        // 2 seconds of 120 Hz touch events.
        repeat(240) {
            r.engine.moveCursorBy(1.2f, 0.4f)
            step(8)
        }
        step(1_000)
        val updates = r.glasses.textUpdates - before
        // One touch event per 8 ms must not become one BLE update per event.
        assertTrue("zu viele Updates: $updates", updates <= 2 * 25 * 2 + 10)
        assertTrue("zu wenige Updates: $updates", updates >= 20)
        r.assertCursorShownAtReportedPosition()
        val stats = r.engine.state.value.stats
        assertEquals(0, stats.textTimeouts)
        r.assertNoViolations()
    }

    @Test
    fun imageIsTransferredTwiceAndValid() = runTest {
        val r = rig()
        r.engine.connect(request)
        step(15_000)
        assertEquals(2, r.glasses.imagesCompleted)
        assertTrue(r.glasses.images.getValue(TestPage.WEDGE_ID).bmp != null)
        assertEquals("1/1 bestätigt", r.engine.state.value.imageStatus)
        r.assertNoViolations()
    }

    @Test
    fun spliceSemanticsGiveTheSameResult() = runTest {
        val r = rig { textSemantics = FakeGlasses.TextSemantics.SPLICE }
        r.engine.connect(request)
        step(10_000)
        for (i in 0 until 30) {
            r.engine.moveCursorBy(if (i % 2 == 0) 37f else -11f, if (i % 3 == 0) 23f else -9f)
            step(300)
            r.assertCursorShownAtReportedPosition()
        }
        r.assertNoViolations()
    }

    @Test
    fun ignoredCreateFallsBackToRebuild() = runTest {
        val r = rig {
            answerCreate = false
        }
        r.engine.connect(request)
        step(15_000)
        assertEquals(SessionPhase.READY, r.engine.state.value.phase)
        assertEquals(PageState.ACTIVE, r.engine.state.value.page)
        assertEquals(1, r.glasses.rebuilds)
        r.assertCursorShownAtReportedPosition()
        r.assertNoViolations()
    }

    @Test
    fun missingTextAcksSwitchToFixedRate() = runTest {
        val r = rig { sendTextAcks = false }
        r.engine.connect(request)
        step(10_000)
        repeat(20) {
            r.engine.moveCursorBy(9f, 0f)
            step(200)
        }
        val s = r.engine.state.value
        assertTrue(s.stats.fixedRateMode)
        r.assertCursorShownAtReportedPosition()
        r.assertNoViolations()
    }

    @Test
    fun leftArmFailureContinuesWithRightOnly() = runTest {
        val r = rig { failConnect += Side.LEFT }
        r.engine.connect(request)
        step(10_000)
        val s = r.engine.state.value
        assertEquals(SessionPhase.READY, s.phase)
        assertEquals(ArmPhase.FAILED, s.left.phase)
        assertTrue(s.notice!!.text.contains("Linker Bügel"))
        r.assertCursorShownAtReportedPosition()
        r.assertNoViolations()
    }

    @Test
    fun rightArmFailureIsReportedAsError() = runTest {
        val r = rig { failConnect += Side.RIGHT }
        r.engine.connect(request)
        step(10_000)
        val s = r.engine.state.value
        assertEquals(SessionPhase.FAILED, s.phase)
        assertTrue(s.notice!!.text.contains("133"))
    }

    @Test
    fun droppedRightArmReconnectsAndRedrawsThePage() = runTest {
        val r = rig()
        r.engine.connect(request)
        step(10_000)
        r.glasses.links.getValue(Side.RIGHT).drop()
        step(100)
        assertEquals(SessionPhase.RECONNECTING, r.engine.state.value.phase)
        // The glasses keep the old page registered for a while: CREATE gets no answer.
        step(20_000)
        assertEquals(SessionPhase.READY, r.engine.state.value.phase)
        assertEquals(PageState.ACTIVE, r.engine.state.value.page)
        r.engine.moveCursorBy(-50f, 20f)
        step(1_000)
        r.assertCursorShownAtReportedPosition()
        r.assertNoViolations()
    }

    @Test
    fun systemExitMarksThePageLostAndTouchRebuildsIt() = runTest {
        val r = rig()
        r.engine.connect(request)
        step(10_000)
        r.glasses.pageRegistered = false
        r.glasses.injectSysEvent(OsEvent.SYSTEM_EXIT)
        step(500)
        assertEquals(PageState.LOST, r.engine.state.value.page)
        r.engine.onTouchStart()
        step(5_000)
        assertEquals(PageState.ACTIVE, r.engine.state.value.page)
        assertEquals(2, r.glasses.creates)
        r.engine.moveCursorBy(30f, 30f)
        step(1_000)
        r.assertCursorShownAtReportedPosition()
        r.assertNoViolations()
    }

    @Test
    fun heartbeatsKeepFlowingToTheRightArmOnly() = runTest {
        val r = rig()
        r.engine.connect(request)
        step(31_000)
        val hubHeartbeats = r.glasses.evenHubCommands().count { it == 12 }
        val devHeartbeats = r.glasses.messages(Side.RIGHT, ServiceId.DEVICE_SETTINGS).count { it.int(1) == 14 }
        assertTrue("EvenHub-Heartbeats $hubHeartbeats", hubHeartbeats >= 4)
        assertTrue("DevSettings-Heartbeats $devHeartbeats", devHeartbeats >= 4)
        assertTrue(r.glasses.evenHubCommands(Side.LEFT).isEmpty())
        r.assertNoViolations()
    }

    @Test
    fun styleChangeRebuildsThePageAndKeepsOneCursor() = runTest {
        val r = rig()
        r.engine.connect(request)
        step(10_000)
        r.engine.moveCursorBy(-100f, 40f)
        step(1_000)
        for (style in CursorLayers.Style.entries) {
            r.engine.setStyle(style)
            step(5_000)
            assertEquals(style, r.engine.state.value.style)
            r.assertCursorShownAtReportedPosition()
            r.engine.moveCursorBy(17f, -23f)
            step(1_000)
            r.assertCursorShownAtReportedPosition()
        }
        r.assertNoViolations()
    }

    @Test
    fun disconnectClosesThePageFirst() = runTest {
        val r = rig()
        r.engine.connect(request)
        step(10_000)
        r.engine.disconnect()
        step(2_000)
        assertEquals(1, r.glasses.shutdowns)
        assertEquals(SessionPhase.IDLE, r.engine.state.value.phase)
        r.assertNoViolations()
    }

    @Test
    fun touchRequestsTheFastConnectionModeAtMostEvery20s() = runTest {
        val r = rig()
        r.engine.connect(request)
        step(10_000)
        r.engine.onTouchStart()
        step(100)
        r.engine.onTouchStart()
        step(100)
        assertEquals(1, r.glasses.links.getValue(Side.RIGHT).highPriorityRequests)
        step(21_000)
        r.engine.onTouchStart()
        step(100)
        assertEquals(2, r.glasses.links.getValue(Side.RIGHT).highPriorityRequests)
    }
}
