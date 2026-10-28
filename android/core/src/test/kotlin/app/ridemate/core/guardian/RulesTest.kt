package app.ridemate.core.guardian

import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.core.replay.ReplayHarness
import app.ridemate.core.state.DriverInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private val rule = BetterRideRule()

    @Test
    fun `better means a gain of at least max(5 min, 40 percent)`() {
        assertTrue(rule.isBetter(10, 5))
        assertFalse(rule.isBetter(10, 6))
        assertFalse("40% of 20 is 8", rule.isBetter(20, 13))
        assertTrue(rule.isBetter(20, 12))
        assertTrue(rule.isBetter(30, 18))
        assertFalse(rule.isBetter(30, 19))
        assertFalse("5 min floor dominates for short ETAs", rule.isBetter(6, 2))
        assertTrue(rule.isBetter(6, 1))
        assertFalse(rule.isBetter(5, 5))
    }

    @Test
    fun `only VERIFIED and TEMPLATE ETAs are compared, counting down from the reading`() {
        fun driver(eta: Int?, source: EtaSource, at: Long = 0) = DriverInfo(etaMin = eta, etaSource = source, etaAtElapsedMs = at)
        assertTrue(rule.isBetter(driver(12, EtaSource.TEMPLATE), driver(5, EtaSource.VERIFIED, at = 60_000), 60_000))
        assertFalse("12 min read 3 min ago is 9 now", rule.isBetter(driver(12, EtaSource.TEMPLATE), driver(5, EtaSource.TEMPLATE, at = 180_000), 180_000))
        assertFalse(rule.isBetter(driver(12, EtaSource.NETWORK), driver(1, EtaSource.TEMPLATE), 0))
        assertFalse(rule.isBetter(driver(12, EtaSource.USER), driver(1, EtaSource.TEMPLATE), 0))
        assertFalse(rule.isBetter(driver(12, EtaSource.TEMPLATE), driver(null, EtaSource.NONE), 0))
        assertTrue(rule.currentIsClose(driver(4, EtaSource.TEMPLATE), 120_000))
        assertFalse(rule.currentIsClose(driver(4, EtaSource.TEMPLATE), 119_999))
        assertFalse(rule.currentIsClose(driver(null, EtaSource.NONE), 0))
        assertEquals(120_000L, rule.closeAtElapsedMs(driver(4, EtaSource.TEMPLATE)))
        assertEquals(0L, rule.closeAtElapsedMs(driver(1, EtaSource.VERIFIED)))
        assertNull(rule.closeAtElapsedMs(driver(4, EtaSource.NETWORK)))
    }

    @Test
    fun `reminder schedule is 30, 60, 120 s and then every 120 s`() {
        val schedule = ReminderSchedule()
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 360_000L, 480_000L), (0 until 6).map(schedule::offsetMs))
        assertEquals(0, schedule.nextIndexAfter(29_999))
        assertEquals(1, schedule.nextIndexAfter(30_000))
        assertEquals(4, schedule.nextIndexAfter(240_000))
        assertEquals(6, schedule.nextIndexAfter(500_000))
        assertThrows(IllegalArgumentException::class.java) { ReminderSchedule(offsetsMs = emptyList()) }
    }

    @Test
    fun `cancelled keywords are matched case-insensitively in OCR text`() {
        assertTrue(CancelledKeywords.found("Ride CANCELLED successfully", listOf("cancelled")))
        assertTrue(CancelledKeywords.found("आपकी राइड रद्द हो गई", listOf("cancelled", "रद्द")))
        assertFalse(CancelledKeywords.found("Your captain is on the way", listOf("cancelled", "")))
    }

    @Test
    fun `fee notes follow contracts 2_5 - free while searching, free windows, late drivers, capped percentages`() {
        val rules = FeeRules.parse(ReplayHarness.FEE_RULES_JSON)
        val noState = RuleBasedFeeEstimator(rules)
        val assam = RuleBasedFeeEstimator(rules, stateCode = "AS")
        fun q(provider: Provider, state: RideState, now: Long, assignedAt: Long? = 0, eta: Int? = null, fare: Int? = null) =
            FeeQuery(provider, state, assignedAt, eta, now, fare)

        assertEquals(FeeNote(FeeNoteKind.FREE_WHILE_SEARCHING), noState.note(q(Provider.UBER, RideState.SEARCHING, 0)))
        assertEquals(FeeNote(FeeNoteKind.FREE_WINDOW, freeForSeconds = 120), noState.note(q(Provider.UBER, RideState.ASSIGNED, 0)))
        assertEquals(FeeNote(FeeNoteKind.FREE_WINDOW, freeForSeconds = 1), noState.note(q(Provider.UBER, RideState.ASSIGNED, 119_500)))
        assertEquals(FeeNote(FeeNoteKind.MAY_APPLY, upToInr = 100), noState.note(q(Provider.UBER, RideState.ARRIVING, 120_000)))
        assertEquals("10% of ₹180, rounded up", FeeNote(FeeNoteKind.MAY_APPLY, upToInr = 18), noState.note(q(Provider.UBER, RideState.ASSIGNED, 200_000, fare = 180)))
        assertEquals("capped at ₹100", FeeNote(FeeNoteKind.MAY_APPLY, upToInr = 100), noState.note(q(Provider.UBER, RideState.ASSIGNED, 200_000, fare = 1_500)))
        assertEquals("Rapido caps at ₹50", FeeNote(FeeNoteKind.MAY_APPLY, upToInr = 50), noState.note(q(Provider.RAPIDO, RideState.ASSIGNED, 61_000)))
        assertEquals("the state rule wins", FeeNote(FeeNoteKind.MAY_APPLY, upToInr = 100), assam.note(q(Provider.RAPIDO, RideState.ASSIGNED, 61_000)))
        // Ola: free within 3 min of acceptance, or once the driver is more than 5 min late.
        assertEquals(FeeNoteKind.FREE_WINDOW, noState.note(q(Provider.OLA, RideState.ASSIGNED, 179_000, eta = 4)).kind)
        assertEquals(FeeNoteKind.MAY_APPLY, noState.note(q(Provider.OLA, RideState.ASSIGNED, 540_000, eta = 4)).kind)
        assertEquals(FeeNoteKind.FREE_DRIVER_LATE, noState.note(q(Provider.OLA, RideState.ASSIGNED, 540_001, eta = 4)).kind)
        assertEquals(FeeNote(FeeNoteKind.SHOWN_BEFORE_CONFIRM), noState.note(q(Provider.ONDC, RideState.CONFIRMED, 0)))
        assertEquals(FeeNote(FeeNoteKind.NETWORK_FEE, upToInr = 30), noState.note(FeeQuery(Provider.ONDC, RideState.ASSIGNED, 0, null, 0, ondcFeeInr = 30)))

        assertEquals(120_000L, noState.freeWindowEndsAtElapsedMs(q(Provider.UBER, RideState.ASSIGNED, 0)))
        assertEquals(180_000L, noState.freeWindowEndsAtElapsedMs(q(Provider.OLA, RideState.ARRIVING, 0)))
        assertNull(noState.freeWindowEndsAtElapsedMs(q(Provider.OLA, RideState.SEARCHING, 0)))
        assertNull(noState.freeWindowEndsAtElapsedMs(q(Provider.ONDC, RideState.ASSIGNED, 0)))
        assertNull(noState.freeWindowEndsAtElapsedMs(q(Provider.UBER, RideState.ASSIGNED, 0, assignedAt = null)))
    }

    @Test
    fun `fee rules reject an unknown schema major, and default to 10 percent capped at 100`() {
        assertThrows(IllegalArgumentException::class.java) { FeeRules.parse("""{"schema": "2.0"}""") }
        val defaults = FeeRules.parse("""{"schema": "1.3"}""")
        assertEquals(FeeRule(pct = 10, maxInr = 100, freeWhileSearching = true), defaults.ruleFor("com.ubercab", null))
        val noFreeSearch = RuleBasedFeeEstimator(FeeRules(default = FeeRule(pct = 10, maxInr = 80, freeWhileSearching = false)))
        assertEquals(FeeNote(FeeNoteKind.MAY_APPLY, upToInr = 80), noFreeSearch.note(FeeQuery(Provider.UBER, RideState.SEARCHING, null, null, 0)))
    }
}
