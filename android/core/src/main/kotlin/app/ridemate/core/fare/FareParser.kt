package app.ridemate.core.fare

import app.ridemate.core.config.FareParserEntry
import app.ridemate.core.config.FareParsersConfig
import app.ridemate.core.format.CurrencyContext
import app.ridemate.core.format.Digits
import app.ridemate.core.format.DistanceText
import app.ridemate.core.format.RupeeParser
import app.ridemate.core.format.normalizeSpaces
import app.ridemate.core.model.Provider
import app.ridemate.core.model.ScreenType
import app.ridemate.core.model.VehicleClass
import app.ridemate.core.ocr.Box
import app.ridemate.core.ocr.Layout
import app.ridemate.core.ocr.OcrFrame
import app.ridemate.core.ocr.TextLine
import com.google.re2j.Matcher
import com.google.re2j.Pattern
import kotlin.math.abs

/**
 * Channel B (§4.2 FareParser): deterministic per-app rules over OCR tokens. Every config regex runs under RE2/J.
 *
 * Rows are built from text lines: lines that overlap vertically form one visual line. A new row starts when the
 * blank gap above a visual line exceeds (`row_gap_ratio` − 1) × the median line height (for lines of median
 * height, centres more than `row_gap_ratio` line heights apart; measuring the gap keeps a tall price from pushing
 * its own struck price into the next row), or when a line starting with a vehicle label follows a row that already
 * has a label or a price.
 *
 * In each row the payable price is the tallest ₹ token, then the rightmost. A ₹ token shorter than
 * `strike_height_ratio` × the row's tallest is treated as struck through, as is one crossed by a strike marker
 * token or written with combining strokes; neither is ever selected.
 *
 * Price column (real ML Kit output, `eval/recorded/ocr`): the phone's OCR often drops the ₹ glyph or reads it as
 * another glyph, so a fare screen can carry no ₹ token at all. A labelled row with no ₹ payable may then take a bare
 * amount ([CurrencyContext.PRICE_COLUMN]) from the right half of the frame, but only when its right edge lines up
 * (± 3% of the frame width) with a price or bare amount of another labelled row, and, when other rows have ₹
 * payables, only at their height (± 15%). Amounts on the label's own visual line come first (struck prices sit on
 * the line below), then the tallest, then the rightmost. A lone bare number is never a price.
 */
class FareParser(val entry: FareParserEntry) {
    private val labelRules: List<Pair<Pattern, VehicleClass>> =
        entry.vehicleLabels.map { compile(it.pattern) to it.vehicleClass }
    private val pricePattern = compile(entry.priceTokenPattern)
    private val etaPattern = compile(entry.etaPattern)
    private val distancePattern = compile(entry.driverDistancePattern)
    private val anchors = entry.driverEtaAnchors.map { it.lowercase() }
    private val keywords: Map<ScreenType, List<String>> =
        entry.keywordsByScreen.mapValues { (_, words) -> words.map { it.lowercase() } }

    fun parse(frame: OcrFrame): FareRead = Analysis(frame).read()

    /** K3: true when the frame has ≥ 1 ₹/Rs token or one of this app's status keywords; else don't upload. */
    fun looksLikeFareScreen(frame: OcrFrame): Boolean = Analysis(frame).looksLikeFareScreen()

    /** The vehicle class the first matching `vehicle_labels` rule gives [text] (used by the gate on model labels). */
    fun classifyLabel(text: String?): VehicleClass? {
        if (text.isNullOrBlank()) return null
        val normalized = Digits.toAscii(normalizeSpaces(text))
        return labelRules.firstOrNull { (pattern, _) -> pattern.matcher(normalized).find() }?.second
    }

