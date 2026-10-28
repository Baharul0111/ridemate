package app.ridemate

import android.content.Context
import androidx.work.WorkManager
import app.ridemate.auth.AuthBackend
import app.ridemate.auth.CredentialManagerGoogleSource
import app.ridemate.auth.FirebaseAuthBackend
import app.ridemate.auth.GoogleCredentialSource
import app.ridemate.config.AssetBundledConfig
import app.ridemate.config.BundledConfigSource
import app.ridemate.config.FirebaseRemoteConfigBackend
import app.ridemate.config.RemoteConfigBackend
import app.ridemate.crash.CrashBackend
import app.ridemate.crash.FirebaseCrashBackend
import app.ridemate.data.RideMateDb
import app.ridemate.net.ApiClient
import app.ridemate.net.OkHttpApiClient
import app.ridemate.ocr.MlKitOcrEngine
import app.ridemate.ocr.OcrEngine
import app.ridemate.ondc.FirestoreOndcTxnSource
import app.ridemate.ondc.OndcTxnSource
import app.ridemate.util.AndroidBootIdProvider
import app.ridemate.util.BootIdProvider
import app.ridemate.util.Clock
import app.ridemate.util.SystemClocks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import okhttp3.OkHttpClient

/**
 * Everything in the platform that touches Firebase, the network stack, WorkManager or the database file, as
 * factories. [AppGraph] builds its singletons through these, so tests swap in fakes (no Firebase, no network) and
 * production uses [production].
 */
class PlatformSeams(
    val clock: Clock,
    val authBackend: () -> AuthBackend,
    val googleCredentials: (Context) -> GoogleCredentialSource,
    val remoteConfigBackend: () -> RemoteConfigBackend,
    val crashBackend: () -> CrashBackend?,
    val bundledConfig: (Context) -> BundledConfigSource,
    val database: (Context) -> RideMateDb,
    val httpClient: () -> OkHttpClient,
    val workManager: (Context) -> WorkManager,
    val bootIds: (Context) -> BootIdProvider,
    /** The ONDC transaction listener (Firestore `ondc_txn/{txnId}` in production). */
    val ondcTxns: () -> OndcTxnSource = { FirestoreOndcTxnSource() },
    /** Channel B's on-device OCR (ML Kit Text Recognition v2 in production). */
    val ocrEngine: () -> OcrEngine = { MlKitOcrEngine() },
    /** Replaces the OkHttp API client when it returns one (tests: a scripted server, never the real network). */
    val apiClient: () -> ApiClient? = { null },
) {
    companion object {
        fun production(): PlatformSeams = PlatformSeams(
            clock = SystemClocks,
            authBackend = { FirebaseAuthBackend { FirebaseAuth.getInstance() } },
            googleCredentials = { context -> CredentialManagerGoogleSource(context.getString(R.string.default_web_client_id)) },
            remoteConfigBackend = { FirebaseRemoteConfigBackend { FirebaseRemoteConfig.getInstance() } },
            crashBackend = { FirebaseCrashBackend() },
            bundledConfig = { context -> AssetBundledConfig(context.assets) },
            database = RideMateDb::build,
            httpClient = OkHttpApiClient::defaultHttpClient,
            workManager = { context -> WorkManager.getInstance(context) },
            bootIds = { context -> AndroidBootIdProvider(context) },
        )
    }
}
