package app.ridemate.e2e

import app.ridemate.data.MigrationTest
import org.junit.runner.RunWith
import org.junit.runners.Suite

/**
 * The on-phone suite, in a fixed order: onboarding needs the freshly installed app, so it runs before the demo (which
 * then starts from Home). `connectedDebugAndroidTest` runs this suite (testInstrumentationRunnerArguments "class").
 */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    FreshInstallOnboardingTest::class,
    DemoEndToEndTest::class,
    DemoOcrRecorderTest::class,
    MigrationTest::class,
)
class DeviceTestSuite