    private inner class Analysis(private val frame: OcrFrame) {
        private val lines: List<TextLine> = Layout.textLines(frame)
        private val docs = lines.map { Layout.document(frame, listOf(it)) }
        private val texts = docs.map { Digits.toAscii(normalizeSpaces(it.text)) }
        private val refHeight: Double = median(lines.map { it.box.height.toDouble() }) ?: 1.0
        private val pieces: List<List<Piece>> = lines.map(::piecesOf)
        private val candidates: List<List<Candidate>> = pieces.map { line -> line.mapNotNull(::priceOf) }
        private val priceTokenIds: Set<Int> = candidates.flatten().flatMapTo(mutableSetOf()) { it.price.tokenIds }
        private val markers: List<List<Box>> = lines.map { line ->
            line.tokenIds.filter { it !in priceTokenIds && STRIKE_MARKER.matches(frame.tokens[it].text.trim()) }
                .map { frame.tokens[it].box }
        }
        private val labels: List<RowLabel?> = lines.indices.map(::labelOf)

        fun read(): FareRead {
            val groups = rowGroups()
            val firstPass = groups.map { choosePayable(it.flatMap { i -> candidates[i] }, it.flatMap { i -> markers[i] }) }
            val withTwo = groups.mapIndexed { g, lineIdx -> withLeadingTwo(g, lineIdx, firstPass) }
            val rows = groups.mapIndexedNotNull { g, lineIdx ->
                val chosen = withPriceColumn(groups, g, withTwo)
                val label = lineIdx.firstNotNullOfOrNull { labels[it] }
                if (label == null && chosen.first == null && chosen.second.isEmpty()) return@mapIndexedNotNull null
                val ids = lineIdx.flatMap { lines[it].tokenIds }
                FareRow(
                    index = 0,
                    box = Box.unionOf(lineIdx.map { lines[it].box }),
                    tokenIds = ids,
                    label = label,
                    price = chosen.first,
                    ignoredPrices = chosen.second,
                    eta = uniqueValue(lineIdx, etaPattern, ::minutesOf),
                )
            }.mapIndexed { i, row -> row.copy(index = i) }
            val anchorLines = anchorNeighbourhood()
            return FareRead(
                frameWidth = frame.width,
                frameHeight = frame.height,
                rows = rows,
                screenType = screenType(rows),
                driverEta = uniqueValue(anchorLines, etaPattern, ::minutesOf),
                driverDistance = uniqueValue(anchorLines, distancePattern, ::metresOf),
                looksLikeFareScreen = looksLikeFareScreen(),
            )
        }

        fun looksLikeFareScreen(): Boolean = candidates.any { it.isNotEmpty() } || keywordHits().values.any { it > 0 }

        /** Groups line indices into rows (see the class comment). */
        private fun rowGroups(): List<List<Int>> {
            val visual = visualLines()
            val rows = mutableListOf<MutableList<List<Int>>>()
            val maxGap = (entry.rowGapRatio - 1).coerceAtLeast(0.0) * refHeight
            var previous: Box? = null
            for (v in visual) {
                val box = Box.unionOf(v.map { lines[it].box })
                val current = rows.lastOrNull()
                val farBelow = previous != null && box.t - previous.b > maxGap
                val newOption = current != null && startsWithLabel(v) &&
                    current.flatten().any { labels[it] != null || candidates[it].isNotEmpty() }
                if (current == null || farBelow || newOption) rows += mutableListOf(v) else current += v
                previous = box
            }
            return rows.map { row -> row.flatten().sortedWith(compareBy({ lines[it].box.t }, { lines[it].box.l })) }
        }

        /** Line indices merged into visual lines (vertical overlap ≥ half the shorter line), top to bottom. */
        private fun visualLines(): List<List<Int>> {
            val parent = IntArray(lines.size) { it }
            fun find(i: Int): Int {
                var x = i
                while (parent[x] != x) x = parent[x]
                return x
            }
            for (i in lines.indices) for (j in i + 1 until lines.size) {
                val a = lines[i].box
                val b = lines[j].box
                if (a.verticalOverlap(b) >= 0.5 * minOf(a.height, b.height).coerceAtLeast(1)) parent[find(j)] = find(i)
            }
            return lines.indices.groupBy(::find).values
                .sortedBy { group -> Box.unionOf(group.map { lines[it].box }).centerY }
        }

        private fun startsWithLabel(visualLine: List<Int>): Boolean {
            val first = visualLine.flatMap { lines[it].tokenIds }.minByOrNull { frame.tokens[it].box.l } ?: return false
            return visualLine.any { labels[it]?.tokenIds?.contains(first) == true }
        }

        /**
         * A row whose ₹ token is missing or only struck-sized may hold ML Kit's `₹110` → `2110` misread. A leading
         * `2` becomes ₹ only for a token right-aligned with the other rows' payable prices (± 3% of the frame width)
         * and of their height (± 15%); without that context `2110` stays 2110 and is not a price.
         */
        private fun withLeadingTwo(
            group: Int,
            lineIdx: List<Int>,
            firstPass: List<Pair<PriceToken?, List<IgnoredPrice>>>,
        ): Pair<PriceToken?, List<IgnoredPrice>> {
            val own = firstPass[group]
            val others = firstPass.filterIndexed { i, _ -> i != group }.mapNotNull { it.first }
            if (others.isEmpty()) return own
            val columnRight = median(others.map { it.box.r.toDouble() })!!
            val priceHeight = median(others.map { it.box.height.toDouble() })!!
            if (own.first != null && own.first!!.box.height >= LEADING_TWO_HEIGHT.start * priceHeight) return own
            val extra = lineIdx.flatMap { pieces[it] }.filter { piece ->
                piece.ids.none { it in priceTokenIds } &&
                    abs(piece.box.r - columnRight) <= LEADING_TWO_COLUMN * frame.width &&
                    piece.box.height / priceHeight in LEADING_TWO_HEIGHT
            }.mapNotNull(::leadingTwoOf)
            if (extra.isEmpty()) return own
            return choosePayable(lineIdx.flatMap { candidates[it] } + extra, lineIdx.flatMap { markers[it] })
        }

        /**
         * A labelled row still without a payable: a bare amount from the price column (see the class comment), or
         * the row as it was. The bare amounts it passes over are kept as ignored prices.
         */
        private fun withPriceColumn(
            groups: List<List<Int>>,
            group: Int,
            chosen: List<Pair<PriceToken?, List<IgnoredPrice>>>,
        ): Pair<PriceToken?, List<IgnoredPrice>> {
            val own = chosen[group]
            if (own.first != null) return own
            val label = groups[group].firstNotNullOfOrNull { labels[it] } ?: return own
            val mine = columnCandidates(groups[group])
            if (mine.isEmpty()) return own
            val marked = chosen.filterIndexed { i, _ -> i != group }.mapNotNull { it.first }
            val markedHeight = median(marked.map { it.box.height.toDouble() })
            val partners = marked.map { it.box } + groups.indices.filter { h ->
                h != group && chosen[h].first == null && groups[h].any { labels[it] != null }
            }.flatMap { h -> columnCandidates(groups[h]).map { it.price.box } }
            val inColumn = mine.filter { c ->
                partners.any { abs(it.r - c.price.box.r) <= LEADING_TWO_COLUMN * frame.width } &&
                    (markedHeight == null || c.price.box.height / markedHeight in LEADING_TWO_HEIGHT)
            }
            if (inColumn.isEmpty()) return own
            val onLabelLine = inColumn.filter {
                it.price.box.verticalOverlap(label.box) >= 0.5 * minOf(it.price.box.height, label.box.height).coerceAtLeast(1)
            }
            val pool = onLabelLine.ifEmpty { inColumn }
            val (payable, ignored) = choosePayable(pool, groups[group].flatMap { markers[it] })
            val passedOver = (inColumn - pool.toSet()).map { IgnoredPrice(it.price, IgnoredReason.NOT_SELECTED) }
            return payable to (own.second + ignored + passedOver)
        }

        /** Bare amounts (no ₹ token) in the right half of the frame on [lineIdx], read in the price-column context. */
        private fun columnCandidates(lineIdx: List<Int>): List<Candidate> = lineIdx.flatMap { pieces[it] }.mapNotNull { piece ->
            if (piece.ids.any { it in priceTokenIds } || piece.box.l < frame.width / 2) return@mapNotNull null
            val text = Digits.toAscii(normalizeSpaces(piece.text.trim()))
            if (!BARE_AMOUNT.matches(text)) return@mapNotNull null
            val amount = RupeeParser.parse(text, CurrencyContext.PRICE_COLUMN) ?: return@mapNotNull null
            Candidate(PriceToken(amount, piece.ids, piece.box, piece.text), struckByText = false)
        }

        private fun choosePayable(found: List<Candidate>, strikeMarks: List<Box>): Pair<PriceToken?, List<IgnoredPrice>> {
            val ignored = mutableListOf<IgnoredPrice>()
            val (marked, clean) = found.partition { it.struckByText || strikeMarks.any { m -> crosses(m, it.price.box) } }
            marked.forEach { ignored += IgnoredPrice(it.price, IgnoredReason.STRIKE_MARKER) }
            if (clean.isEmpty()) return null to ignored
            val tallest = clean.maxOf { it.price.box.height }
            val (tall, short) = clean.partition { it.price.box.height >= entry.strikeHeightRatio * tallest }
            short.forEach { ignored += IgnoredPrice(it.price, IgnoredReason.SHORTER_THAN_PAYABLE) }
            val payable = tall.filter { it.price.box.height >= (1 - TALLEST_TIE) * tallest }
                .maxWith(compareBy({ it.price.box.r }, { it.price.box.b })).price
            tall.filter { it.price !== payable }.forEach { ignored += IgnoredPrice(it.price, IgnoredReason.NOT_SELECTED) }
            return payable to ignored
        }

        private fun labelOf(line: Int): RowLabel? {
            for ((pattern, vehicleClass) in labelRules) {
                val m = pattern.matcher(texts[line])
                if (!m.find()) continue
                val ids = docs[line].tokensIn(m.start(), m.end()).filter { it !in priceTokenIds }
                if (ids.isEmpty()) continue
                return RowLabel(
                    text = ids.joinToString(" ") { frame.tokens[it].text.trim() },
                    vehicleClass = vehicleClass,
                    tokenIds = ids,
                    box = Box.unionOf(ids.map { frame.tokens[it].box }),
                )
            }
            return null
        }

        /** The single value [pattern] yields across [lineIdx] (ignoring price tokens); null if none or several. */
        private fun uniqueValue(lineIdx: List<Int>, pattern: Pattern, value: (Matcher) -> Int?): TokenValue? {
            val found = mutableListOf<TokenValue>()
            for (line in lineIdx) {
                val m = pattern.matcher(texts[line])
                while (m.find()) {
                    if (m.end() == m.start()) break
                    val v = value(m) ?: continue
                    val ids = docs[line].tokensIn(m.start(), m.end()).filter { it !in priceTokenIds }
                    if (ids.isNotEmpty()) found += TokenValue(v, ids, Box.unionOf(ids.map { frame.tokens[it].box }))
                }
            }
            return found.singleOrNull() ?: found.takeIf { list -> list.isNotEmpty() && list.all { it.value == list[0].value } }
                ?.let { list -> TokenValue(list[0].value, list.flatMap { it.tokenIds }.distinct(), Box.unionOf(list.map { it.box })) }
        }

        /** Lines with a driver-ETA anchor word, plus the lines directly above or below them. */
        private fun anchorNeighbourhood(): List<Int> {
            if (anchors.isEmpty()) return emptyList()
            val anchored = lines.indices.filter { i -> val t = texts[i].lowercase(); anchors.any { it in t } }
            return anchored.flatMap { i ->
                lines.indices.filter { j ->
                    j == i || (abs(lines[j].box.centerY - lines[i].box.centerY) <= 2 * refHeight &&
                        lines[j].box.horizontalOverlap(lines[i].box) > 0)
                }
            }.distinct().sorted()
        }

        private fun keywordHits(): Map<ScreenType, Int> {
            val all = texts.joinToString("\n").lowercase()
            return keywords.mapValues { (_, words) -> words.count { containsWord(all, it) } }
        }

        private fun screenType(rows: List<FareRow>): ScreenType {
            val hits = keywordHits()
            val best = SCREEN_PRIORITY.filter { (hits[it] ?: 0) > 0 }.maxByOrNull { hits.getValue(it) }
            return best ?: if (rows.any { it.label != null && it.price != null }) ScreenType.FARE_OPTIONS else ScreenType.OTHER
        }

        private fun piecesOf(line: TextLine): List<Piece> {
            val ids = line.tokenIds
            val out = mutableListOf<Piece>()
            fun single(k: Int) = Piece(listOf(ids[k]), frame.tokens[ids[k]].text.trim(), frame.tokens[ids[k]].box)
            fun norm(k: Int) = normalizeForPrice(frame.tokens[ids[k]].text)
            var i = 0
            while (i < ids.size) {
                var piece = single(i++)
                if (i < ids.size && BARE_MARKER.matches(normalizeForPrice(piece.text)) &&
                    STARTS_AMOUNT.containsMatchIn(norm(i)) && close(piece.box, single(i).box)
                ) {
                    piece += single(i++)
                }
                val text = normalizeForPrice(piece.text)
                if (CURRENCY_AMOUNT.containsMatchIn(text) && ENDS_DIGIT.containsMatchIn(text)) {
                    if (i + 1 < ids.size && DASH_ONLY.matches(norm(i)) && STARTS_AMOUNT.containsMatchIn(norm(i + 1)) &&
                        close(piece.box, single(i).box) && close(single(i).box, single(i + 1).box)
                    ) {
                        piece += single(i) + single(i + 1)
                        i += 2
                    } else if (i < ids.size && DASH_THEN_AMOUNT.containsMatchIn(norm(i)) && close(piece.box, single(i).box)) {
                        piece += single(i++)
                    }
                } else if (CURRENCY_AMOUNT.containsMatchIn(text) && ENDS_DASH.containsMatchIn(text) && i < ids.size &&
                    STARTS_AMOUNT.containsMatchIn(norm(i)) && close(piece.box, single(i).box)
                ) {
                    piece += single(i++)
                }
                out += piece
            }
            return out
        }

        private fun priceOf(piece: Piece): Candidate? {
            val struckByText = piece.text.any { it in STROKES } || TILDE_WRAPPED.matches(piece.text)
            val clean = piece.text.filterNot { it in STROKES }.trim().removePrefix("~").removeSuffix("~")
            val target = normalizeForPrice(clean)
            val m = pricePattern.matcher(target)
            if (!m.find()) return null
            val end = m.end() + (DECIMAL_TAIL.find(target.substring(m.end()))?.value?.length ?: 0)
            val amount = RupeeParser.parse(clean.substring(m.start(), end)) ?: return null
            return Candidate(PriceToken(amount, piece.ids, piece.box, piece.text), struckByText)
        }

        private fun leadingTwoOf(piece: Piece): Candidate? {
            if (!LEADING_TWO.matches(Digits.toAscii(piece.text))) return null
            val amount = RupeeParser.parse(piece.text, CurrencyContext.LEADING_TWO_IS_RUPEE) ?: return null
            return Candidate(PriceToken(amount, piece.ids, piece.box, piece.text), struckByText = false)
        }
    }

