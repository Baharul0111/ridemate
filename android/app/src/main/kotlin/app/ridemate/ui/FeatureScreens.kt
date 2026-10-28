package app.ridemate.ui

import androidx.compose.runtime.Composable
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
 * Composable slots for screens owned by other areas (A1: trip input; A2: comparison; A3: guardian + demo), bound in
 * [app.ridemate.AppGraph.featureBindings] to `app.ridemate.integration.RideMateScreens`.
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

    /** Drawn over every screen: the guardian's open sheet while a ride session runs. */
    @Composable
    fun SessionSheets(navigator: FeatureNavigator)
}

/**
 * Everything the shell needs from other areas, in one place, bound by AppGraph `featureBindings`:
 * - [screens]: the real feature screens;
 * - [preSessionGate]: A2's PreSessionCheck (pings RideWatch);
 * - [rideWatchSelfTest]: A3's RideWatchStatus (sees RideMate's own `rm_selftest` post);
 * - [captureTester] / [goldenCaptures]: A2's CaptureManager (+ OcrAndMask) for the developer Recorder.
 */
data class FeatureBindings(
    val screens: FeatureScreens,
    val preSessionGate: PreSessionGate,
    val rideWatchSelfTest: RideWatchSelfTest,
    val captureTester: CaptureTester,
    val goldenCaptures: GoldenCaptureSource,
)
