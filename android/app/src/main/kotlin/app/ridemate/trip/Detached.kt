package app.ridemate.trip

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** [block] ended with its own cancellation (e.g. its internal time limit) while the caller was still waiting. */
internal class DetachedWorkCancelled(cause: CancellationException) : IllegalStateException("detached work was cancelled", cause)

/**
 * Runs [block] detached on [dispatcher] and waits for its result like a normal suspend call, so a caller's time limit
 * always holds. With `withContext`, a call that ignores cancellation (the blocking Geocoder below Android 13, a binder
 * call, a ping that blocks its thread) keeps its caller waiting until it returns; here the caller resumes as soon as
 * it is cancelled, and the stuck work is cancelled and its late result discarded.
 *
 * Exceptions from [block] are rethrown. A [CancellationException] that [block] raised by itself while the caller is
 * still active is rethrown as [DetachedWorkCancelled], so it can't be mistaken for the caller's own cancellation.
 */
internal suspend fun <T> awaitDetached(dispatcher: CoroutineDispatcher, block: suspend CoroutineScope.() -> T): T {
    val work = CoroutineScope(dispatcher + SupervisorJob()).async(block = block)
    try {
        return work.await()
    } catch (e: CancellationException) {
        work.cancel()
        currentCoroutineContext().ensureActive()
        throw DetachedWorkCancelled(e)
    }
}
