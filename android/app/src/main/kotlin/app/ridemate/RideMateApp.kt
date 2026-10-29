package app.ridemate

import android.app.Application
import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * The application: creates the process-wide [AppGraph] and starts the platform (crash reporting in release only,
 * Remote Config warm-up, the 24 h purge on WorkManager, the outbox backstop). Everything else is lazy.
 *
 * Tests subclass it and override [createGraph] / [startPlatform] to use fakes and skip Firebase.
 */
open class RideMateApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun attachBaseContext(base: Context) {
        // Android 10–12: the in-app language for everything built from the application context (notifications,
        // toasts, the bubble, fare-reading messages); Android 13+ uses the system per-app locale.
        super.attachBaseContext(app.ridemate.settings.LegacyLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        graph = createGraph()
        startPlatform(graph)
    }

    protected open fun createGraph(): AppGraph = AppGraph(this)

    protected open fun startPlatform(graph: AppGraph) = graph.start(ProcessLifecycleOwner.get())

    companion object {
        /** The graph of the running process, from any context. */
        fun graph(context: Context): AppGraph = (context.applicationContext as RideMateApp).graph
    }
}
