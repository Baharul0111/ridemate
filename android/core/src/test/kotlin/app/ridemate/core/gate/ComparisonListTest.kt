package app.ridemate.core.gate

import app.ridemate.core.config.SanityBandsConfig
import app.ridemate.core.format.Numerals
import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.LatLng
import app.ridemate.core.model.Provider
import app.ridemate.core.model.QuoteStatus
import app.ridemate.core.model.VehicleClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComparisonListTest {
    private val seen = 1_793_000_000_000L

    private fun verified(app: Provider, vc: VehicleClass, price: IntRange, eta: Int? = 3, at: Long = seen) = GatedRow(
        app, vc, vc.name, true, price, QuoteStatus.VERIFIED, eta, if (eta == null) EtaSource.NONE else EtaSource.VERIFIED, at,
    )

    private fun unreadable(app: Provider, vc: VehicleClass) =
        GatedRow(app, vc, vc.name, true, null, QuoteStatus.UNREADABLE, null, EtaSource.NONE, seen, failures = setOf(GateFailure.PRICE_MISMATCH))

    private fun ComparisonList.group(vc: VehicleClass) = groups.single { it.vehicleClass == vc }

    @Test
    fun `groups by class and flags the cheapest verified row only`() {
        val list = ComparisonList.build(
            listOf(
                verified(Provider.UBER, VehicleClass.AUTO, 120..120),
                unreadable(Provider.RAPIDO, VehicleClass.AUTO),
                verified(Provider.OLA, VehicleClass.AUTO, 100..100),
                GatedRow.userEntered(Provider.RAPIDO, VehicleClass.AUTO, 60..60, seen),
                GatedRow.network(VehicleClass.AUTO, "Demo Seller Autos", 50, 4, seen),
                verified(Provider.UBER, VehicleClass.BIKE, 45..45),
            ),
            nowMs = seen + 1_000,
        )
        assertEquals(listOf(VehicleClass.BIKE, VehicleClass.AUTO), list.groups.map { it.vehicleClass })
        val auto = list.group(VehicleClass.AUTO).entries
        assertEquals(
            listOf(Provider.OLA, Provider.UBER, Provider.RAPIDO, Provider.RAPIDO, Provider.ONDC),
            auto.map { it.row.provider },
        )
        assertEquals(listOf(true, false, false, false, false), auto.map { it.cheapest })
        assertEquals(listOf("₹100", "₹120", null, "₹60", "₹50"), auto.map { it.priceText })
        assertEquals(
            listOf(null, null, GateText.UNREADABLE, GateText.ENTERED_BY_YOU, GateText.NETWORK_QUOTE),
            auto.map { it.statusTextKey },
        )
        assertTrue(list.group(VehicleClass.BIKE).entries.single().cheapest)
    }

    @Test
    fun `unsure rows are never cheapest even when alone`() {
        val list = ComparisonList.build(
            listOf(unreadable(Provider.UBER, VehicleClass.CAB_ECONOMY), GatedRow.userEntered(Provider.OLA, VehicleClass.CAB_ECONOMY, 99..99, seen)),
            seen,
        )
        assertTrue(list.groups.single().entries.none { it.cheapest })
    }

    @Test
    fun `ties are all cheapest and ranges compare by what you may pay`() {
        val tie = ComparisonList.build(
            listOf(verified(Provider.UBER, VehicleClass.AUTO, 110..110), verified(Provider.OLA, VehicleClass.AUTO, 110..110)),
            seen,
        )
        assertEquals(listOf(true, true), tie.groups.single().entries.map { it.cheapest })

        val ranges = ComparisonList.build(
            listOf(verified(Provider.UBER, VehicleClass.AUTO, 100..130), verified(Provider.OLA, VehicleClass.AUTO, 125..125)),
            seen,
        )
        val entries = ranges.groups.single().entries
        assertEquals(listOf(Provider.OLA, Provider.UBER), entries.map { it.row.provider })
        assertEquals(listOf(true, false), entries.map { it.cheapest })
        assertEquals("₹100–130", entries[1].priceText)
    }

    @Test
    fun `an old price is never flagged cheapest over a fresh one`() {
        // A 5-minute-old Uber ₹90 (greyed, "old") and a fresh Ola ₹100.
        val list = ComparisonList.build(
            listOf(
                verified(Provider.UBER, VehicleClass.AUTO, 90..90, at = seen - 300_000),
                verified(Provider.OLA, VehicleClass.AUTO, 100..100),
                verified(Provider.RAPIDO, VehicleClass.AUTO, 100..100, at = seen - 61_000),
            ),
            nowMs = seen + 1_000,
        )
        val auto = list.groups.single().entries
        assertEquals(listOf(Provider.UBER, Provider.OLA, Provider.RAPIDO), auto.map { it.row.provider })
        assertEquals(listOf(true, false, true), auto.map { it.stale })
        assertEquals("only the fresh ₹100 is cheapest; the old ₹100 tie is not", listOf(false, true, false), auto.map { it.cheapest })

        // Every verified price old: no cheapest flag at all.
        val allOld = ComparisonList.build(
            listOf(verified(Provider.UBER, VehicleClass.BIKE, 45..45, at = seen), verified(Provider.OLA, VehicleClass.BIKE, 50..50, at = seen)),
            nowMs = seen + Freshness.STALE_AFTER_MS,
        )
        assertTrue(allOld.groups.single().entries.none { it.cheapest })
        // The same rows while fresh: the cheaper one is flagged.
        val fresh = ComparisonList.build(
            listOf(verified(Provider.UBER, VehicleClass.BIKE, 45..45, at = seen), verified(Provider.OLA, VehicleClass.BIKE, 50..50, at = seen)),
            nowMs = seen + 59_999,
        )
        assertEquals(listOf(true, false), fresh.groups.single().entries.map { it.cheapest })
    }

    @Test
    fun `rows go stale at exactly 60 seconds with a text key, not just colour`() {
        val rows = listOf(verified(Provider.UBER, VehicleClass.AUTO, 110..110))
        val fresh = ComparisonList.build(rows, seen + 59_999).groups.single().entries.single()
        assertFalse(fresh.stale)
        assertEquals(GateText.SEEN_AT, fresh.freshnessTextKey)
        val stale = ComparisonList.build(rows, seen + 60_000).groups.single().entries.single()
        assertTrue(stale.stale)
        assertEquals(GateText.STALE, stale.freshnessTextKey)
        assertTrue(Freshness.isStale(seen, seen + Freshness.STALE_AFTER_MS))
        assertFalse(Freshness.isStale(seen, seen))
        assertEquals(fresh.seenAtText, stale.seenAtText)
    }

    @Test
    fun `ETA text keys and Hindi numerals`() {
        val list = ComparisonList.build(
            listOf(verified(Provider.UBER, VehicleClass.AUTO, 1200..1450, eta = null), unreadable(Provider.OLA, VehicleClass.AUTO)),
            seen,
            Numerals.DEVANAGARI,
        )
        val (withPrice, blank) = list.groups.single().entries
        assertEquals(GateText.ETA_SEE_IN_APP, withPrice.etaTextKey)
        assertNull(blank.etaTextKey)
        assertEquals("₹१,२००–१,४५०", withPrice.priceText)
        assertTrue(withPrice.seenAtText.none { it in '0'..'9' })
    }

    @Test
    fun `sanity bands follow contracts 2_3`() {
        val bands = SanityBands()
        assertTrue(bands.priceOk(15..15, VehicleClass.AUTO, null))
        assertFalse(bands.priceOk(14..20, VehicleClass.AUTO, null))
        assertFalse(bands.priceOk(4_900..5_001, VehicleClass.CAB_XL, null))
        // 10 km straight line → 13.5 km road: auto 8·13.5−60 = 48 … 45·13.5+60 = 667.5
        assertTrue(bands.priceOk(49..49, VehicleClass.AUTO, 10.0))
        assertFalse(bands.priceOk(47..47, VehicleClass.AUTO, 10.0))
        assertTrue(bands.priceOk(600..667, VehicleClass.AUTO, 10.0))
        assertFalse(bands.priceOk(600..668, VehicleClass.AUTO, 10.0))
        assertTrue(bands.priceOk(100..100, VehicleClass.OTHER, 0.0))
        assertTrue(bands.etaOk(0) && bands.etaOk(60))
        assertFalse(bands.etaOk(61) || bands.etaOk(-1))
        val km = SanityBands.tripKm(LatLng(26.1817, 91.7510), LatLng(26.1061, 91.5859))
        assertTrue(km in 17.5..19.5)
        val strict = SanityBands(SanityBandsConfig.DEFAULT.copy(priceMaxInr = 300))
        assertFalse(strict.priceOk(301..301, VehicleClass.CAB_PREMIUM, null))
    }
}
