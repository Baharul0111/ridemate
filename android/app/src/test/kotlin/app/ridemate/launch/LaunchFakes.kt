package app.ridemate.launch

import android.content.Intent
import app.ridemate.core.model.Provider

/** Installed ride apps for tests: each installed app has a launcher intent; deep links resolve when [linksHandled]. */
class FakeInstalledApps(
    var installed: Set<Provider> = Provider.LIVE_APPS.toSet(),
    var linksHandled: Boolean = true,
) : InstalledApps {
    val checkedIntents = mutableListOf<Intent>()

    override fun isInstalled(provider: Provider): Boolean = provider in installed && provider in Provider.LIVE_APPS

    override fun launchIntent(provider: Provider): Intent? =
        if (isInstalled(provider)) Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(provider.packageName) else null

    override fun canHandle(intent: Intent): Boolean {
        checkedIntents += intent
        return linksHandled
    }
}

class RecordingStarter(var accept: (Intent) -> Boolean = { true }) : ActivityStarter {
    val started = mutableListOf<Intent>()
    val refused = mutableListOf<Intent>()

    override fun start(intent: Intent): Boolean = if (accept(intent)) {
        started += intent
        true
    } else {
        refused += intent
        false
    }
}

class FakeNotifier(var canPost: Boolean = true) : LaunchNotifier {
    val posted = mutableListOf<LaunchRequest>()
    val cancelled = mutableListOf<Provider>()

    override fun postOpenApp(request: LaunchRequest): Boolean {
        if (canPost) posted += request
        return canPost
    }

    override fun cancel(provider: Provider) {
        cancelled += provider
    }
}

class FakeClipboard(var works: Boolean = true) : ClipboardWriter {
    val copies = mutableListOf<String>()

    override fun copyDrop(text: String): Boolean {
        if (works) copies += text
        return works
    }
}

class MutableBubble(override var isShowing: Boolean = false) : BubbleVisibility

class MutableForeground(override var isForeground: Boolean = false) : AppForeground

class RecordingLegSink : LegOpenedSink {
    val opened = mutableListOf<Triple<String, Provider, Long>>()

    override suspend fun legOpened(legId: String, provider: Provider, atWallMs: Long) {
        opened += Triple(legId, provider, atWallMs)
    }
}
