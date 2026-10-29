package app.ridemate.core.watch

import app.ridemate.core.config.NotificationTemplatesConfig
import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.Provider
import app.ridemate.core.state.DriverData
import app.ridemate.core.state.NotificationRemoved
import app.ridemate.core.state.ProviderUpdate
import app.ridemate.core.state.RideEvent
import app.ridemate.core.state.SignalSource
import app.ridemate.core.state.Statechart
import app.ridemate.core.state.UnknownReason
import app.ridemate.core.state.UnknownUpdate
import app.ridemate.core.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * RideWatch (§4.5). Every allowlisted post is masked and matched immediately (nothing on the local path is debounced);
 * template hits and redacted posts become RideEvents synchronously, rule misses go through the per-session cache and
 * then the classifier under a 10 s deadline with UNKNOWN_UPDATE as the fallback.
 *
 * Threading: all public methods and the classifier continuations must run on one confined dispatcher (the [scope]'s),
 * the same one the guardian is confined to.
 */
class RideWatchPipeline(
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val legs: LegDirectory,
    private val sink: RideEventSink,
    private val templates: () -> NotificationTemplatesConfig,
    private val classifier: NotificationClassifier? = null,
    private val masker: TextMasker = TextMasker.NONE,
    private val classifierDeadlineMs: Long = CLASSIFIER_DEADLINE_MS,
    /** The listener lives as long as the process and ride apps update posts every few seconds, so the logs are capped. */
    private val maxLogEntries: Int = MAX_LOG_ENTRIES,
) : OngoingNotifications {

    /** Demo packages are read only while a demo session is explicitly active; they never mix with live legs. */
    var demoSessionActive: Boolean = false

    var listenerConnected: Boolean = false
        private set

    /** I7: posts and removals from other packages, dropped before any field but the package name was read. */
    var nonAllowlistedDropped: Long = 0
        private set

    private val auditLog = ArrayDeque<PostAuditEntry>()
    private val removalLog = ArrayDeque<RemovalAuditEntry>()

    /** The most recent allowlisted posts (at most `maxLogEntries`), oldest first. */
    val audit: List<PostAuditEntry> get() = auditLog
    val removals: List<RemovalAuditEntry> get() = removalLog

    init {
        require(maxLogEntries > 0)
    }

    private var seq = 0L
    private val lastFingerprint = HashMap<String, PostFingerprint>()
    private val lastIdTag = HashMap<String, IdTag>()
    private val ongoing = HashMap<String, MutableSet<String>>()
    private val keyStates = HashMap<String, KeyState>()
    private val cache = HashMap<CacheKey, ClassifierLabel>()
    private var cacheSessionId: String? = null

    fun isAllowlisted(packageName: String): Boolean =
        packageName in Provider.RIDE_APP_PACKAGES || (demoSessionActive && packageName in DEMO_PACKAGES)

    fun onPosted(source: PostSource) {
        if (!isAllowlisted(source.packageName)) {
            nonAllowlistedDropped++
            return
        }
        process(source.read(), replayed = false)
    }

    fun onRemoved(source: RemovalSource) {
        if (!isAllowlisted(source.packageName)) {
            nonAllowlistedDropped++
            return
        }
        val removal = source.read()
        val wasOngoing = ongoing[removal.packageName]?.remove(removal.sbnKey) == true
        lastFingerprint.remove(removal.sbnKey)
        val leg = legs.legFor(removal.packageName)
        removalLog.addCapped(
            RemovalAuditEntry(removal.packageName, removal.sbnKey, removal.reason.code, wasOngoing, clock.elapsedMs(), leg?.legId),
        )
        if (leg != null) {
            sink.onRideEvent(
                leg.legId,
                NotificationRemoved(removal.sbnKey, removal.reason.code, removal.reason.isAppCancel, wasOngoing),
            )
        }
    }

    /**
     * §4.5.8 / I9: on (re)connect `getActiveNotifications()` is re-read and every allowlisted post is processed as new.
     * The app also calls this when a session starts, so posts that predate the session are seen.
     */
    fun onListenerConnected(active: List<PostSource>) {
        listenerConnected = true
        lastFingerprint.clear()
        ongoing.clear()
        active.filter { source ->
            isAllowlisted(source.packageName).also { allowed -> if (!allowed) nonAllowlistedDropped++ }
        }
            .map { it.read() }
            .sortedBy { it.postTimeMs }
            .forEach { process(it, replayed = true) }
    }

    /**
     * A new ride session starts. Exact-repeat dedupe (§4.5.9) is per session: the last posts of an earlier session must
     * not make an identical first post of this one look like a repeat — Try the demo posts the same words on the same
     * keys every run, and a ride app may reuse a key and its wording for the next ride. The demo apps' ongoing posts
     * ended with their session too (their shade is scripted); live apps' ongoing posts are still in the shade.
     */
    fun onSessionStarted() {
        lastFingerprint.clear()
        lastIdTag.clear()
        DEMO_PACKAGES.forEach { ongoing.remove(it) }
    }

    /** The app calls `requestRebind` after this; nothing is lost because reconnect re-reads every active post. */
    fun onListenerDisconnected() {
        listenerConnected = false
    }

    override fun hasOngoingRide(packageName: String): Boolean = ongoing[packageName]?.isNotEmpty() == true

    private fun process(post: PostSnapshot, replayed: Boolean) {
        val now = clock.elapsedMs()
        val pkg = post.packageName
        val key = post.sbnKey
        val pick = templates().entryFor(pkg, post.versionCode)
        val entry = pick?.entry
        val hit = entry?.let { TemplateEngine.match(it, post, lastIdTag[pkg]) }
        lastIdTag[pkg] = IdTag(post.id, post.tag)
        trackOngoing(post, entry?.ongoingChannelIds.orEmpty())

        val redacted = post.isRedacted
        val normalised = if (redacted) {
            StructuralFingerprint.of(post).render()
        } else {
            Normaliser.normalise(post.matchText, masker.spans(post.matchText))
        }
        val content = hit?.plateHash?.let { "$normalised|plate=$it" } ?: normalised
        val fingerprint = PostFingerprint(pkg, key, hit?.templateId ?: PostFingerprint.MISS, redacted, content)
        val leg = legs.legFor(pkg)
        val kind = when {
            hit != null && hit.structural -> PostKind.STRUCTURAL_HIT
            hit != null -> PostKind.TEMPLATE_HIT
            redacted -> PostKind.REDACTED
            else -> PostKind.RULE_MISS
        }
        val audit = PostAuditEntry(
            seq = ++seq,
            packageName = pkg,
            sbnKey = key,
            arrivedAtElapsedMs = now,
            kind = kind,
            templateId = hit?.templateId,
            templateFallback = pick?.isFallback == true,
            replayed = replayed,
            legId = leg?.legId,
            legStateAtArrival = leg?.state,
            legBookedAtArrival = leg?.isBooked == true,
        )
        auditLog.addCapped(audit)

        if (leg == null) {
            audit.resolve(PostOutcome.NO_ACTIVE_LEG, now)
            return
        }
        if (!replayed && lastFingerprint[key] == fingerprint) {
            audit.resolve(PostOutcome.DUPLICATE_DROPPED, now)
            // Same wording, new countdown: keep the driver's numbers fresh without a transition.
            if (hit != null && (hit.etaMin != null || hit.distanceMeters != null)) {
                sink.onRideEvent(leg.legId, DriverData(hit.etaMin, hit.distanceMeters, EtaSource.TEMPLATE))
            }
            return
        }
        lastFingerprint[key] = fingerprint

        when {
            hit != null -> resolveNow(audit, leg, key, hitEvent(hit, post, replayed))
            redacted -> resolveNow(audit, leg, key, UnknownUpdate(UnknownReason.REDACTED, key, post.postTimeMs, replayed))
            else -> ruleMiss(audit, post, leg, normalised, replayed)
        }
    }

    private fun ruleMiss(audit: PostAuditEntry, post: PostSnapshot, leg: LegView, normalised: String, replayed: Boolean) {
        val key = post.sbnKey
        val sessionId = legs.activeSessionId
        // §4.5.10 + cost: only booked, live legs of an active session are ever classified.
        if (sessionId == null || !leg.isBooked || leg.state.isTerminal) {
            return resolveNow(audit, leg, key, UnknownUpdate(UnknownReason.NOT_CLASSIFIED, key, post.postTimeMs, replayed))
        }
        if (leg.state !in Statechart.CLASSIFIABLE) {
            return resolveNow(audit, leg, key, UnknownUpdate(UnknownReason.LABEL_CANNOT_APPLY, key, post.postTimeMs, replayed))
        }
        if (sessionId != cacheSessionId) {
            cache.clear()
            cacheSessionId = sessionId
        }
        val cacheKey = CacheKey(post.packageName, post.versionCode, normalised)
        cache[cacheKey]?.let { label ->
            return resolveNow(audit, leg, key, label.toRideEvent(key, post.postTimeMs, replayed))
        }
        if (classifier == null) {
            return resolveNow(audit, leg, key, UnknownUpdate(UnknownReason.CLASSIFIER_UNAVAILABLE, key, post.postTimeMs, replayed))
        }
        val state = keyStates.getOrPut(key) { KeyState() }
        var deadline = clock.elapsedMs() + classifierDeadlineMs
        state.waiting?.let { older ->
            older.audit.markCoalesced(audit.seq)
            deadline = minOf(deadline, older.deadline)
        }
        state.waiting = Pending(post, audit, cacheKey, deadline, replayed, coalesced = state.waiting?.allAudits().orEmpty())
        if (state.inFlight == null) startNext(key, state)
    }

    private fun startNext(key: String, state: KeyState) {
        val pending = state.waiting ?: return
        state.waiting = null
        val leg = legs.legFor(pending.post.packageName)
        if (leg == null) {
            pending.resolveAll(PostOutcome.NO_ACTIVE_LEG, clock.elapsedMs())
            return
        }
        cache[pending.cacheKey]?.let { label ->
            return resolvePending(pending, leg, label.toRideEvent(key, pending.post.postTimeMs, pending.replayed))
        }
        if (leg.state !in Statechart.CLASSIFIABLE) {
            // The leg moved on while this post waited (e.g. a driver was assigned): no label could matter now.
            return resolvePending(pending, leg, UnknownUpdate(UnknownReason.LABEL_CANNOT_APPLY, key, pending.post.postTimeMs, pending.replayed))
        }
        val remaining = pending.deadline - clock.elapsedMs()
        if (remaining <= 0) {
            return resolvePending(pending, leg, UnknownUpdate(UnknownReason.CLASSIFIER_TIMEOUT, key, pending.post.postTimeMs, pending.replayed))
        }
        val activeClassifier = classifier ?: return
        val request = buildRequest(pending.post, leg)
        pending.audit.classifierCalled = true
        val flight = InFlight(pending)
        state.inFlight = flight
        flight.job = scope.launch {
            val result: Attempt = withTimeoutOrNull(remaining) {
                try {
                    Attempt.Done(activeClassifier.classify(request))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    Attempt.Failed
                }
            } ?: Attempt.TimedOut
            onClassified(key, state, flight, result)
        }
    }

    private fun onClassified(key: String, state: KeyState, flight: InFlight, attempt: Attempt) {
        if (state.inFlight === flight) state.inFlight = null
        val pending = flight.pending
        val label = ((attempt as? Attempt.Done)?.result as? ClassifierResult.Labelled)?.label
        if (label != null) cache[pending.cacheKey] = label
        if (!flight.superseded) {
            val post = pending.post
            val event = when {
                label != null -> label.toRideEvent(key, post.postTimeMs, pending.replayed)
                attempt is Attempt.Done -> UnknownUpdate(UnknownReason.CLASSIFIER_UNAVAILABLE, key, post.postTimeMs, pending.replayed)
                attempt is Attempt.TimedOut -> UnknownUpdate(UnknownReason.CLASSIFIER_TIMEOUT, key, post.postTimeMs, pending.replayed)
                else -> UnknownUpdate(UnknownReason.CLASSIFIER_FAILED, key, post.postTimeMs, pending.replayed)
            }
            val leg = legs.legFor(post.packageName)
            if (leg == null) pending.resolveAll(PostOutcome.NO_ACTIVE_LEG, clock.elapsedMs()) else resolvePending(pending, leg, event)
        }
        startNext(key, state)
    }

    /** A post resolved immediately also supersedes anything still waiting or in flight on the same key. */
    private fun resolveNow(audit: PostAuditEntry, leg: LegView, key: String, event: RideEvent) {
        val now = clock.elapsedMs()
        keyStates[key]?.let { state ->
            state.waiting?.let { waiting ->
                waiting.allAudits().forEach { it.resolve(PostOutcome.SUPERSEDED, now, coveredBySeq = audit.seq) }
                state.waiting = null
            }
            state.inFlight?.let { flight ->
                if (!flight.superseded) {
                    flight.superseded = true
                    flight.pending.allAudits().forEach { it.resolve(PostOutcome.SUPERSEDED, now, coveredBySeq = audit.seq) }
                }
            }
        }
        deliver(audit, leg, event)
    }

    private fun resolvePending(pending: Pending, leg: LegView, event: RideEvent) {
        deliver(pending.audit, leg, event)
        val now = clock.elapsedMs()
        pending.coalesced.forEach {
            it.resolve(PostOutcome.COALESCED, now, event.type, (event as? UnknownUpdate)?.reason, pending.audit.disposition, pending.audit.seq)
        }
    }

    private fun deliver(audit: PostAuditEntry, leg: LegView, event: RideEvent) {
        val disposition = sink.onRideEvent(leg.legId, event)
        audit.resolve(PostOutcome.EVENT, clock.elapsedMs(), event.type, (event as? UnknownUpdate)?.reason, disposition)
    }

    private fun hitEvent(hit: TemplateHit, post: PostSnapshot, replayed: Boolean) = ProviderUpdate(
        signal = hit.event,
        source = if (hit.structural) SignalSource.STRUCTURAL_TEMPLATE else SignalSource.TEMPLATE,
        sbnKey = post.sbnKey,
        templateId = hit.templateId,
        etaMin = hit.etaMin,
        distanceMeters = hit.distanceMeters,
        plateHash = hit.plateHash,
        postTimeWallMs = post.postTimeMs,
        replayed = replayed,
    )

    private fun buildRequest(post: PostSnapshot, leg: LegView): ClassifyRequest {
        fun masked(value: String?): String = value.orEmpty().let { Normaliser.maskForUpload(it, masker.spans(it)) }
        return ClassifyRequest(
            app = Provider.fromPackage(post.packageName)?.wireId ?: post.packageName,
            versionCode = post.versionCode,
            title = masked(post.title),
            text = masked(post.text),
            channelId = post.channelId,
            category = post.category,
            isOngoing = post.isOngoing,
            legState = leg.state,
        )
    }

    private fun trackOngoing(post: PostSnapshot, ongoingChannelIds: List<String>) {
        val keys = ongoing.getOrPut(post.packageName) { mutableSetOf() }
        val isRidePost = post.isOngoing || (post.channelId != null && post.channelId in ongoingChannelIds)
        if (isRidePost) keys += post.sbnKey else keys -= post.sbnKey
    }

    private fun <T> ArrayDeque<T>.addCapped(entry: T) {
        addLast(entry)
        while (size > maxLogEntries) removeFirst()
    }

    private data class CacheKey(val packageName: String, val versionCode: Long?, val normalised: String)

    private class Pending(
        val post: PostSnapshot,
        val audit: PostAuditEntry,
        val cacheKey: CacheKey,
        val deadline: Long,
        val replayed: Boolean,
        val coalesced: List<PostAuditEntry>,
    ) {
        fun allAudits(): List<PostAuditEntry> = coalesced + audit

        fun resolveAll(outcome: PostOutcome, at: Long) = allAudits().forEach { it.resolve(outcome, at) }
    }

    private class InFlight(val pending: Pending) {
        var job: Job? = null
        var superseded = false
    }

    private class KeyState {
        var waiting: Pending? = null
        var inFlight: InFlight? = null
    }

    private sealed interface Attempt {
        data class Done(val result: ClassifierResult) : Attempt
        data object Failed : Attempt
        data object TimedOut : Attempt
    }

    companion object {
        /** Day-1 spike (docs/spikes.md): reasoning cannot be switched off, so a classification takes 5–8 s. */
        const val CLASSIFIER_DEADLINE_MS = 10_000L
        const val MAX_LOG_ENTRIES = 5_000
        val DEMO_PACKAGES: Set<String> = Provider.DEMO_APPS.map { it.packageName }.toSet()
    }
}
