package app.ridemate.onboarding

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import app.ridemate.R
import java.util.Locale

/** Phone makers whose own battery managers stop background apps beyond Android's battery optimisation. */
enum class OemFamily {
    XIAOMI,
    OPPO_REALME,
    VIVO,
    SAMSUNG,
    MOTOROLA,
    OTHER,
}

/**
 * OEM autostart / background guidance for the battery step (architecture §4.2: Xiaomi, Oppo/Realme/Vivo, Samsung,
 * plus Motorola). The steps are text; the button only opens the maker's settings screen when it exists, else App info.
 */
object OemGuidance {

    fun familyOf(manufacturer: String): OemFamily = when (manufacturer.trim().lowercase(Locale.ROOT)) {
        "xiaomi", "redmi", "poco" -> OemFamily.XIAOMI
        "oppo", "realme", "oneplus" -> OemFamily.OPPO_REALME
        "vivo", "iqoo" -> OemFamily.VIVO
        "samsung" -> OemFamily.SAMSUNG
        "motorola", "lenovo" -> OemFamily.MOTOROLA
        else -> OemFamily.OTHER
    }

    /** The steps to show for [family]; null when the maker adds nothing beyond Android's own setting. */
    @StringRes
    fun stepsRes(family: OemFamily): Int? = when (family) {
        OemFamily.XIAOMI -> R.string.onb_oem_xiaomi
        OemFamily.OPPO_REALME -> R.string.onb_oem_oppo_realme
        OemFamily.VIVO -> R.string.onb_oem_vivo
        OemFamily.SAMSUNG -> R.string.onb_oem_samsung
        OemFamily.MOTOROLA -> R.string.onb_oem_motorola
        OemFamily.OTHER -> null
    }

    /** The maker's autostart / background screen (best effort; these are settings screens, never ride apps). */
    fun settingsComponents(family: OemFamily): List<ComponentName> = when (family) {
        OemFamily.XIAOMI -> listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
        OemFamily.OPPO_REALME -> listOf(
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        )
        OemFamily.VIVO -> listOf(
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        )
        OemFamily.SAMSUNG -> listOf(
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        )
        OemFamily.MOTOROLA, OemFamily.OTHER -> emptyList()
    }

    /** Intents to try in order: the maker's screen(s), then App info (always present). */
    fun settingsIntents(context: Context, family: OemFamily): List<Intent> =
        settingsComponents(family).map { Intent().setComponent(it) } + PermissionIntents.appInfo(context)
}
