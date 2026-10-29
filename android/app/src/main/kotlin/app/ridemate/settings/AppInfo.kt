package app.ridemate.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import app.ridemate.R
import app.ridemate.auth.AuthState
import app.ridemate.config.KillSwitches
import app.ridemate.core.model.Provider
import app.ridemate.onboarding.AppPermission
import app.ridemate.privacy.SignInKind
import app.ridemate.update.UpdateState
import app.ridemate.util.BuildInfo
import java.security.MessageDigest

/**
 * The APK's signing certificate SHA-256, computed at runtime from PackageManager (goal 28), formatted like
 * `apksigner verify --print-certs` / `keytool` (`AB:CD:…`) so it can be compared with the README.
 */
object SigningCertificate {
    /** The current signer's fingerprint(s), or empty when Android does not report any (e.g. tests). */
    fun sha256(context: Context): List<String> = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
        val signing = info.signingInfo ?: return@runCatching emptyList()
        // With key rotation the history's last entry is the current signer.
        val signers = if (signing.hasMultipleSigners()) {
            signing.apkContentsSigners?.toList().orEmpty()
        } else {
            signing.signingCertificateHistory?.toList().orEmpty().takeLast(1)
        }
        signers.map { fingerprint(it.toByteArray()) }
    }.getOrDefault(emptyList())

    fun fingerprint(certificate: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(certificate).joinToString(":") { "%02X".format(it) }
}

/**
 * "Report a problem" (Settings): metadata only — app version, Android version and enums. Never screenshots,
 * screen or notification text, places, prices, emails or ids. The user adds their own words in their mail app.
 */
object ProblemReport {
    data class Input(
        val build: BuildInfo,
        val sdkInt: Int,
        val androidRelease: String,
        val signIn: SignInKind,
        val killSwitches: KillSwitches,
        val permissions: Map<AppPermission, Boolean>,
        val language: AppLanguage,
        val onboardingCompleted: Boolean,
        val update: UpdateState,
        val developerMode: Boolean,
    )

    /** The email subject in the app's language (the version is the only detail in it). */
    fun subject(context: Context, build: BuildInfo): String = context.getString(R.string.set_report_subject, build.versionName)

    /** `key: VALUE` lines; every value is a version number, a boolean or an enum name. */
    fun metadataLines(input: Input): List<String> = buildList {
        add("app_version: ${input.build.versionName} (${input.build.versionCode})")
        add("android: ${input.androidRelease} (API ${input.sdkInt})")
        add("sign_in: ${input.signIn.name}")
        add("language: ${input.language.name}")
        add("onboarding_completed: ${input.onboardingCompleted}")
        add("ai_reader_enabled: ${input.killSwitches.aiReaderEnabled}")
        Provider.LIVE_APPS.forEach { add("reader_${it.wireId}: ${input.killSwitches.readerEnabledFor(it)}") }
        add("ondc_enabled: ${input.killSwitches.ondcEnabled}")
        add("demo_enabled: ${input.killSwitches.demoEnabled}")
        add("better_ride_enabled: ${input.killSwitches.betterRideEnabled}")
        AppPermission.entries.forEach { add("permission_${it.name.lowercase()}: ${if (input.permissions[it] == true) "GRANTED" else "DENIED"}") }
        add("update_available: ${input.update.available}")
        add("below_min_supported: ${input.update.belowMinSupported}")
        add("developer_mode: ${input.developerMode}")
    }

    fun body(intro: String, input: Input): String = intro + "\n\n\n---\n" + metadataLines(input).joinToString("\n")

    fun signInKind(state: AuthState): SignInKind = when (state) {
        AuthState.SignedOut -> SignInKind.SIGNED_OUT
        is AuthState.Anonymous -> SignInKind.ANONYMOUS
        is AuthState.Google -> SignInKind.GOOGLE
    }
}
