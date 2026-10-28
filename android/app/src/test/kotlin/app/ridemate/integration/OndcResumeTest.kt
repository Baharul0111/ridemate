package app.ridemate.integration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.core.guardian.LegRole
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.notify.GuardianUiState
import app.ridemate.notify.LegUi
import app.ridemate.ondc.OndcPhase
import app.ridemate.ondc.OndcView
import app.ridemate.session.GuardianStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** A booked network ride is re-attached after a process restart, and only then. */
@RunWith(AndroidJUnit4::class)
class OndcResumeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val memory = OndcTxnMemory(context.getSharedPreferences(OndcTxnMemory.PREFS_NAME, Context.MODE_PRIVATE))

    private fun ui(state: RideState) = GuardianUiState(
        sessionId = "s1",
        legs = listOf(LegUi("s1:ondc", Provider.ONDC, state, 1, LegRole.KEPT, dismissed = false, booked = true, endedByApp = false)),
    )

    private val open = GuardianStatus(restored = true, sessionId = "s1", sessionOpen = true)

    @Test
    fun `the client's transaction is remembered and re-attached for an unsettled network leg`() {
        assertNull(OndcResume.toRemember(OndcView(legId = "s1:ondc", phase = OndcPhase.SEARCHING)))
        val (legId, txnId) = OndcResume.toRemember(OndcView(legId = "s1:ondc", txnId = "t-42", phase = OndcPhase.BOOKED))!!
        memory.remember(legId, txnId)

        assertEquals("s1:ondc" to "t-42", OndcResume.resumeTarget(open, ui(RideState.CONFIRMED), memory))
        assertNull("settled: nothing to listen to", OndcResume.resumeTarget(open, ui(RideState.CANCEL_CONFIRMED), memory))
        assertNull("no session left", OndcResume.resumeTarget(GuardianStatus(restored = true), ui(RideState.CONFIRMED), memory))
        assertNull("before the restore", OndcResume.resumeTarget(open.copy(restored = false), ui(RideState.CONFIRMED), memory))

        memory.clear()
        assertNull("Erase forgets it", OndcResume.resumeTarget(open, ui(RideState.CONFIRMED), memory))
    }
}
