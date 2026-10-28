package app.ridemate.core.gate

import app.ridemate.core.fare.FareParser
import app.ridemate.core.fare.RowSpec
import app.ridemate.core.fare.TestConfigs
import app.ridemate.core.fare.fareScreen
import app.ridemate.core.model.Provider
import app.ridemate.core.ocr.OcrFrame

object GateFixtures {
    val parser: FareParser = TestConfigs.parser()
    val bands = SanityBands()
    const val TRIP_KM = 6.2
    const val NOW = 1_793_000_000_000L
    val APP = Provider.DEMO_KESTREL

    val ROWS = listOf(
        RowSpec("Bike", "₹45", eta = "2 min away"),
        RowSpec("Auto", "₹110", struck = "₹140"),
        RowSpec("Cab Economy", "₹210", eta = "5 min away"),
    )

    val frame: OcrFrame = fareScreen(ROWS)

    /** What a correct Channel A would say about [frame]: the parser's own rows, boxed on the 0–1000 grid. */
    fun perfectRead(frame: OcrFrame = GateFixtures.frame, app: Provider = APP): ChannelARead {
        val read = parser.parse(frame)
        return ChannelARead(
            app = app,
            screenType = read.screenType,
            options = read.rows.map { row ->
                ChannelAOption(
                    vehicleClass = row.vehicleClass,
                    labelText = row.label?.text,
                    priceMin = row.price?.amount?.min,
                    priceMax = row.price?.amount?.max,
                    priceStruckText = row.ignoredPrices.firstOrNull()?.price?.text,
                    etaMin = row.eta?.value,
                    bbox = row.box.toGrid(frame.width, frame.height).toList(),
                )
            },
        )
    }

    fun ChannelARead.withOption(index: Int, change: (ChannelAOption) -> ChannelAOption): ChannelARead =
        copy(options = options.mapIndexed { i, o -> if (i == index) change(o) else o })

    fun verify(
        read: ChannelARead?,
        frame1: OcrFrame = frame,
        frame2: OcrFrame = frame1,
        tripKm: Double? = TRIP_KM,
        app: Provider = APP,
    ): GateResult = VerificationGate.verify(app, read, frame1, frame2, parser, bands, tripKm, NOW)
}
