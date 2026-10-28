package app.ridemate.home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.auth.AuthState
import app.ridemate.config.KillSwitches
import app.ridemate.core.model.Provider
import app.ridemate.data.SessionEntity
import app.ridemate.net.ApiResult
import app.ridemate.net.ConfigCheckQuota
import app.ridemate.net.ConfigCheckResponse
import app.ridemate.onboarding.AppPermission
import app.ridemate.testing.FakePermissions
import app.ridemate.testing.ShellFakeApi
import app.ridemate.testing.TestData
import app.ridemate.update.UpdateState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class HomeModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val google = AuthState.Google("rider@example.com", "Rider", null, "g-uid")

    @Test
    fun `AI status line maps every server answer to what the user can do`() {
        val on = KillSwitches()
        val off = KillSwitches(aiReaderEnabled = false)
        assertEquals(AiStatus.SwitchedOff, AiStatus.of(google, off, null))
        assertEquals(AiStatus.ManualMode, AiStatus.of(AuthState.SignedOut, on, null))
        assertEquals(AiStatus.ManualMode, AiStatus.of(AuthState.Anonymous("a"), on, ApiResult.Ok(ConfigCheckResponse(aiEnabled = true))))
        assertEquals(AiStatus.Checking, AiStatus.of(google, on, null))
        assertEquals(
            AiStatus.On(6),
            AiStatus.of(google, on, ApiResult.Ok(ConfigCheckResponse(aiEnabled = true, quota = ConfigCheckQuota(readsLeft = 6)))),
        )
        assertEquals(
            AiStatus.PausedUntil("13:30"),
            AiStatus.of(google, on, ApiResult.Ok(ConfigCheckResponse(aiEnabled = false, reason = "day_cap", resumeAtIst = "13:30"))),
        )
        assertEquals(AiStatus.Paused, AiStatus.of(google, on, ApiResult.Ok(ConfigCheckResponse(aiEnabled = false, reason = "budget"))))
        assertEquals(AiStatus.Paused, AiStatus.of(google, on, ApiResult.Ok(ConfigCheckResponse(aiEnabled = false, resumeAtIst = "nonsense"))))
        assertEquals(AiStatus.SwitchedOff, AiStatus.of(google, on, ApiResult.Ok(ConfigCheckResponse(aiEnabled = false, reason = "kill_switch"))))
        assertEquals(AiStatus.ManualMode, AiStatus.of(google, on, ApiResult.Ok(ConfigCheckResponse(aiEnabled = false, reason = "anonymous"))))
        assertEquals(AiStatus.PausedUntil("09:05"), AiStatus.of(google, on, ApiResult.AiPaused("quota_exhausted", "09:05", null)))
        assertEquals(AiStatus.ManualMode, AiStatus.of(google, on, ApiResult.GoogleRequired))
        assertEquals(AiStatus.Unknown(ApiResult.Offline), AiStatus.of(google, on, ApiResult.Offline))
    }

    @Test
    fun `AI status wording says when AI resumes in IST 12-hour time and what to do meanwhile`() {
        assertEquals(
            "Paused. AI reading resumes at 1:30 PM (IST). Type prices meanwhile.",
            AiStatusText.text(context, AiStatus.PausedUntil("13:30")),
        )
        assertEquals("On · 1 read left today", AiStatusText.text(context, AiStatus.On(1)))
        assertEquals("On · 6 reads left today", AiStatusText.text(context, AiStatus.On(6)))
        assertTrue(AiStatusText.text(context, AiStatus.ManualMode).contains("Sign in with Google"))
        assertTrue(AiStatusText.text(context, AiStatus.Unknown(ApiResult.Offline)).contains("offline"))
    }

    private fun model(
        scope: kotlinx.coroutines.CoroutineScope,
        auth: MutableStateFlow<AuthState> = MutableStateFlow(google),
        switches: MutableStateFlow<KillSwitches> = MutableStateFlow(KillSwitches()),
        api: ShellFakeApi = ShellFakeApi(),
        sessions: MutableStateFlow<List<SessionEntity>> = MutableStateFlow(emptyList()),
        gate: PreSessionGate = PreSessionGate { PreSessionReport.READY },
    ) = HomeModel(auth, switches, MutableStateFlow(UpdateState()), sessions, api, { gate }, scope)

    @Test
    fun `config-check is asked on open and again after signing in`() = runTest(UnconfinedTestDispatcher()) {
        val api = ShellFakeApi(ApiResult.Ok(ConfigCheckResponse(aiEnabled = false, resumeAtIst = "18:00")))
        val auth = MutableStateFlow<AuthState>(AuthState.SignedOut)
        val home = model(backgroundScope, auth = auth, api = api)
        assertEquals(0, api.configChecks)
        assertEquals(AiStatus.ManualMode, home.state.value.ai)
        auth.value = google
        assertEquals(1, api.configChecks)
        assertEquals(AiStatus.PausedUntil("18:00"), home.state.value.ai)
        api.configCheckResult = ApiResult.Ok(ConfigCheckResponse(aiEnabled = true))
        home.refreshAi()
        assertEquals(AiStatus.On(null), home.state.value.ai)
    }

    @Test
    fun `the kill switch wins over the server and the active session is shown`() = runTest(UnconfinedTestDispatcher()) {
        val switches = MutableStateFlow(KillSwitches())
        val sessions = MutableStateFlow<List<SessionEntity>>(emptyList())
        val home = model(backgroundScope, switches = switches, sessions = sessions, api = ShellFakeApi(ApiResult.Ok(ConfigCheckResponse(aiEnabled = true))))
        switches.value = KillSwitches(aiReaderEnabled = false, demoEnabled = false)
        assertEquals(AiStatus.SwitchedOff, home.state.value.ai)
        assertEquals(false, home.state.value.killSwitches.demoEnabled)

        sessions.value = listOf(TestData.session(id = "s9"))
        val active = home.state.value.activeSession!!
        assertEquals("s9", active.sessionId)
        assertEquals("LGBI Airport, Guwahati", active.dropLabel)
        assertEquals(listOf(Provider.UBER, Provider.RAPIDO), active.apps)
    }

    @Test
    fun `compare runs the pre-session check first`() = runTest(UnconfinedTestDispatcher()) {
        var report = PreSessionReport.READY
        var opened = 0
        val home = model(backgroundScope, gate = PreSessionGate { report })

        home.compare { opened++ }
        assertEquals(1, opened)
        assertEquals(CompareCheck.Idle, home.state.value.compare)

        report = PreSessionReport(listOf(PreSessionIssue.LISTENER_OFF), listOf(PreSessionIssue.OFFLINE))
        home.compare { opened++ }
        assertEquals(1, opened)
        assertEquals(CompareCheck.Blocked(report), home.state.value.compare)
        home.continueAnyway { opened++ }
        assertEquals("blocked checks cannot be skipped", 1, opened)

        report = PreSessionReport(emptyList(), listOf(PreSessionIssue.BATTERY_OPTIMISED))
        home.compare { opened++ }
        assertEquals(CompareCheck.Warnings(report), home.state.value.compare)
        home.continueAnyway { opened++ }
        assertEquals(2, opened)
        assertEquals(CompareCheck.Idle, home.state.value.compare)
    }

    @Test
    fun `default pre-session gate blocks when RideWatch is blind and warns about the rest`() = runTest {
        val permissions = FakePermissions()
        var apps = setOf(Provider.RAPIDO)
        var online = false
        val gate = DefaultPreSessionGate(permissions, { apps }, { online })
        val blind = gate.check()
        assertEquals(
            listOf(PreSessionIssue.LISTENER_OFF, PreSessionIssue.OVERLAY_OFF, PreSessionIssue.NOTIFICATIONS_OFF),
            blind.blocking,
        )
        assertEquals(listOf(PreSessionIssue.BATTERY_OPTIMISED, PreSessionIssue.LOCATION_OFF, PreSessionIssue.OFFLINE), blind.warnings)

        permissions.grant(*AppPermission.entries.toTypedArray())
        online = true
        assertEquals(PreSessionReport.READY, gate.check())
        apps = emptySet()
        assertEquals(listOf(PreSessionIssue.NO_SUPPORTED_APP), gate.check().blocking)
    }
}
