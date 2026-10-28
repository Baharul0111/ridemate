package app.ridemate.ondc

import app.ridemate.core.model.VehicleClass
import app.ridemate.core.state.ProtocolKind
import app.ridemate.core.state.ProtocolKind.CANCEL_CONFIRMED
import app.ridemate.core.state.ProtocolKind.CONFIRMED
import app.ridemate.core.state.ProtocolKind.DRIVER_ASSIGNED
import app.ridemate.core.state.ProtocolKind.ENDED
import app.ridemate.core.state.ProtocolKind.FAILED
import app.ridemate.core.state.ProtocolKind.INITIALIZED
import app.ridemate.core.state.ProtocolKind.QUOTES
import app.ridemate.core.state.ProtocolKind.SEARCH_STARTED
import app.ridemate.core.state.ProtocolKind.SELECTED
import app.ridemate.core.state.ProtocolKind.SOFT_CANCEL_QUOTED
import app.ridemate.core.state.ProtocolKind.TRIP_STARTED
import app.ridemate.core.state.TapId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `ondc_txn` snapshots → core ONDC protocol events (§4.4), as a table. */
class OndcEventsTest {
    private fun kinds(state: String, driver: Map<String, Any?>? = null, fee: Map<String, Any?>? = null, quotes: List<Map<String, Any?>> = OndcDocs.QUOTES) =
        OndcEvents.from(OndcTxnDoc.fromMap("t1", OndcDocs.doc(state, quotes = quotes, driver = driver, fee = fee))).map { it.kind }

    private val booked = listOf(SEARCH_STARTED, QUOTES, SELECTED, INITIALIZED, CONFIRMED)

    @Test
    fun `each state maps to the whole path up to it`() {
        val driver = mapOf("eta_min" to 4L, "distance_text" to "1.1 km")
        val table: List<Pair<() -> List<ProtocolKind>, List<ProtocolKind>>> = listOf(
            { kinds("SEARCHING_NET", quotes = emptyList()) } to listOf(SEARCH_STARTED),
            { kinds("SEARCHING_NET") } to listOf(SEARCH_STARTED, QUOTES),
            { kinds("ON_SELECT") } to listOf(SEARCH_STARTED, QUOTES, SELECTED),
            { kinds("INIT") } to listOf(SEARCH_STARTED, QUOTES, SELECTED, INITIALIZED),
            { kinds("CONFIRMED") } to booked,
            { kinds("CONFIRMED", driver = driver) } to booked + DRIVER_ASSIGNED,
            { kinds("ARRIVING", driver = driver) } to booked + ProtocolKind.ARRIVING,
            { kinds("ON_TRIP") } to booked + TRIP_STARTED,
            { kinds("ENDED") } to booked + ENDED,
            { kinds("SOFT_CANCEL", fee = mapOf("amount" to 30L, "currency" to "INR")) } to booked + SOFT_CANCEL_QUOTED,
            { kinds("SOFT_CANCEL", driver = driver, fee = mapOf("amount" to 30L)) } to booked + DRIVER_ASSIGNED + SOFT_CANCEL_QUOTED,
            { kinds("SOFT_CANCEL", fee = null) } to booked,
            { kinds("CANCEL_CONFIRMED", fee = mapOf("amount" to 30L)) } to booked + CANCEL_CONFIRMED,
            { kinds("FAILED") } to listOf(FAILED),
            { kinds("SOMETHING_NEW") } to emptyList(),
        )
        table.forEachIndexed { i, (actual, expected) -> assertEquals("row $i", expected, actual()) }
    }

    @Test
    fun `values travel as protocol data with stable message ids`() {
        val tap = TapId("book-tap")
        val doc = OndcTxnDoc.fromMap("t1", OndcDocs.doc("ARRIVING", driver = mapOf("eta_min" to 3L, "distance_text" to "800 m")))
        val events = OndcEvents.from(doc, bookTap = tap)
        val confirmed = events.single { it.kind == CONFIRMED }
        assertEquals(tap, confirmed.tapId)
        val arriving = events.single { it.kind == ProtocolKind.ARRIVING }
        assertEquals(3, arriving.etaMin)
        assertEquals(800, arriving.distanceMeters)
        assertEquals(events.map { it.messageId }.distinct().size, events.size)
        assertTrue(events.all { it.messageId!!.startsWith("t1/") })
        // The same snapshot gives the same ids (deliver-once); new driver values give a new id.
        assertEquals(events.map { it.messageId }, OndcEvents.from(doc, bookTap = tap).map { it.messageId })
        val moved = OndcTxnDoc.fromMap("t1", OndcDocs.doc("ARRIVING", driver = mapOf("eta_min" to 2L, "distance_text" to "500 m")))
        assertNotEquals(arriving.messageId, OndcEvents.from(moved).single { it.kind == ProtocolKind.ARRIVING }.messageId)
    }

    @Test
    fun `a fee is quoted per soft-cancel round, so a repeated request is shown again`() {
        val doc = OndcTxnDoc.fromMap("t1", OndcDocs.doc("SOFT_CANCEL", fee = mapOf("amount" to 25.4, "currency" to "INR")))
        val first = OndcEvents.from(doc, cancelRound = 1).single { it.kind == SOFT_CANCEL_QUOTED }
        val second = OndcEvents.from(doc, cancelRound = 2).single { it.kind == SOFT_CANCEL_QUOTED }
        assertEquals("rounded up, never understated", 26, first.feeInr)
        assertNotEquals(first.messageId, second.messageId)
    }

    @Test
    fun `documents parse leniently`() {
        val doc = OndcTxnDoc.fromMap(
            "t9",
            mapOf(
                "state" to "SEARCHING_NET",
                "bpp_id" to "mock-bpp.ridemate",
                "quotes" to listOf(
                    mapOf("item_id" to "a", "vehicle_class" to "auto", "price" to "95", "eta_min" to 5.0),
                    mapOf("item_id" to "b", "vehicle_class" to "rickshaw", "price" to 120L),
                    mapOf("item_id" to "c", "price" to 99.5),
                    mapOf("item_id" to "", "price" to 10L),
                    mapOf("item_id" to "d", "price" to -4L),
                    "garbage",
                ),
                "fee" to mapOf("amount" to 50L, "currency" to "USD"),
                "driver" to mapOf("distance_text" to "far"),
            ),
        )
        assertEquals(OndcTxnState.SEARCHING_NET, doc.state)
        assertTrue("mock seller is the demo seller", doc.demoSeller)
        assertEquals(listOf("a", "b"), doc.quotes.map { it.itemId })
        assertEquals(VehicleClass.OTHER, doc.quotes[1].vehicleClass)
        assertEquals(95, doc.quotes[0].priceInr)
        assertEquals(5, doc.quotes[0].etaMin)
        assertNull("only INR fees", doc.feeInr)
        assertNull(doc.driver)
        assertNull(OndcTxnDoc.fromMap("t", mapOf("state" to 3)).state)
        assertTrue(OndcTxnDoc.fromMap("t", emptyMap()).quotes.isEmpty())
    }
}
