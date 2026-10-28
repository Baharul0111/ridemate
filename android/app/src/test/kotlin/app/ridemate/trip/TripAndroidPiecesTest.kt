package app.ridemate.trip

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import android.os.Bundle
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.core.model.LatLng
import app.ridemate.testing.FakeClock
import app.ridemate.testing.TestRideMateApp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowGeocoder
import java.util.Locale

/** Recent places, map intents, the Android Geocoder adapter and speech helpers (Robolectric). */
@RunWith(AndroidJUnit4::class)
class TripAndroidPiecesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock()
    private val prefs = context.getSharedPreferences("recent-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @After
    fun resetGeocoder() = ShadowGeocoder.reset()

    // ---- recent places -------------------------------------------------------------------------------------

    @Test
    fun `recent places are newest first, deduplicated and capped`() {
        val store = RecentPlacesStore(prefs, clock, maxEntries = 3)
        store.add(TripPlace("Fancy Bazaar", LatLng(26.18, 91.74)))
        clock.advance(1_000)
        store.add(TripPlace("LGBI Airport", LatLng(26.1, 91.58)))
        clock.advance(1_000)
        store.add(TripPlace("fancy bazaar", LatLng(26.18, 91.74)))
        clock.advance(1_000)
        store.add(TripPlace("Typed place", null))
        clock.advance(1_000)
        store.add(TripPlace("Paltan Bazaar", LatLng(26.17, 91.75)))

        assertEquals(listOf("Paltan Bazaar", "Typed place", "fancy bazaar"), store.recent().map { it.label })
        assertNull(store.recent()[1].point)
        assertEquals(LatLng(26.18, 91.74), store.recent()[2].point)
    }

    @Test
    fun `recent places expire after 24 hours like ride data`() {
        val store = RecentPlacesStore(prefs, clock)
        store.add(TripPlace("Old", LatLng(1.0, 1.0)))
        clock.advance(RecentPlacesStore.RETENTION_MS - 1)
        store.add(TripPlace("New", LatLng(2.0, 2.0)))
        assertEquals(listOf("New", "Old"), store.recent().map { it.label })
        clock.advance(2)
        assertEquals(listOf("New"), store.recent().map { it.label })
        clock.advance(RecentPlacesStore.RETENTION_MS)
        assertEquals(1, store.purgeExpired())
        assertTrue(store.recent().isEmpty())
    }

    @Test
    fun `clear wipes everything and corrupt data reads as empty`() {
        val store = RecentPlacesStore(prefs, clock)
        store.add(TripPlace("A", null))
        store.clear()
        assertTrue(store.recent().isEmpty())
        prefs.edit().putString("recent", "{not json").commit()
        assertTrue(store.recent().isEmpty())
    }

    @Test
    fun `erase wipes recent places through the graph's local wipers`() = runBlocking {
        val graph = (context as TestRideMateApp).graph
        graph.recentPlaces.add(TripPlace("Home", LatLng(26.0, 91.0)))
        assertEquals(1, graph.recentPlaces.recent().size)
        graph.localWipers.forEach { it.wipe() }
        assertTrue(graph.recentPlaces.recent().isEmpty())
    }

    // ---- view on map -----------------------------------------------------------------------------------------

    @Test
    fun `view on map drops a labelled pin in the phone's maps app`() {
        val place = TripPlace("LGBI Airport, Guwahati", LatLng(26.1061, 91.5859))
        assertEquals("geo:26.106100,91.585900?q=26.106100,91.585900(LGBI%20Airport%2C%20Guwahati)", MapIntents.geoUri(place))
        val intent = MapIntents.viewOnMap(place)
        assertEquals(android.content.Intent.ACTION_VIEW, intent.action)
        assertEquals("geo", intent.data!!.scheme)
    }

    @Test
    fun `a typed address without coordinates becomes a map search`() {
        val uri = MapIntents.geoUri(TripPlace("Near Kali Mandir & bus stand", null))
        assertEquals("geo:0,0?q=Near%20Kali%20Mandir%20%26%20bus%20stand", uri)
        assertEquals("Near Kali Mandir & bus stand", Uri.decode(uri.substringAfter("?q=")))
    }

    // ---- Android Geocoder adapter ------------------------------------------------------------------------------

    private fun address(lines: List<String>, lat: Double? = 26.1, lng: Double? = 91.5, feature: String? = null) =
        Address(Locale.ENGLISH).apply {
            lines.forEachIndexed { i, line -> setAddressLine(i, line) }
            if (lat != null) latitude = lat
            if (lng != null) longitude = lng
            featureName = feature
            locality = if (lines.isEmpty()) "Guwahati" else null
        }

    private fun geocoderReturning(addresses: List<Address>? = null, error: String? = null): AndroidPlaceGeocoder {
        val geocoder = Geocoder(context, Locale.ENGLISH)
        if (addresses != null) shadowOf(geocoder).setFromLocation(addresses)
        if (error != null) shadowOf(geocoder).setErrorMessage(error)
        return AndroidPlaceGeocoder(context, newGeocoder = { _, _ -> geocoder })
    }

    @Test
    fun `geocoder matches become labelled places, without duplicates or pinless entries`() = runBlocking {
        val geocoder = geocoderReturning(
            listOf(
                address(listOf("LGBI Airport", "Guwahati, Assam")),
                address(listOf("lgbi airport", "guwahati, assam")),
                address(listOf("No pin"), lat = null, lng = null),
                address(emptyList(), lat = 26.2, lng = 91.7, feature = "Paltan Bazaar"),
            ),
        )
        val result = geocoder.search("airport", 5, near = null) as GeocodeResult.Found
        assertEquals(listOf("LGBI Airport, Guwahati, Assam", "Paltan Bazaar, Guwahati"), result.places.map { it.label })
        assertEquals(LatLng(26.1, 91.5), result.places.first().point)
    }

    @Test
    fun `geocoder errors fail, a missing backend is unavailable, blank queries find nothing`() = runBlocking {
        assertEquals(GeocodeResult.Failed, geocoderReturning(error = "service not available").search("airport", 5, null))
        assertEquals(GeocodeResult.Found(emptyList()), geocoderReturning(emptyList()).search("  ", 5, null))
        ShadowGeocoder.setIsPresent(false)
        val missing = geocoderReturning(emptyList())
        assertEquals(false, missing.isAvailable)
        assertEquals(GeocodeResult.Unavailable, missing.search("airport", 5, null))
        assertNull(missing.reverse(LatLng(26.0, 91.0)))
    }

    @Test
    fun `reverse geocoding gives the pickup's one-line address`() = runBlocking {
        val geocoder = geocoderReturning(listOf(address(listOf("Station Road", "Barpeta Road, Assam"))))
        assertEquals("Station Road, Barpeta Road, Assam", geocoder.reverse(LatLng(26.5, 90.97)))
    }

    // ---- speech helpers ----------------------------------------------------------------------------------------

    @Test
    fun `voice languages are matched against what the recognizer reports`() {
        assertEquals(setOf(VoiceLanguage.ENGLISH, VoiceLanguage.HINDI), VoiceLanguage.supportedAmong(listOf("en-IN", "hi_IN", "fr-FR")))
        assertEquals(setOf(VoiceLanguage.BENGALI), VoiceLanguage.supportedAmong(listOf("bn")))
        assertEquals(emptySet<VoiceLanguage>(), VoiceLanguage.supportedAmong(listOf("de-DE")))
        assertNull(VoiceLanguage.supportedAmong(emptyList()))
        assertEquals(VoiceLanguage.HINDI, VoiceLanguage.defaultFor(Locale.forLanguageTag("hi-IN")))
        assertEquals(VoiceLanguage.ENGLISH, VoiceLanguage.defaultFor(Locale.US))
        assertEquals(listOf("en-IN", "hi-IN", "bn-IN", "as-IN"), VoiceLanguage.entries.map { it.tag })
    }

    @Test
    fun `recognizer errors map to what the user can do next`() {
        assertEquals(SpeechError.NO_MATCH, AndroidSpeechInput.errorOf(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals(SpeechError.NO_MATCH, AndroidSpeechInput.errorOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertEquals(SpeechError.NO_PERMISSION, AndroidSpeechInput.errorOf(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals(SpeechError.NETWORK, AndroidSpeechInput.errorOf(SpeechRecognizer.ERROR_NETWORK))
        assertEquals(SpeechError.BUSY, AndroidSpeechInput.errorOf(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
        assertEquals(SpeechError.LANGUAGE_UNSUPPORTED, AndroidSpeechInput.errorOf(12))
        assertEquals(SpeechError.LANGUAGE_UNSUPPORTED, AndroidSpeechInput.errorOf(13))
        assertEquals(SpeechError.OTHER, AndroidSpeechInput.errorOf(999))
        val bundle = Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(" ", " airport ")) }
        assertEquals("airport", AndroidSpeechInput.firstResult(bundle))
        assertNull(AndroidSpeechInput.firstResult(null))
    }
}
