package app.ridemate.compare

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.core.config.SanityBandsConfig
import app.ridemate.core.gate.GatedRow
import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.Provider
import app.ridemate.core.model.QuoteStatus
import app.ridemate.core.model.RideState
import app.ridemate.core.model.VehicleClass
import app.ridemate.data.QuoteEntity
import app.ridemate.data.RideMateDb
import app.ridemate.testing.FakeClock
import app.ridemate.testing.TestData
import app.ridemate.testing.TestSeams
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuoteStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: RideMateDb
    private val clock = FakeClock()
    private lateinit var store: RoomQuoteStore

    @Before
    fun setUp() {
        db = TestSeams.inMemoryDb(context)
        store = RoomQuoteStore(db, clock)
        runBlocking {
            db.sessionDao().insert(TestData.session("s1"))
            db.legDao().upsert(TestData.leg("l1", "s1", Provider.UBER, RideState.OPENED))
            db.legDao().upsert(TestData.leg("l2", "s1", Provider.OLA, RideState.SELECTED))
            db.legDao().upsert(TestData.leg("l3", "s1", Provider.ONDC, RideState.SEARCHING_NET))
        }
    }

    @After
    fun tearDown() = db.close()

    private fun verified(cls: VehicleClass, price: Int, eta: Int? = 3) = GatedRow(
        Provider.UBER, cls, "Uber ${cls.wireId}", labelFromOcr = true, price = price..price, status = QuoteStatus.VERIFIED,
        eta = eta, etaSource = if (eta == null) EtaSource.NONE else EtaSource.VERIFIED, seenAtMs = clock.wall,
    )

    private fun unreadable(cls: VehicleClass) = GatedRow(
        Provider.UBER, cls, "Uber ${cls.wireId}", labelFromOcr = true, price = null, status = QuoteStatus.UNREADABLE,
        eta = null, etaSource = EtaSource.NONE, seenAtMs = clock.wall,
    )

    private suspend fun quotes(legId: String) = db.quoteDao().observeForLeg(legId).first()

    @Test
    fun `a read replaces earlier machine rows and moves the leg to QUOTED`() = runBlocking {
        assertTrue(store.saveRead("l1", listOf(verified(VehicleClass.AUTO, 110)), 7, "h1"))
        assertTrue(store.saveRead("l1", listOf(verified(VehicleClass.AUTO, 115), unreadable(VehicleClass.BIKE)), 7, "h2"))

        val rows = quotes("l1")
        assertEquals(2, rows.size)
        assertEquals(115, rows.single { it.vehicleClass == VehicleClass.AUTO }.priceMin)
        assertNull(rows.single { it.vehicleClass == VehicleClass.BIKE }.priceMin)
        assertTrue(rows.all { it.sourceFrameHash == "h2" && it.sourceVersionCode == 7L })
        assertEquals(RideState.QUOTED, db.legDao().get("l1")!!.state)
    }

    @Test
    fun `typed prices survive a read unless the read verified that class`() = runBlocking {
        val typedAuto = GatedRow.userEntered(Provider.UBER, VehicleClass.AUTO, 100..100, clock.wall)
        val typedCab = GatedRow.userEntered(Provider.UBER, VehicleClass.CAB_ECONOMY, 200..200, clock.wall)
        store.saveUserEntered("l1", typedAuto)
        store.saveUserEntered("l1", typedCab)

        store.saveRead("l1", listOf(verified(VehicleClass.AUTO, 110), unreadable(VehicleClass.CAB_ECONOMY)), null, null)

        val rows = quotes("l1")
        assertEquals(setOf(QuoteStatus.VERIFIED to VehicleClass.AUTO, QuoteStatus.UNREADABLE to VehicleClass.CAB_ECONOMY,
            QuoteStatus.USER_ENTERED to VehicleClass.CAB_ECONOMY), rows.map { it.status to it.vehicleClass }.toSet())
    }

    @Test
    fun `a typed price replaces an earlier typed price of the same class`() = runBlocking {
        store.saveUserEntered("l1", GatedRow.userEntered(Provider.UBER, VehicleClass.AUTO, 100..100, clock.wall))
        store.saveUserEntered("l1", GatedRow.userEntered(Provider.UBER, VehicleClass.AUTO, 105..105, clock.wall))

        assertEquals(listOf(105), quotes("l1").map { it.priceMin })
    }

    @Test
    fun `a read never moves a booked leg backwards`() = runBlocking {
        db.legDao().upsert(TestData.leg("l1", "s1", Provider.UBER, RideState.BOOK_REQUESTED))
        store.saveRead("l1", listOf(verified(VehicleClass.AUTO, 110)), null, null)
        assertEquals(RideState.BOOK_REQUESTED, db.legDao().get("l1")!!.state)
    }

    @Test
    fun `unknown legs are refused`() = runBlocking {
        assertFalse(store.saveRead("nope", listOf(verified(VehicleClass.AUTO, 110)), null, null))
        assertFalse(store.saveUserEntered("nope", GatedRow.userEntered(Provider.UBER, VehicleClass.AUTO, 100..100, clock.wall)))
    }

    @Test
    fun `pending reads count ride-app legs not yet quoted (never ONDC)`() = runBlocking {
        assertEquals(2, store.pendingReads("s1"))
        store.saveRead("l1", listOf(verified(VehicleClass.AUTO, 110)), null, null)
        assertEquals(1, store.pendingReads("s1"))
        store.saveUserEntered("l2", GatedRow.userEntered(Provider.OLA, VehicleClass.AUTO, 100..100, clock.wall))
        assertEquals(0, store.pendingReads("s1"))
    }

    @Test
    fun `stored quotes map back to rows that satisfy I2`() {
        val verifiedRow = QuoteMapping.toGatedRow(Provider.UBER, QuoteMapping.toEntity("l1", verified(VehicleClass.AUTO, 110), null, null))!!
        assertEquals(110..110, verifiedRow.price)
        assertEquals(EtaSource.VERIFIED, verifiedRow.etaSource)
        assertTrue(verifiedRow.labelFromOcr)

        val typed = QuoteMapping.toGatedRow(
            Provider.OLA,
            QuoteMapping.toEntity("l2", GatedRow.userEntered(Provider.OLA, VehicleClass.BIKE, 40..50, clock.wall), null, null),
        )!!
        assertEquals(QuoteStatus.USER_ENTERED, typed.status)
        assertFalse(typed.labelFromOcr)

        val network = QuoteMapping.toGatedRow(
            Provider.ONDC,
            QuoteMapping.toEntity("l3", GatedRow.network(VehicleClass.AUTO, "Demo Seller Autos", 92, 4, clock.wall), null, null),
        )!!
        assertEquals("Demo Seller Autos", network.displayLabel)
        assertEquals(EtaSource.NETWORK, network.etaSource)
    }

    @Test
    fun `a stored row that would show a number without a source is dropped`() {
        val broken = QuoteEntity(
            legId = "l1", vehicleClass = VehicleClass.AUTO, labelText = "Auto", priceMin = null, priceMax = null, etaMin = null,
            seenAtUtc = clock.wall, status = QuoteStatus.VERIFIED, sourceVersionCode = null,
        )
        assertNull(QuoteMapping.toGatedRow(Provider.UBER, broken))
        // An unreadable row never shows a price or ETA, whatever the columns hold.
        val unreadable = broken.copy(status = QuoteStatus.UNREADABLE, priceMin = 110, priceMax = 110, etaMin = 3)
        val row = QuoteMapping.toGatedRow(Provider.UBER, unreadable)!!
        assertNull(row.price)
        assertNull(row.eta)
    }
}