    private data class Piece(val ids: List<Int>, val text: String, val box: Box) {
        operator fun plus(other: Piece) = Piece(ids + other.ids, "$text ${other.text}", box.union(other.box))
    }

    private data class Candidate(val price: PriceToken, val struckByText: Boolean)

    companion object {
        /** Heights within 4% of the tallest ₹ token count as equally tall (OCR boxes jitter by a few pixels). */
        private const val TALLEST_TIE = 0.04
        private const val LEADING_TWO_COLUMN = 0.03
        private val LEADING_TWO_HEIGHT = 0.85..1.15

        private const val MARKER = "(?:₹|(?i:rs)\\.?|(?i:inr)|रु\\.?|रू\\.?)"
        private val BARE_MARKER = Regex("^$MARKER$")
        private val STARTS_AMOUNT = Regex("^$MARKER?\\s*\\d")
        private val CURRENCY_AMOUNT = Regex("^$MARKER\\s*\\d")
        private val DASH_ONLY = Regex("^(?:-|–|—|−|(?i:to))$")
        private val DASH_THEN_AMOUNT = Regex("^(?:-|–|—|−)\\s*$MARKER?\\s*\\d")
        private val ENDS_DIGIT = Regex("\\d$")
        private val ENDS_DASH = Regex("\\d\\s*(?:-|–|—|−)$")
        private val DECIMAL_TAIL = Regex("^\\.\\d{1,2}(?!\\d)")
        private val LEADING_TWO = Regex("^2\\d[\\d,]*(?:\\.\\d{1,2})?$")

        /** A bare amount or range, optionally led by one glyph ML Kit makes of ₹ (`{264`, `T272`, `180-210`). */
        private val BARE_AMOUNT = Regex("^[{\\[(<T]?\\d[\\d,]*(?:\\.\\d{1,2})?(?:\\s*(?:-|–|—|−)\\s*\\d[\\d,]*(?:\\.\\d{1,2})?)?$")
        private val STRIKE_MARKER = Regex("^[-–—―−_~=]+$")
        private val TILDE_WRAPPED = Regex("^~.+~$")
        private const val STROKES = "̵̶̸"

        /** Tie-break when two screen types have as many keyword hits: the more specific state wins. */
        private val SCREEN_PRIORITY = listOf(
            ScreenType.CANCELLED, ScreenType.DRIVER_ASSIGNED, ScreenType.SEARCHING, ScreenType.CANCEL_FLOW,
            ScreenType.FARE_OPTIONS, ScreenType.HOME, ScreenType.OTHER,
        )

        /** The parser for [app] at [installedVersionCode], or null when `fare_parsers` has no entry for it. */
        fun forApp(config: FareParsersConfig, app: Provider, installedVersionCode: Long?): FareParser? =
            config.entryFor(app.packageName, installedVersionCode)?.let { FareParser(it.entry) }

        private fun compile(pattern: String): Pattern = Pattern.compile(pattern)

        private fun normalizeForPrice(text: String): String =
            Digits.toAscii(normalizeSpaces(RupeeParser.fixCurrencyConfusion(text.trim())))

        /** Adjacent on a line: the gap is between −30% and 120% of the taller box's height. */
        private fun close(a: Box, b: Box): Boolean {
            val gap = b.l - a.r
            val h = maxOf(a.height, b.height)
            return gap >= -0.3 * h && gap <= 1.2 * h
        }

        /** A strike mark crosses a price when it covers ≥ half its width through the middle 70% of its height. */
        private fun crosses(mark: Box, price: Box): Boolean =
            mark.horizontalOverlap(price) >= 0.5 * price.width &&
                mark.centerY >= price.t + 0.15 * price.height && mark.centerY <= price.b - 0.15 * price.height

        private fun minutesOf(m: Matcher): Int? =
            (if (m.groupCount() >= 1) m.group(1) else null)?.toIntOrNull() ?: FIRST_INT.find(m.group())?.value?.toIntOrNull()

        private fun metresOf(m: Matcher): Int? {
            if (m.groupCount() >= 2 && m.group(1) != null && m.group(2) != null) {
                return DistanceText.parseMeters("${m.group(1)} ${m.group(2)}")
            }
            return DistanceText.parseMeters(m.group())
        }

        private val FIRST_INT = Regex("\\d+")

        /**
         * [word] occurs in [text] as a whole word: not glued to a letter or combining mark on either side, so `book`
         * is not found in "Facebook" or "booking", nor `pin` in "shopping" (K3 must not upload those screens).
         */
        internal fun containsWord(text: String, word: String): Boolean {
            if (word.isEmpty()) return false
            var from = 0
            while (true) {
                val at = text.indexOf(word, from)
                if (at < 0) return false
                val end = at + word.length
                val startOk = at == 0 || !isWordChar(word.first()) || !isWordChar(text[at - 1])
                val endOk = end == text.length || !isWordChar(word.last()) || !isWordChar(text[end])
                if (startOk && endOk) return true
                from = at + 1
            }
        }

        private fun isWordChar(c: Char): Boolean = c.isLetter() || when (Character.getType(c).toByte()) {
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
            else -> false
        }

        private fun median(values: List<Double>): Double? {
            if (values.isEmpty()) return null
            val s = values.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }
    }
}

/** K3 for [app]: without a parser entry for it, any ₹/Rs amount on screen is enough. */
fun looksLikeFareScreen(frame: OcrFrame, app: Provider, config: FareParsersConfig, installedVersionCode: Long?): Boolean =
    FareParser.forApp(config, app, installedVersionCode)?.looksLikeFareScreen(frame)
        ?: frame.tokens.any { token -> RupeeParser.parse(token.text) != null }
