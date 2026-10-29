package app.ridemate.map

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MapConfigTest {

    @Test
    fun `an https style url is used as given`() {
        val url = "https://tiles.example.org/styles/bright"
        assertEquals(url, MapConfig.from(true, url).styleUrl)
    }

    @Test
    fun `non https, blank, malformed or osm tile urls fall back to openfreemap`() {
        listOf(
            null,
            "",
            "   ",
            "http://tiles.openfreemap.org/styles/liberty",
            "ftp://tiles.example.org/style",
            "javascript:alert(1)",
            "https://tiles.example.org/style?key=abc",
            "https://tile.openstreetmap.org/styles/x",
        ).forEach { bad ->
            assertEquals("$bad", MapConfig.DEFAULT_STYLE_URL, MapConfig.from(true, bad).styleUrl)
        }
    }

    @Test
    fun `the default is the free keyless openfreemap liberty style, map on`() {
        assertTrue(MapConfig.DEFAULT.enabled)
        assertEquals("https://tiles.openfreemap.org/styles/liberty", MapConfig.DEFAULT.styleUrl)
    }

    @Test
    fun `the kill switch turns the map off but keeps a safe style`() {
        val config = MapConfig.from(false, "http://bad")
        assertFalse(config.enabled)
        assertEquals(MapConfig.DEFAULT_STYLE_URL, config.styleUrl)
    }

    @Test
    fun `a style that loads in time is ready`() = runTest {
        assertEquals(MapLoadResult.Ready, awaitMapStyle { true })
    }

    @Test
    fun `a style that reports failure is unavailable at once`() = runTest {
        assertEquals(MapLoadResult.Unavailable(MapUnavailableReason.STYLE_FAILED), awaitMapStyle { false })
        assertEquals(MapLoadResult.Unavailable(MapUnavailableReason.STYLE_FAILED), awaitMapStyle { error("boom") })
    }

    @Test
    fun `a style still loading after 8 seconds times out to the typed address fallback`() = runTest {
        val never = CompletableDeferred<Boolean>()
        val result = async { awaitMapStyle { never.await() } }
        advanceTimeBy(MapConfig.STYLE_LOAD_TIMEOUT_MS - 1)
        runCurrent()
        assertFalse(result.isCompleted)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(MapLoadResult.Unavailable(MapUnavailableReason.TIMEOUT), result.await())
        assertEquals(8_000L, MapConfig.STYLE_LOAD_TIMEOUT_MS)
    }
}
