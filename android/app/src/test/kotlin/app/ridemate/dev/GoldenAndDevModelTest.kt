package app.ridemate.dev

import android.app.Activity
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.auth.AuthState
import app.ridemate.core.model.Provider
import app.ridemate.core.model.ScreenType
import app.ridemate.data.RecorderPostEntity
import app.ridemate.net.ApiResult
import app.ridemate.net.ConfigCheckBudget
import app.ridemate.net.ConfigCheckResponse
import app.ridemate.testing.FakeClock
import app.ridemate.testing.FakePushTokens
import app.ridemate.testing.ShellFakeApi
import app.ridemate.ui.InMemoryKeyValueStore
import app.ridemate.util.BuildInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import java.io.File
import java.util.zip.ZipFile

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class GoldenAndDevModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
    private val capture = MaskedCapture(png, 1080, 2400, Provider.RAPIDO, ScreenType.FARE_OPTIONS)

    private fun labelled(screen: GoldenScreen) = screen.copy(
        tripKm = 6.2,
        rows = listOf(GoldenRow("auto", "Auto", 110, 110, 3, 140), GoldenRow("bike", "Bike", 60, 70, 2)),
        tags = listOf("strikethrough", "eta"),
    )

    @Test
    fun `golden store saves masked captures by hash and exports contracts §6 labels`() {
        val store = GoldenStore(File(tmp.root, "golden"))
        val screen = store.add(capture)
        assertEquals(GoldenStore.sha256(png), screen.sha256)
        assertEquals("images/${screen.sha256}.png", screen.file)
        assertEquals("rapido", screen.app)
        assertEquals("fare_options", screen.screenType)
        assertEquals("a repeat capture is not added twice", 1, store.also { it.add(capture) }.screens().size)
        assertEquals(setOf(GoldenProblem.NO_ROWS), GoldenValidator.problems(screen))

        val out = File(tmp.root, "export.zip")
        assertEquals("nothing complete, nothing exported", 0, store.exportZip(out))
        store.update(labelled(screen))
        assertEquals(1, store.exportZip(out))

        ZipFile(out).use { zip ->
            assertNotNull(zip.getEntry("images/${screen.sha256}.png"))
            assertTrue(zip.getInputStream(zip.getEntry("images/${screen.sha256}.png")).readBytes().contentEquals(png))
            val labels = Json.parseToJsonElement(zip.getInputStream(zip.getEntry("labels.json")).readBytes().decodeToString()).jsonObject
            assertEquals("1.0", labels["schema"]!!.jsonPrimitive.content)
            val s = labels["screens"]!!.jsonArray.single().jsonObject
            assertEquals(
                setOf("file", "sha256", "app", "screen_type", "frame_w", "frame_h", "trip_km", "rows", "driver_eta_min", "driver_distance_text", "tags"),
                s.keys,
            )
            assertEquals(1080, s["frame_w"]!!.jsonPrimitive.content.toInt())
            assertEquals(JsonNull, s["driver_eta_min"])
            val row = s["rows"]!!.jsonArray[0].jsonObject
            assertEquals(setOf("vehicle_class", "label", "price_min", "price_max", "eta_min", "struck_price"), row.keys)
            assertEquals("auto", row["vehicle_class"]!!.jsonPrimitive.content)
            assertEquals("140", row["struck_price"]!!.jsonPrimitive.content)
        }

        store.remove(screen.sha256)
        assertTrue(store.screens().isEmpty())
        assertFalse(store.imageFile(screen.sha256).exists())
        store.add(capture)
        store.clear()
        assertTrue(store.screens().isEmpty())
    }

    @Test
    fun `validator catches incomplete labels`() {
        val base = GoldenScreen(file = "f", sha256 = "s", app = "uber", screenType = "fare_options", frameW = 1, frameH = 1)
        assertEquals(setOf(GoldenProblem.NO_ROWS), GoldenValidator.problems(base))
        assertEquals(
            setOf(GoldenProblem.NO_APP, GoldenProblem.NO_SCREEN_TYPE),
            GoldenValidator.problems(base.copy(app = "ondc", screenType = "nope")),
        )
        val bad = base.copy(rows = listOf(GoldenRow("spaceship", "", 130, 110, -1)))
        assertEquals(
            setOf(GoldenProblem.ROW_BAD_CLASS, GoldenProblem.ROW_LABEL_MISSING, GoldenProblem.ROW_PRICE_ORDER, GoldenProblem.BAD_NUMBER),
            GoldenValidator.problems(bad),
        )
        assertEquals(setOf(GoldenProblem.ROW_PRICE_MISSING), GoldenValidator.problems(base.copy(rows = listOf(GoldenRow("auto", "Auto", null, 100, null)))))
        assertTrue(GoldenValidator.isComplete(base.copy(screenType = "searching")))
        assertEquals("driver_assigned", GoldenWire.screenType(ScreenType.DRIVER_ASSIGNED))
        assertEquals(ScreenType.CANCEL_FLOW, GoldenWire.screenTypeOf("cancel_flow"))
        assertFalse(Provider.ONDC in GoldenWire.APPS)
    }

    private val owner = AuthState.Google("owner@example.com", "Owner", null, "o")
    private val clock = FakeClock()
    private val gate = DeveloperModeGate(InMemoryKeyValueStore(), MutableStateFlow(owner), { state -> (state as? AuthState.Google)?.uid == "o" }, clock)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val recorder = mutableListOf<RecorderPostEntity>()

    private fun model(
        api: ShellFakeApi = ShellFakeApi(),
        push: OwnerPushTokens = FakePushTokens(),
        golden: GoldenStore = GoldenStore(File(tmp.root, "g")),
        clear: suspend () -> Unit = { recorder.clear() },
        scope: CoroutineScope = this.scope,
        io: CoroutineDispatcher = Dispatchers.Unconfined,
    ) =
        DevModel(
            gate = gate,
            recorderRecent = MutableStateFlow(recorder.toList()),
            recorderAll = { recorder.toList() },
            recorderClear = clear,
            golden = golden,
            writeText = { name, text -> File(tmp.root, name).apply { writeText(text) } },
            exportFile = { name -> File(tmp.root, name) },
            api = api,
            pushTokens = push,
            clock = clock,
            build = BuildInfo(1, "1.0.0-test", debug = true),
            io = io,
            scope = scope,
        )

    @Test
    fun `recorder export writes masked JSON for the share sheet, or says the log is empty`() = runBlocking {
        val dev = model()
        var shared: File? = null
        dev.exportRecorder { shared = it }.join()
        assertEquals(null, shared)
        assertEquals(DevMessage.RECORDER_EMPTY, dev.state.value.message)

        recorder += RecorderMasking.toEntity(
            RecordedPost("com.ubercab", "trip", null, true, 3, null, "Driver Asha", "OTP 7781", false, null, 1_793_000_000_000L),
        )
        dev.exportRecorder { shared = it }.join()
        val text = shared!!.readText()
        assertTrue(shared!!.name.startsWith("ridemate-recorder-") && shared!!.name.endsWith(".json"))
        assertTrue("\"kind\": \"recorder_posts\"" in text)
        assertFalse("7781" in text)
        dev.clearRecorder().join()
        assertTrue(recorder.isEmpty())
        assertEquals(DevMessage.RECORDER_CLEARED, dev.state.value.message)
    }

    @Test
    fun `capture test and golden capture report every outcome`() = runBlocking {
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        val dev = model()
        dev.runCaptureTest(activity, CaptureTester { CaptureTestResult.Done(blackFrame = true, bubbleVisible = false) }).join()
        assertEquals(CaptureTestStatus.Finished(CaptureTestResult.Done(true, false)), dev.state.value.captureTest)
        dev.runCaptureTest(activity, CaptureTester.Unavailable).join()
        assertEquals(CaptureTestStatus.Finished(CaptureTestResult.Unavailable), dev.state.value.captureTest)

        dev.captureGolden(activity, GoldenCaptureSource { GoldenCaptureResult.BlackFrame }).join()
        assertEquals(DevMessage.GOLDEN_BLACK_FRAME, dev.state.value.message)
        assertTrue(dev.state.value.golden.isEmpty())
        dev.captureGolden(activity, GoldenCaptureSource { GoldenCaptureResult.Captured(capture) }).join()
        assertEquals(DevMessage.GOLDEN_CAPTURED, dev.state.value.message)
        val screen = dev.state.value.golden.single()

        var exported: File? = null
        dev.exportGolden { exported = it }.join()
        assertEquals(DevMessage.GOLDEN_NOTHING_TO_EXPORT, dev.state.value.message)
        dev.saveGolden(labelled(screen))
        dev.exportGolden { exported = it }.join()
        assertTrue(exported!!.name.endsWith(".zip"))
        dev.removeGolden(screen.sha256)
        assertTrue(dev.state.value.golden.isEmpty())
    }

    @Test
    fun `budget view and owner alerts`() = runBlocking {
        val budget = ConfigCheckBudget(1.23, 80.0, 0.4, 12.0, 1.3, 0.1, "2027-01-26")
        val api = ShellFakeApi(ApiResult.Ok(ConfigCheckResponse(aiEnabled = true, capProfile = "normal", budget = budget)))
        val push = FakePushTokens()
        val dev = model(api, push)
        dev.loadBudget().join()
        assertEquals(BudgetStatus.Loaded(api.configCheckResult.getOrNull()!!), dev.state.value.budget)
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val lines = BudgetLines.lines(context, (dev.state.value.budget as BudgetStatus.Loaded).check).toMap()
        assertEquals("$1.23 of $80.00 (1%)", lines["AI spend"])
        assertEquals("$0.10 of $1.30 (7%)", lines["Today (pacing cap)"])
        assertEquals("26 Jan 2027", lines["Budget horizon ends"])
        assertEquals("Normal", lines["Cap profile"])
        assertEquals("On", lines["AI reading"])
        // Server reasons and profiles are shown in words, never as raw codes (unknown codes stay readable).
        assertEquals("Off: today's pacing cap reached", BudgetLines.aiStateText(context, ConfigCheckResponse(reason = "day_cap")))
        assertEquals("Off: spend meter unreadable (fails closed)", BudgetLines.aiStateText(context, ConfigCheckResponse(reason = "meter")))
        assertEquals("Off (server code new_reason)", BudgetLines.aiStateText(context, ConfigCheckResponse(reason = "new_reason")))
        assertEquals("Off", BudgetLines.aiStateText(context, ConfigCheckResponse()))
        listOf("signed_out", "anonymous", "kill_switch", "budget", "day_cap", "tier_pause", "quota", "breaker", "meter", "model_missing")
            .forEach { assertNotNull("contracts §1.3 reason $it", BudgetLines.reasonRes(it)) }
        assertEquals("Judging week", BudgetLines.profileText(context, "judging"))
        assertEquals("Not reported", BudgetLines.profileText(context, null))
        assertTrue("non-owners get no budget block", BudgetLines.lines(context, ConfigCheckResponse()).isEmpty())

        api.configCheckResult = ApiResult.Offline
        dev.loadBudget().join()
        assertEquals(BudgetStatus.Failed(ApiResult.Offline), dev.state.value.budget)

        dev.setOwnerAlerts(true).join()
        assertEquals(listOf("fcm-token-1"), api.registeredTokens)
        assertEquals(OwnerAlertsStatus.Registered, dev.state.value.ownerAlerts)
        assertTrue(dev.state.value.ownerAlertsOn)
        dev.setOwnerAlerts(false).join()
        assertEquals(1, push.deletes)
        assertFalse(dev.state.value.ownerAlertsOn)

        api.ownerTokenResult = ApiResult.Http(403, "forbidden")
        dev.setOwnerAlerts(true).join()
        assertEquals(OwnerAlertsStatus.Failed(ApiResult.Http(403, "forbidden")), dev.state.value.ownerAlerts)
        assertFalse("stays off when the server refused", dev.state.value.ownerAlertsOn)
        push.token = null
        dev.setOwnerAlerts(true).join()
        assertEquals(OwnerAlertsStatus.NoToken, dev.state.value.ownerAlerts)
    }

    @Test
    fun `golden export leaves out screens whose image is gone, so labels and images always match`() {
        val store = GoldenStore(File(tmp.root, "golden"))
        val kept = store.add(capture)
        val lost = store.add(MaskedCapture(byteArrayOf(9, 8, 7), 1080, 2400, Provider.UBER, ScreenType.FARE_OPTIONS))
        store.update(labelled(kept))
        store.update(labelled(lost).copy(app = "uber"))
        assertTrue(store.imageFile(lost.sha256).delete())

        val out = File(tmp.root, "export.zip")
        assertEquals(1, store.exportZip(out))
        ZipFile(out).use { zip ->
            val labels = Json.parseToJsonElement(zip.getInputStream(zip.getEntry("labels.json")).readBytes().decodeToString()).jsonObject
            assertEquals(listOf(kept.sha256), labels["screens"]!!.jsonArray.map { it.jsonObject["sha256"]!!.jsonPrimitive.content })
            assertEquals(setOf("labels.json", kept.file), zip.entries().asSequence().map { it.name }.toSet())
        }
        assertTrue(store.imageFile(lost.sha256).let { !it.exists() })
        assertTrue(store.imageFile(kept.sha256).delete())
        assertEquals("nothing left to export", 0, store.exportZip(File(tmp.root, "empty.zip")))
    }

    @Test
    fun `storage failures say what to do instead of crashing`() = runBlocking {
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        // A plain file where the golden directory should be: every write fails.
        val broken = File(tmp.root, "not-a-dir").apply { writeText("x") }
        val dev = model(golden = GoldenStore(broken), clear = { throw java.io.IOException("storage removed") })

        dev.clearRecorder().join()
        assertEquals(DevMessage.STORAGE_FAILED, dev.state.value.message)
        dev.clearMessage()
        dev.captureGolden(activity, GoldenCaptureSource { GoldenCaptureResult.Captured(capture) }).join()
        assertEquals(DevMessage.STORAGE_FAILED, dev.state.value.message)
        assertTrue(dev.state.value.golden.isEmpty())
        dev.clearMessage()
        dev.saveGolden(labelled(GoldenScreen(file = "f", sha256 = "s", frameW = 1, frameH = 1))).join()
        assertEquals(DevMessage.STORAGE_FAILED, dev.state.value.message)
        dev.clearMessage()
        dev.removeGolden("s").join()
        assertEquals(DevMessage.STORAGE_FAILED, dev.state.value.message)
    }

    @Test
    fun `owner alerts never hang on Firebase`() = runTest {
        val api = ShellFakeApi()
        val hanging = object : OwnerPushTokens {
            override suspend fun token(): String? = awaitCancellation()

            override suspend fun delete(): Boolean = awaitCancellation()
        }
        val dev = model(api, hanging, scope = backgroundScope, io = StandardTestDispatcher(testScheduler))
        val on = dev.setOwnerAlerts(true)
        runCurrent()
        assertEquals(OwnerAlertsStatus.Working, dev.state.value.ownerAlerts)
        advanceTimeBy(DevModel.PUSH_TOKEN_TIMEOUT_MS + 1)
        runCurrent()
        assertTrue(on.isCompleted)
        assertEquals(OwnerAlertsStatus.NoToken, dev.state.value.ownerAlerts)
        assertTrue(api.registeredTokens.isEmpty())
        assertFalse(dev.state.value.ownerAlertsOn)

        val off = dev.setOwnerAlerts(false)
        advanceTimeBy(DevModel.PUSH_TOKEN_TIMEOUT_MS + 1)
        runCurrent()
        assertTrue(off.isCompleted)
        assertEquals(OwnerAlertsStatus.Off, dev.state.value.ownerAlerts)
    }
}
