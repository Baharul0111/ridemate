package app.ridemate.demo

import app.ridemate.core.model.Provider
import app.ridemate.core.watch.PostRemoval
import app.ridemate.core.watch.PostSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where the scripted timeline goes: RideWatch (posts, removals) and the demo "apps" (their current screen). */
interface DemoEventSink {
    fun post(event: DemoEvent, snapshot: PostSnapshot)
    fun remove(event: DemoEvent, removal: PostRemoval)
    fun screenChanged(provider: Provider, screen: String)
}

/**
 * The scripted event source of Try the demo (eval/demo/README.md). Each event waits for its `requires_user_step`; the
 * first time the user completes that step, every event anchored on it fires `t_ms` later, in script order. Later
 * repeats of a step anchor nothing (each event fires once).
 *
 * Tracks branch on the user's answers: the session starts on the chosen track, and when the user takes a step the
 * active track has no events for, the source moves to another track over the same providers that scripts this step and
 * agrees with everything already played (for every step taken so far, it scripts either nothing or the same posts).
 * So "Yes, cancel the others" with keep searching on continues on the main track once Tamarind's cancel is tapped, and
 * "Not now" with it off continues on the keep-searching track (a second driver, then "pick one"). Already-scheduled
 * events are never replayed.
 *
 * Confinement: call from the [scope]'s dispatcher; [sink] is called there too.
 */
class DemoEventSource(
    private val script: DemoScript,
    initialTrack: DemoTrack,
    private val scope: CoroutineScope,
    private val wallClock: () -> Long,
    private val sink: DemoEventSink,
) {
    var track: DemoTrack = initialTrack
        private set

    private val anchored = LinkedHashSet<String>()

    /** The events each step scheduled (possibly none), whichever track was active when it was taken. */
    private val played = HashMap<String, List<DemoEvent>>()
    private val jobs = mutableListOf<Job>()
    private val fired = mutableListOf<String>()

    /** Ids of the events fired so far, in order. */
    val firedEventIds: List<String> get() = fired

    /** Steps taken so far (their wire form), in order. */
    val stepsTaken: Set<String> get() = anchored

    /** Records [step]; returns true when it was new and its events (if any) were scheduled. */
    fun onUserStep(step: DemoUserStep): Boolean {
        if (!anchored.add(step.wire)) return false
        if (track.eventsFor(step).isEmpty()) branchFor(step)?.let { track = it }
        val events = track.eventsFor(step)
        played[step.wire] = events
        if (events.isEmpty()) return true
        jobs += scope.launch {
            var elapsed = 0L
            for (event in events) {
                val wait = event.tMs - elapsed
                if (wait > 0) delay(wait)
                elapsed = event.tMs
                fire(event)
            }
        }
        return true
    }

    /** Stops every pending event (the demo was closed). */
    fun cancel() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    private fun branchFor(step: DemoUserStep): DemoTrack? {
        val taken = anchored.filter { it != step.wire }.mapNotNull(DemoUserStep::parse)
        return script.tracks.firstOrNull { candidate ->
            candidate !== track &&
                candidate.providers == track.providers &&
                candidate.eventsFor(step).isNotEmpty() &&
                taken.all { s -> candidate.eventsFor(s).isEmpty() || sameEvents(candidate.eventsFor(s), played[s.wire].orEmpty()) }
        }
    }

    private fun sameEvents(a: List<DemoEvent>, b: List<DemoEvent>): Boolean =
        a.size == b.size && a.zip(b).all { (x, y) -> x.sameContentAs(y) }

    private fun fire(event: DemoEvent) {
        fired += event.id
        val spec = script.providers.getValue(event.packageName)
        when (event.action) {
            DemoAction.POST -> sink.post(event, event.toSnapshot(wallClock(), spec.versionCode))
            DemoAction.REMOVE -> sink.remove(event, event.toRemoval())
        }
        event.screen?.let { sink.screenChanged(event.provider, it) }
    }
}
