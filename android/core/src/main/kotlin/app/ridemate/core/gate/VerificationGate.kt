package app.ridemate.core.gate

import app.ridemate.core.fare.FareParser
import app.ridemate.core.fare.FareRead
import app.ridemate.core.fare.FareRow
import app.ridemate.core.format.Digits
import app.ridemate.core.format.DistanceText
import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.Provider
import app.ridemate.core.model.QuoteStatus
import app.ridemate.core.model.ScreenType
import app.ridemate.core.model.VehicleClass
import app.ridemate.core.ocr.Box
import app.ridemate.core.ocr.GRID
import app.ridemate.core.ocr.GridBox
import app.ridemate.core.ocr.OcrFrame
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * §4.3: no confident wrong number, ever. A row's price is VERIFIED only when (a) Channel A read the launched app,
 * (b) its price equals the parser's payable token and that token's centre lies in A's box ± 4% of the frame,
 * (c) the labels match and map to the same vehicle class, (d) both frames show the same digits, and (e) the value is
 * inside the sanity bands. (f) An ETA is shown only on a VERIFIED row whose parser ETA token equals A's in both frames.
 * Driver ETA and distance must equal the parser's anchor-selected tokens, else "see in <app>".
 *
 * A options are paired with parser rows by position: each pair needs the row's payable centre inside the option's
 * box ± 4%, and the closest pairs are taken first, one-to-one.
 */
object VerificationGate {
    /** The phone deadline of one "Read fares" tap (§4.12). */
    const val TAP_BUDGET_MS: Long = 20_000

    /** A re-read is attempted only with at least this much of the tap budget left (§4.3.5). */
    const val MIN_REMAINING_FOR_REREAD_MS: Long = 6_000

    /** § 4.3 (b) tolerance: 4% of the frame, in 0–1000 grid units. */
    const val POSITION_TOLERANCE: Double = 0.04 * GRID

    fun verify(
        launchedApp: Provider,
        read: ChannelARead?,
        frame1: OcrFrame,
        frame2: OcrFrame,
        parser: FareParser,
        bands: SanityBands,
        tripKm: Double?,
        nowMs: Long,
    ): GateResult {
        val ctx = GateContext(launchedApp, read?.truncated(), parser.parse(frame1), parser.parse(frame2), parser, bands, tripKm, nowMs)
        return ctx.result(ctx.pairUp(), rereadApplied = false)
    }

    /**
     * Which rows to re-read in one `/v1/reread` call: rows where the two channels disagree in a way another look
     * can settle (price, label, position, missing option, or only the ETA), at most [MAX_REREAD_CROPS] of them, top
     * first, so a screen full of rows can't make one paid call arbitrarily large. Null when there is nothing to
     * re-read, a re-read already happened, the first call timed out, A read another app, or under 6 s of the 20 s
     * tap budget remain.
     */
    fun planReread(result: GateResult, tapAtMs: Long, nowMs: Long, firstCallTimedOut: Boolean): RereadPlan? {
        val ctx = result.context
        if (result.rereadApplied || firstCallTimedOut || ctx.read == null || !ctx.appOk) return null
        if (TAP_BUDGET_MS - (nowMs - tapAtMs) < MIN_REMAINING_FOR_REREAD_MS) return null
        val crops = rereadRows(result).map { ctx.cropOf(it.row!!) }
        return if (crops.isEmpty()) null else RereadPlan(ctx.app, crops)
    }

    /** The same cap as Channel A's options (contracts §1.3): rows beyond it stay "Couldn't read". */
    const val MAX_REREAD_CROPS: Int = ChannelARead.MAX_OPTIONS

