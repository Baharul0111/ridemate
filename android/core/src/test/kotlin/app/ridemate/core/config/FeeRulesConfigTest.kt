package app.ridemate.core.config

import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FeeRulesConfigTest {
    /** The contracts §2.5 example. */
    private val json = """
        {"schema": "1.0", "default": {"pct": 10, "max_inr": 100, "free_while_searching": true},
         "apps": {"com.olacabs.customer": {"free_within_s_after_accept": 180, "free_if_driver_late_min": 5, "max_inr": 100},
                  "com.ubercab": {"free_within_s_after_accept": 120, "max_inr": 100},
                  "com.rapido.passenger": {"free_within_s_after_accept": 60, "max_inr": 50}},
         "states": {"AS": {"max_inr": 100}}}
    """.trimIndent()

    private val estimator = FeeEstimator(parseFeeRules(json))

    private fun fee(
        app: Provider,
        state: RideState = RideState.ASSIGNED,
        sinceAccept: Long? = null,
        late: Int? = null,
        stateCode: String? = "AS",
        fare: Int? = null,
    ) = estimator.estimate(app, state, sinceAccept, late, stateCode, fare)

    @Test
    fun `searching legs are free to cancel`() {
        for (state in listOf(RideState.SEARCHING, RideState.BOOK_REQUESTED, RideState.DRIVER_CANCELLED, RideState.OPENED)) {
            assertEquals(state.name, FeeNote.Free(null), fee(Provider.UBER, state))
        }
        assertEquals(FeeNote.Free(null), fee(Provider.RAPIDO, RideState.CANCEL_GUIDED, sinceAccept = null))
    }

    @Test
    fun `assigned legs are free only inside the app's window, with a safety margin`() {
        // Ola: free for 180 s after acceptance; 15 s are kept back for notification delay.
        assertEquals(FeeNote.Free(165), fee(Provider.OLA, sinceAccept = 0))
        assertEquals(FeeNote.Free(1), fee(Provider.OLA, sinceAccept = 164))
        assertEquals(FeeNote.MayApply(100), fee(Provider.OLA, sinceAccept = 165))
        assertEquals(FeeNote.MayApply(100), fee(Provider.OLA, sinceAccept = 600))
        assertEquals(FeeNote.Free(45), fee(Provider.UBER, RideState.ARRIVING, sinceAccept = 60))
        assertEquals(FeeNote.MayApply(50), fee(Provider.RAPIDO, sinceAccept = 50))
        // Unknown acceptance time → may apply.
        assertEquals(FeeNote.MayApply(100), fee(Provider.UBER, RideState.MAYBE_ASSIGNED, sinceAccept = null))
        assertEquals(FeeNote.MayApply(50), fee(Provider.RAPIDO, RideState.CANCEL_REQUESTED, sinceAccept = 300))
    }

    @Test
    fun `a late driver makes the Ola cancel free`() {
        assertEquals(FeeNote.MayApply(100), fee(Provider.OLA, sinceAccept = 600, late = 5))
        assertEquals(FeeNote.Free(null), fee(Provider.OLA, sinceAccept = 600, late = 6))
        // Uber has no lateness rule in this config.
        assertEquals(FeeNote.MayApply(100), fee(Provider.UBER, sinceAccept = 600, late = 30))
    }

    @Test
    fun `caps combine app policy, state law and the fare`() {
        val strict = FeeEstimator(parseFeeRules(json.replace("\"AS\": {\"max_inr\": 100}", "\"AS\": {\"max_inr\": 30}, \"KA\": {\"max_inr\": 75, \"pct\": 5}")))
        assertEquals(FeeNote.MayApply(30), strict.estimate(Provider.UBER, RideState.ASSIGNED, 600, null, "AS"))
        assertEquals(FeeNote.MayApply(30), strict.estimate(Provider.RAPIDO, RideState.ASSIGNED, 600, null, "as"))
        assertEquals(FeeNote.MayApply(75), strict.estimate(Provider.UBER, RideState.ASSIGNED, 600, null, "KA"))
        assertEquals(FeeNote.MayApply(100), strict.estimate(Provider.UBER, RideState.ASSIGNED, 600, null, null))
        // 10% of a ₹180 fare is ₹18; 5% (Karnataka) of ₹180 is ₹9.
        assertEquals(FeeNote.MayApply(18), fee(Provider.UBER, sinceAccept = 600, fare = 180))
        assertEquals(FeeNote.MayApply(9), strict.estimate(Provider.UBER, RideState.ASSIGNED, 600, null, "KA", fareInr = 180))
        assertEquals(FeeNote.Free(null), fee(Provider.UBER, sinceAccept = 600, fare = 0))
    }

    @Test
    fun `nothing is estimated for ONDC, trips or finished legs`() {
        assertEquals(FeeNote.Unknown, fee(Provider.ONDC, RideState.SEARCHING))
        for (state in listOf(RideState.ON_TRIP, RideState.ENDED, RideState.CANCEL_CONFIRMED, RideState.PROVIDER_CANCELLED, RideState.SOFT_CANCEL)) {
            assertEquals(state.name, FeeNote.Unknown, fee(Provider.UBER, state, sinceAccept = 10))
        }
        // Demo apps without their own rule use the default.
        assertEquals(FeeNote.MayApply(100), fee(Provider.DEMO_KESTREL, sinceAccept = 10))
    }

    @Test
    fun `invalid rules are rejected`() {
        for (broken in listOf(
            json.replace("\"pct\": 10", "\"pct\": 150"),
            json.replace("\"max_inr\": 50", "\"max_inr\": -1"),
            json.replace("\"AS\"", "\"Assam\""),
            json.replace("\"default\": {\"pct\": 10, \"max_inr\": 100,", "\"default\": {\"pct\": 10,"),
            json.replace("\"schema\": \"1.0\"", "\"schema\": \"0.9\""),
        )) {
            assertThrows(broken, IllegalArgumentException::class.java) { parseFeeRules(broken) }
        }
    }
}
