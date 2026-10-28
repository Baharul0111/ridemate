package app.ridemate.ondc

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.core.gate.GatedRow
import app.ridemate.core.model.VehicleClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class OndcMessagesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val hindi: Context = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag("hi")) })

    private val quote = OndcQuoteView("auto-1", GatedRow.network(VehicleClass.AUTO, "Demo Seller Autos", 92, 4, 0L), demoSeller = true)

    @Test
    fun `network quotes are labelled, and the demo seller says so`() {
        assertEquals("network quote · ONDC demo seller", OndcMessages.quoteLabel(context, quote))
        assertEquals("network quote", OndcMessages.quoteLabel(context, quote.copy(demoSeller = false)))
        assertEquals(
            "Demo Seller Autos, Auto: ₹92, 4 min. network quote · ONDC demo seller",
            OndcMessages.quoteDescription(context, quote, "Auto"),
        )
        assertEquals("नेटवर्क का भाव · ONDC डेमो विक्रेता", OndcMessages.quoteLabel(hindi, quote))
    }

    @Test
    fun `every unavailable reason says what to do next, in English and Hindi`() {
        OndcUnavailableReason.entries.forEach { reason ->
            val en = context.getString(OndcMessages.unavailableRes(reason))
            val hi = hindi.getString(OndcMessages.unavailableRes(reason))
            // Each names the next step: book elsewhere, sign in, tap Cancel ride again, or wait for RideMate's reminder.
            assertTrue(en, listOf("ride app", "Sign in", "Tap Cancel ride again", "will remind you").any { it in en })
            assertTrue(hi, hi.isNotBlank() && hi != en)
        }
        val row = OndcMessages.status(context, OndcView(phase = OndcPhase.UNAVAILABLE, unavailable = OndcUnavailableReason.OFFLINE))
        assertEquals("ONDC unavailable. You're offline. Connect and search again, or book in a ride app.", row)
        assertEquals(
            "ONDC उपलब्ध नहीं। खुले नेटवर्क ने अभी तक आपका रद्द करना पक्का नहीं किया है, इसलिए आपकी राइड शायद अभी भी बुक है। RideMate जाँच करता रहेगा और आपको याद दिलाएगा।",
            OndcMessages.status(hindi, OndcView(phase = OndcPhase.UNAVAILABLE, unavailable = OndcUnavailableReason.CANCEL_UNCONFIRMED)),
        )
    }

    @Test
    fun `the fee is stated before confirming, free or not`() {
        assertEquals("Cancelling now costs ₹1,250 (the network's fee). Confirm to cancel, or keep the ride.", OndcMessages.feeText(context, 1250))
        assertEquals("Cancelling now is free. Confirm to cancel, or keep the ride.", OndcMessages.feeText(context, 0))
        assertEquals("Asking the network what cancelling costs now…", OndcMessages.feeText(context, null))
        assertEquals(
            OndcMessages.feeText(context, 30),
            OndcMessages.status(context, OndcView(phase = OndcPhase.CANCEL_FEE_SHOWN, feeInr = 30)),
        )
    }

    @Test
    fun `each phase has a status line or speaks through its quotes`() {
        OndcPhase.entries.forEach { phase ->
            val text = OndcMessages.status(context, OndcView(phase = phase, unavailable = OndcUnavailableReason.SERVER))
            if (phase == OndcPhase.IDLE || phase == OndcPhase.QUOTES) assertNull(text) else assertTrue(phase.name, !text.isNullOrBlank())
        }
        VehicleClass.entries.forEach { assertTrue(context.getString(OndcMessages.classRes(it)).isNotBlank()) }
    }
}