    /**
     * Re-evaluates the re-read rows with the re-read values; rows still disagreeing (or missing from [response])
     * end UNREADABLE with no number. A row whose price was already VERIFIED can only gain its ETA here. Answers for
     * rows [planReread] didn't send a crop for are ignored.
     */
    fun applyReread(result: GateResult, response: RereadResponse): GateResult {
        check(!result.rereadApplied) { "one re-read per tap" }
        val ctx = result.context
        val planned = rereadRows(result).mapTo(HashSet()) { it.rowId }
        val counts = response.rows.groupingBy { it.rowId }.eachCount()
        val byId = response.rows.filter { counts[it.rowId] == 1 }.associateBy { it.rowId }
        val updated = result.evaluations.map { e ->
            if (e.rowId !in planned) return@map e
            val reread = byId[e.rowId] ?: return@map e
            val row = e.row!!
            val option = OptionView(
                reread.vehicleClass, reread.labelText, reread.priceMin, reread.priceMax, reread.etaMin, ctx.cropOf(row).grid,
            )
            if (e.failures.isEmpty()) e.copy(eta = ctx.verifiedEta(row, option)) else ctx.evaluate(e.rowId, row, option)
        }
        return ctx.result(updated, rereadApplied = true)
    }

    private fun rereadRows(result: GateResult): List<Evaluation> =
        result.evaluations.filter { result.context.rereadable(it) }.take(MAX_REREAD_CROPS)
}

/** The outcome of one tap: rows for the comparison list, the driver reading, and what's needed for a re-read. */
class GateResult internal constructor(
    val provider: Provider,
    val rows: List<GatedRow>,
    val driver: DriverReading,
    /** Channel B's guess of the screen (from `screen_keywords`), for routing; never shown. */
    val screenType: ScreenType,
    val rereadApplied: Boolean,
    internal val context: GateContext,
    internal val evaluations: List<Evaluation>,
) {
    val verifiedCount: Int get() = rows.count { it.status == QuoteStatus.VERIFIED }
    val unreadableCount: Int get() = rows.count { it.status == QuoteStatus.UNREADABLE }
}

/** One row crop for `/v1/reread`: pixels to cut from the masked frame, and the same box on the 0–1000 grid. */
data class RereadCrop(val rowId: String, val box: Box, val grid: GridBox)

data class RereadPlan(val app: Provider, val crops: List<RereadCrop>) {
    val meta: RereadMeta get() = RereadMeta(app.wireId, crops.map { RereadRowRef(it.rowId) })
}

internal data class OptionView(
    val vehicleClass: VehicleClass?,
    val labelText: String?,
    val priceMin: Int?,
    val priceMax: Int?,
    val etaMin: Int?,
    val grid: GridBox?,
) {
    companion object {
        fun of(o: ChannelAOption) = OptionView(o.vehicleClass, o.labelText, o.priceMin, o.priceMax, o.etaMin, o.gridBox)
    }
}

internal data class Evaluation(
    val rowId: String,
    val row: FareRow?,
    val option: OptionView?,
    val failures: Set<GateFailure>,
    val eta: Int?,
)

