package app.ridemate.trip

import app.ridemate.core.model.LatLng
import app.ridemate.core.model.Place
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drop search: 400 ms debounce over a fake Geocoder (virtual time). */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaceSearchControllerTest {
    private val geocoder = FakeGeocoder()
    private var near: LatLng? = LatLng(26.5, 91.0)

    private fun TestScope.controller() = PlaceSearchController(backgroundScope, geocoder, { near }).also { runCurrent() }

    private fun TestScope.type(c: PlaceSearchController, text: String, thenWaitMs: Long) {
        c.onQueryChange(text)
        advanceTimeBy(thenWaitMs)
        runCurrent()
    }

    @Test
    fun `typing quickly searches once, 400 ms after the last keystroke`() = runTest {
        val c = controller()
        type(c, "a", 100)
        type(c, "ai", 100)
        type(c, "air", 399)
        assertTrue(geocoder.calls.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(FakeGeocoder.Call("air", 5, near)), geocoder.calls)
        val results = c.state.value as PlaceSearchState.Results
        assertEquals("air", results.query)
        assertEquals(listOf("c1", "c2", "c3"), results.suggestions.map { it.id })
        assertEquals("air place 1", results.suggestions.first().label)
    }

    @Test
    fun `a pause between words searches each settled query`() = runTest {
        val c = controller()
        type(c, "fancy", 400)
        type(c, "fancy bazaar", 400)
        assertEquals(listOf("fancy", "fancy bazaar"), geocoder.calls.map { it.query })
    }

    @Test
    fun `the same settled query is not searched twice`() = runTest {
        val c = controller()
        type(c, "airport", 400)
        type(c, "airport ", 100) // a trailing space then back within the debounce window
        type(c, "airport", 400)
        assertEquals(listOf("airport"), geocoder.calls.map { it.query })
    }

    @Test
    fun `clearing the text shows nothing at once and searches nothing`() = runTest {
        val c = controller()
        type(c, "air", 400)
        c.clear()
        runCurrent()
        assertEquals(PlaceSearchState.Idle, c.state.value)
        type(c, "   ", 1_000)
        assertEquals(PlaceSearchState.Idle, c.state.value)
        assertEquals(1, geocoder.calls.size)
    }

    @Test
    fun `at most five suggestions with ids c1 to c5`() = runTest {
        geocoder.answer = { q -> GeocodeResult.Found(FakeGeocoder.placesFor(q, count = 8)) }
        val c = controller()
        type(c, "market", 400)
        val results = c.state.value as PlaceSearchState.Results
        assertEquals(listOf("c1", "c2", "c3", "c4", "c5"), results.suggestions.map { it.id })
    }

    @Test
    fun `no match, no geocoder and a failed lookup are distinct states`() = runTest {
        geocoder.answer = { GeocodeResult.Found(emptyList()) }
        val c = controller()
        type(c, "zzqx", 400)
        assertEquals(PlaceSearchState.NoResults("zzqx"), c.state.value)

        geocoder.answer = { GeocodeResult.Failed }
        type(c, "offline", 400)
        assertEquals(PlaceSearchState.Failed("offline"), c.state.value)

        geocoder.isAvailable = false
        type(c, "near the temple", 400)
        assertEquals(PlaceSearchState.Unavailable("near the temple"), c.state.value)
    }

    @Test
    fun `retry runs the same query again`() = runTest {
        geocoder.answer = { GeocodeResult.Failed }
        val c = controller()
        type(c, "airport", 400)
        geocoder.answer = { q -> GeocodeResult.Found(listOf(Place("$q T1", LatLng(26.1, 91.5)))) }
        c.retry()
        advanceTimeBy(400)
        runCurrent()
        assertEquals(listOf("airport", "airport"), geocoder.calls.map { it.query })
        assertEquals("airport T1", (c.state.value as PlaceSearchState.Results).suggestions.single().label)
    }

    @Test
    fun `a newer query abandons an older lookup that is still running`() = runTest {
        geocoder.searchDelayMs = 2_000
        val c = controller()
        type(c, "air", 400)
        assertEquals(PlaceSearchState.Searching("air"), c.state.value)
        type(c, "airport", 400)
        advanceTimeBy(2_000)
        runCurrent()
        val results = c.state.value as PlaceSearchState.Results
        assertEquals("airport", results.query)
        assertEquals(listOf("air", "airport"), geocoder.calls.map { it.query })
    }

    @Test
    fun `searches prefer places near the pickup`() = runTest {
        val c = controller()
        near = LatLng(26.1, 91.6)
        type(c, "station", 400)
        assertEquals(LatLng(26.1, 91.6), geocoder.calls.single().near)
    }
}