@RunWith(AndroidJUnit4::class)
class ManualEntryUseCaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: RideMateDb
    private val clock = FakeClock()
    private lateinit var manual: ManualEntryUseCase
    private var projectionStops = 0

    @Before
    fun setUp() {
        db = TestSeams.inMemoryDb(context)
        manual = ManualEntryUseCase(RoomQuoteStore(db, clock), clock, onAllFaresRead = { projectionStops++ }) { SanityBandsConfig.DEFAULT }
        runBlocking {
            db.sessionDao().insert(TestData.session("s1"))
            db.legDao().upsert(TestData.leg("l1", "s1", Provider.RAPIDO, RideState.OPENED))
        }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `Indian formats are understood and saved as entered by you`() = runBlocking {
        for ((text, range) in listOf("120" to 120..120, "₹1,234" to 1234..1234, "Rs 110–130" to 110..130, "१२०" to 120..120)) {
            val saved = manual.enter("l1", Provider.RAPIDO, VehicleClass.AUTO, text) as ManualEntryResult.Saved
            assertEquals(range, saved.row.price)
            assertEquals(QuoteStatus.USER_ENTERED, saved.row.status)
        }
        val stored = db.quoteDao().observeForLeg("l1").first().single()
        assertEquals(QuoteStatus.USER_ENTERED, stored.status)
        assertEquals(120, stored.priceMin)
        assertEquals(RideState.QUOTED, db.legDao().get("l1")!!.state)
    }

    @Test
    fun `nonsense and implausible prices are refused with what to do`() = runBlocking {
        assertEquals(ManualEntryResult.NotAPrice, manual.enter("l1", Provider.RAPIDO, VehicleClass.AUTO, "cheap"))
        assertEquals(ManualEntryResult.NotAPrice, manual.enter("l1", Provider.RAPIDO, VehicleClass.AUTO, "1,2345"))
        assertEquals(ManualEntryResult.OutOfRange(15, 5000), manual.enter("l1", Provider.RAPIDO, VehicleClass.AUTO, "10"))
        assertEquals(ManualEntryResult.OutOfRange(15, 5000), manual.enter("l1", Provider.RAPIDO, VehicleClass.AUTO, "₹9,999"))
        assertEquals(ManualEntryResult.NoSuchLeg, manual.enter("gone", Provider.RAPIDO, VehicleClass.AUTO, "120"))
        assertTrue(db.quoteDao().observeForLeg("l1").first().isEmpty())

        val message = manualEntryError(context, ManualEntryResult.OutOfRange(15, 5000))!!
        assertTrue(message, message.contains("₹15") && message.contains("₹5,000"))
        assertNull(manualEntryError(context, ManualEntryResult.Saved(GatedRow.userEntered(Provider.RAPIDO, VehicleClass.AUTO, 120..120, 0))))
    }

    @Test
    fun `typing the price of the last app still waiting ends fare reading and stops the projection`() = runBlocking {
        db.legDao().upsert(TestData.leg("l2", "s1", Provider.OLA, RideState.OPENED))

        manual.enter("l1", Provider.RAPIDO, VehicleClass.AUTO, "120")
        assertEquals("Ola still waits for a price", 0, projectionStops)

        manual.enter("l2", Provider.OLA, VehicleClass.AUTO, "130")
        assertEquals(1, projectionStops)

        manual.enter("gone", Provider.OLA, VehicleClass.AUTO, "130")
        manual.enter("l2", Provider.OLA, VehicleClass.AUTO, "nonsense")
        assertEquals("refused entries change nothing", 1, projectionStops)
    }
}