internal class GateContext(
    val app: Provider,
    val read: ChannelARead?,
    private val first: FareRead,
    private val second: FareRead,
    private val parser: FareParser,
    private val bands: SanityBands,
    private val tripKm: Double?,
    private val seenAtMs: Long,
) {
    val appOk: Boolean = read != null && read.app == app

    fun pairUp(): List<Evaluation> {
        val options = read?.options.orEmpty().map(OptionView::of)
        val rows = first.rows
        data class Candidate(val row: Int, val option: Int, val outside: Double, val centre: Double)
        val candidates = mutableListOf<Candidate>()
        for (r in rows.indices) {
            val (x, y) = anchorOf(rows[r])
            for (o in options.indices) {
                val box = options[o].grid ?: continue
                if (!box.expand(VerificationGate.POSITION_TOLERANCE).contains(x, y)) continue
                candidates += Candidate(r, o, box.distanceTo(x, y), hypot(box.centerX - x, box.centerY - y))
            }
        }
        val rowToOption = HashMap<Int, Int>()
        val taken = HashSet<Int>()
        for (c in candidates.sortedWith(compareBy({ it.outside }, { it.centre }))) {
            if (c.row in rowToOption || c.option in taken) continue
            rowToOption[c.row] = c.option
            taken += c.option
        }
        val paired = rows.mapIndexed { r, row -> evaluate("r${row.index}", row, rowToOption[r]?.let(options::get)) }
        val unpaired = if (appOk) {
            options.indices.filter { it !in taken }.map { o -> evaluate("a$o", null, options[o]) }
        } else {
            emptyList()
        }
        return paired + unpaired
    }

    fun evaluate(rowId: String, row: FareRow?, option: OptionView?): Evaluation {
        val f = mutableSetOf<GateFailure>()
        when {
            read == null -> f += GateFailure.MODEL_UNAVAILABLE
            !appOk -> f += GateFailure.APP_MISMATCH
        }
        if (row == null) return Evaluation(rowId, null, option, f + GateFailure.NO_PARSER_ROW, eta = null)
        val price = row.price
        if (option == null) {
            if (read != null) f += GateFailure.NO_MODEL_OPTION
            if (price == null) f += GateFailure.PRICE_MISSING
        } else {
            if (price == null || option.priceMin == null || option.priceMax == null) {
                f += GateFailure.PRICE_MISSING
            } else if (option.priceMin != price.amount.min || option.priceMax != price.amount.max) {
                f += GateFailure.PRICE_MISMATCH
            }
            if (price != null) {
                val (x, y) = gridPoint(price.box)
                if (option.grid?.expand(VerificationGate.POSITION_TOLERANCE)?.contains(x, y) != true) f += GateFailure.POSITION_MISMATCH
            }
            if (!labelsAgree(row, option)) f += GateFailure.LABEL_MISMATCH
        }
        if (price != null) {
            if (twinOf(row)?.price?.amount?.range != price.amount.range) f += GateFailure.FRAMES_DIFFER
            if (!bands.priceOk(price.amount.range, row.vehicleClass ?: VehicleClass.OTHER, tripKm)) f += GateFailure.OUT_OF_BANDS
        }
        val eta = if (f.isEmpty()) verifiedEta(row, option!!) else null
        return Evaluation(rowId, row, option, f, eta)
    }

    /** (f): A's ETA equals the parser's ETA token for the row in both frames, and is inside the band. */
    fun verifiedEta(row: FareRow, option: OptionView): Int? {
        val eta = option.etaMin ?: return null
        val ok = row.eta?.value == eta && twinOf(row)?.eta?.value == eta && bands.etaOk(eta)
        return if (ok) eta else null
    }

    fun rereadable(e: Evaluation): Boolean {
        val row = e.row ?: return false
        if (row.price == null || row.label == null) return false
        if (e.failures.isEmpty()) return e.eta == null && row.eta != null
        return e.failures.all { it in FIXABLE_BY_REREAD }
    }

    fun cropOf(row: FareRow): RereadCrop {
        val box = row.box.expand(
            (0.02 * first.frameWidth).toInt(), max(4, row.box.height / 4), first.frameWidth, first.frameHeight,
        )
        return RereadCrop("r${row.index}", box, box.toGrid(first.frameWidth, first.frameHeight))
    }

    fun result(evaluations: List<Evaluation>, rereadApplied: Boolean): GateResult {
        val (parserRows, modelOnly) = evaluations.mapNotNull { e -> toGatedRow(e)?.let { e to it } }.partition { it.first.row != null }
        // An option only Channel A saw is listed only for a class the parser has no row for (else it is a duplicate).
        val covered = parserRows.mapTo(HashSet()) { it.second.vehicleClass }
        return GateResult(
            provider = app,
            rows = parserRows.map { it.second } + modelOnly.map { it.second }.filter { it.vehicleClass !in covered },
            driver = driver(),
            screenType = first.screenType,
            rereadApplied = rereadApplied,
            context = this,
            evaluations = evaluations,
        )
    }

    private fun toGatedRow(e: Evaluation): GatedRow? {
        val row = e.row
        if (row != null && e.option == null) {
            // Parser-only lines: keep real fare rows A missed, drop labels without prices and prices without labels.
            if (row.label == null || (row.price == null && row.ignoredPrices.isEmpty())) return null
        }
        val vehicleClass = row?.vehicleClass ?: e.option?.vehicleClass ?: return null
        val label = row?.label
        val verified = e.failures.isEmpty()
        return GatedRow(
            provider = app,
            vehicleClass = vehicleClass,
            displayLabel = label?.text ?: VehicleClassNames.english(vehicleClass),
            labelFromOcr = label != null,
            price = if (verified) row!!.price!!.amount.range else null,
            status = if (verified) QuoteStatus.VERIFIED else QuoteStatus.UNREADABLE,
            eta = e.eta,
            etaSource = if (e.eta != null) EtaSource.VERIFIED else EtaSource.NONE,
            seenAtMs = seenAtMs,
            rowId = e.rowId,
            failures = e.failures,
        )
    }

    private fun driver(): DriverReading {
        if (!appOk) return DriverReading.NONE
        val eta = read!!.driverEtaMin?.takeIf {
            it == first.driverEta?.value && it == second.driverEta?.value && bands.etaOk(it)
        }
        val metres = DistanceText.parseMeters(read.driverDistanceText)?.takeIf {
            it == first.driverDistance?.value && it == second.driverDistance?.value
        }
        return DriverReading(eta, if (eta != null) EtaSource.VERIFIED else EtaSource.NONE, metres)
    }

    /** (c): same class on both sides, A's label maps to it under the app's rules, and the label texts match. */
    private fun labelsAgree(row: FareRow, option: OptionView): Boolean {
        val label = row.label ?: return false
        return option.vehicleClass == label.vehicleClass &&
            parser.classifyLabel(option.labelText) == label.vehicleClass &&
            labelTextsMatch(option.labelText, label.text)
    }

    /** The same row in the second frame: same class, payable centre within the position tolerance. */
    private fun twinOf(row: FareRow): FareRow? {
        val (x, y) = anchorOf(row)
        return second.rows.filter { it.vehicleClass == row.vehicleClass }
            .map { it to anchorOf(it) }
            .filter { (_, p) -> abs(p.first - x) <= VerificationGate.POSITION_TOLERANCE && abs(p.second - y) <= VerificationGate.POSITION_TOLERANCE }
            .minByOrNull { (_, p) -> hypot(p.first - x, p.second - y) }?.first
    }

    private fun anchorOf(row: FareRow): Pair<Double, Double> = gridPoint(row.price?.box ?: row.label?.box ?: row.box)

    private fun gridPoint(box: Box): Pair<Double, Double> =
        box.centerX * GRID / first.frameWidth to box.centerY * GRID / first.frameHeight

    private companion object {
        val FIXABLE_BY_REREAD = setOf(
            GateFailure.NO_MODEL_OPTION, GateFailure.PRICE_MISSING, GateFailure.PRICE_MISMATCH,
            GateFailure.POSITION_MISMATCH, GateFailure.LABEL_MISMATCH,
        )
    }
}

/**
 * Label texts match when, after lower-casing and dropping punctuation, they are equal or one contains the other as
 * whole words with at most [MAX_EXTRA_LABEL_WORDS] extra words (`Uber Go` vs `Uber Go Sedan`).
 */
internal fun labelTextsMatch(a: String?, b: String?): Boolean {
    val x = normalizeLabel(a ?: return false)
    val y = normalizeLabel(b ?: return false)
    if (x.isEmpty() || y.isEmpty()) return false
    if (x == y) return true
    val (short, long) = if (x.length <= y.length) x to y else y to x
    val extra = long.split(' ').size - short.split(' ').size
    return extra <= MAX_EXTRA_LABEL_WORDS && " $long ".contains(" $short ")
}

private const val MAX_EXTRA_LABEL_WORDS = 2
private val NON_WORD = Regex("[^\\p{L}\\p{M}\\p{Nd}]+")

private fun normalizeLabel(text: String): String = Digits.toAscii(text).lowercase().replace(NON_WORD, " ").trim()
