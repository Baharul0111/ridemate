package app.ridemate.dev

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ridemate.AppGraph
import app.ridemate.R
import app.ridemate.ui.SystemIntents
import app.ridemate.ui.findActivity
import app.ridemate.ui.rememberScopedModel
import java.io.File

@Composable
fun DevRoute(graph: AppGraph, onBack: () -> Unit) {
    val shell = graph.appShell
    val context = LocalContext.current
    val model = rememberScopedModel("dev") { scope ->
        DevModel(
            gate = shell.developerMode,
            recorderRecent = graph.db.recorderPostDao().observeLatest(RECENT_LIMIT),
            recorderAll = { graph.db.recorderPostDao().all() },
            recorderClear = { graph.db.recorderPostDao().clear() },
            golden = shell.goldenStore,
            writeText = shell.exports::writeText,
            exportFile = shell.exports::file,
            api = graph.apiClient,
            pushTokens = shell.ownerPushTokens,
            clock = graph.clock,
            build = graph.build,
            io = graph.dispatchers.io,
            scope = scope,
        )
    }
    val state by model.state.collectAsStateWithLifecycle()
    DevScreen(
        state = state,
        imageFile = shell.goldenStore::imageFile,
        actions = DevActions(
            back = onBack,
            setRecorderOn = model::setRecorderOn,
            exportRecorder = { model.exportRecorder { share(context, graph, it, "application/json") } },
            clearRecorder = { model.clearRecorder() },
            runCaptureTest = { context.findActivity()?.let { model.runCaptureTest(it, graph.featureBindings.captureTester) } },
            captureGolden = { context.findActivity()?.let { model.captureGolden(it, graph.featureBindings.goldenCaptures) } },
            saveGolden = { model.saveGolden(it) },
            removeGolden = { model.removeGolden(it) },
            exportGolden = { model.exportGolden { share(context, graph, it, "application/zip") } },
            loadBudget = { model.loadBudget() },
            setOwnerAlerts = { model.setOwnerAlerts(it) },
            disable = {
                model.disableDeveloperMode()
                onBack()
            },
            clearMessage = model::clearMessage,
        ),
    )
}

private const val RECENT_LIMIT = 50

/** Hands an export to the share sheet; the owner picks where it goes. */
private fun share(context: Context, graph: AppGraph, file: File, mimeType: String) {
    val intent = graph.appShell.exports.shareIntent(file, mimeType, context.getString(R.string.dev_share_title))
    if (!SystemIntents.startFirst(context, intent)) {
        Toast.makeText(context, context.getString(R.string.dev_msg_export_failed), Toast.LENGTH_LONG).show()
    }
}
