package app.ridemate.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.ridemate.R
import app.ridemate.dev.CaptureTester
import app.ridemate.dev.GoldenCaptureSource
import app.ridemate.home.PreSessionGate
import app.ridemate.onboarding.RideWatchSelfTest

/** Navigation that other areas' screens may ask the shell for. */
interface FeatureNavigator {
    fun back()

    fun home()

    /** Trip input (A2). */
    fun tripInput()

    /** The comparison list (A2) of [sessionId] (null: the current one). */
    fun comparison(sessionId: String?)

    /** The guardian (A3) of [sessionId]; [sheet] is an opaque id of a sheet to open (from a notification). */
    fun guardian(sessionId: String?, sheet: String? = null)

    /** Try the demo (A3). */
    fun demo()

    fun settings()

    /** The one-time Google sign-in offer at the first "Read fares" (see `SignInReoffer`). */
    fun signInOffer()
}

/**
 * Composable slots for screens owned by other areas (A2: trip input + comparison; A3: guardian + demo). The shell's
 * navigation renders whatever is bound here; [PlaceholderFeatureScreens] stands in until the integration binds the
 * real screens, so the shell never depends on them being present.
 */
interface FeatureScreens {
    @Composable
    fun TripInput(navigator: FeatureNavigator)

    @Composable
    fun Comparison(sessionId: String?, navigator: FeatureNavigator)

    @Composable
    fun Guardian(sessionId: String?, sheet: String?, navigator: FeatureNavigator)

    @Composable
    fun Demo(navigator: FeatureNavigator)
}

/** Stand-ins: a titled screen that says the part is not in this build, with a way back. */
object PlaceholderFeatureScreens : FeatureScreens {
    @Composable
    override fun TripInput(navigator: FeatureNavigator) = Placeholder(stringResource(R.string.ui_feature_trip), navigator)

    @Composable
    override fun Comparison(sessionId: String?, navigator: FeatureNavigator) =
        Placeholder(stringResource(R.string.ui_feature_compare), navigator)

    @Composable
    override fun Guardian(sessionId: String?, sheet: String?, navigator: FeatureNavigator) =
        Placeholder(stringResource(R.string.ui_feature_guardian), navigator)

    @Composable
    override fun Demo(navigator: FeatureNavigator) = Placeholder(stringResource(R.string.ui_feature_demo), navigator)

    @Composable
    private fun Placeholder(title: String, navigator: FeatureNavigator) {
        ScreenScaffold(title = title, onBack = navigator::back) {
            InfoCard(
                title = stringResource(R.string.ui_feature_missing_title),
                body = stringResource(R.string.ui_feature_missing_body),
                tone = ChipTone.WARNING,
            )
            Text(stringResource(R.string.ui_feature_missing_next), style = MaterialTheme.typography.bodyMedium)
            PrimaryButton(stringResource(R.string.ui_go_home), navigator::home)
        }
    }
}

/**
 * Everything the shell needs from other areas, in one place for the integration step to bind (AppGraph
 * `featureBindings`). Null services fall back to the shell's own defaults:
 * - [preSessionGate]: A2's PreSessionCheck (default: permission-based `DefaultPreSessionGate`);
 * - [rideWatchSelfTest]: A3's RideWatchStatus (default: `SelfTestRegistry`, which RideWatch feeds);
 * - [captureTester] / [goldenCaptures]: A2's CaptureManager (+ OcrAndMask) for the developer Recorder.
 */
data class FeatureBindings(
    val screens: FeatureScreens = PlaceholderFeatureScreens,
    val preSessionGate: PreSessionGate? = null,
    val rideWatchSelfTest: RideWatchSelfTest? = null,
    val captureTester: CaptureTester = CaptureTester.Unavailable,
    val goldenCaptures: GoldenCaptureSource = GoldenCaptureSource.Unavailable,
)
