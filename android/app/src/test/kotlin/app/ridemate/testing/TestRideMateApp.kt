package app.ridemate.testing

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import app.ridemate.AppGraph
import app.ridemate.PlatformSeams
import app.ridemate.RideMateApp
import app.ridemate.config.BundledConfigSource
import app.ridemate.data.RideMateDb
import app.ridemate.net.ApiClient
import app.ridemate.ocr.OcrEngine
import app.ridemate.ocr.OcrFailedException
import app.ridemate.ondc.OndcTxnSource
import app.ridemate.util.BuildInfo
import okhttp3.OkHttpClient

/**
 * The Robolectric application for unit tests (robolectric.properties): a [RideMateApp] whose graph uses fakes
 * (no Firebase, no network, in-memory Room) and which does not start the platform on create. Tests reach the fakes
 * through [fakes].
 */
class TestRideMateApp : RideMateApp() {
    lateinit var fakes: TestSeams
        private set

    override fun createGraph(): AppGraph {
        fakes = TestSeams()
        return AppGraph(this, fakes.seams(), BuildInfo(versionCode = 1, versionName = "1.0.0-test", debug = true))
    }

    override fun startPlatform(graph: AppGraph) = Unit
}

/** Fakes behind a test [PlatformSeams]. */
class TestSeams(
    val clock: FakeClock = FakeClock(),
    val auth: FakeAuthBackend = FakeAuthBackend(),
    val google: FakeGoogleCredentialSource = FakeGoogleCredentialSource(),
    val remoteConfig: FakeRemoteConfigBackend = FakeRemoteConfigBackend(),
    val crash: FakeCrashBackend = FakeCrashBackend(),
    val bootIds: FakeBootIds = FakeBootIds(),
    var bundled: Map<String, String> = emptyMap(),
) {
    /** Replaces the OkHttp API client when set before the graph's first use (no real network either way). */
    var api: ApiClient? = null

    /** Channel B's OCR (ML Kit does not run on the JVM): fails unless a test sets one. */
    var ocr: OcrEngine = object : OcrEngine {
        override suspend fun recognize(frame: app.ridemate.capture.FrameImage) = throw OcrFailedException()
    }

    /** The ONDC transaction listener (never Firestore in tests). */
    var ondcTxns: OndcTxnSource = OndcTxnSource { _, _ -> AutoCloseable { } }

    fun seams(): PlatformSeams = PlatformSeams(
        clock = clock,
        authBackend = { auth },
        googleCredentials = { google },
        remoteConfigBackend = { remoteConfig },
        crashBackend = { crash },
        bundledConfig = { BundledConfigSource { bundled } },
        database = { context -> inMemoryDb(context) },
        httpClient = { OkHttpClient() },
        workManager = { context -> WorkManager.getInstance(context) },
        bootIds = { bootIds },
        ondcTxns = { ondcTxns },
        ocrEngine = { ocr },
        apiClient = { api },
    )

    companion object {
        fun inMemoryDb(context: Context): RideMateDb =
            Room.inMemoryDatabaseBuilder(context, RideMateDb::class.java).allowMainThreadQueries().build()
    }
}
