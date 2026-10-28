package app.ridemate.core.gate

import app.ridemate.core.model.Provider
import app.ridemate.core.model.ScreenType
import app.ridemate.core.model.VehicleClass
import app.ridemate.core.ocr.GridBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelAReadTest {
    private val contractExample = """
        {"app": "uber", "screen_type": "fare_options",
         "options": [{"vehicle_class": "auto", "label_text": "Auto", "price_min": 110, "price_max": 130,
                      "price_struck_text": "₹150", "eta_min": 3, "bbox_0_1000": [80, 240, 940, 300]}],
         "fee_text": null, "driver_eta_min": 4, "driver_distance_text": "1.2 km"}
    """.trimIndent()

    @Test
    fun `decodes the contracts example`() {
        val read = ChannelARead.fromJson(contractExample)
        assertEquals(Provider.UBER, read.app)
        assertEquals(ScreenType.FARE_OPTIONS, read.screenType)
        assertEquals(4, read.driverEtaMin)
        assertEquals("1.2 km", read.driverDistanceText)
        assertNull(read.feeText)
        val option = read.options.single()
        assertEquals(ChannelAOption(VehicleClass.AUTO, "Auto", 110, 130, "₹150", 3, listOf(80.0, 240.0, 940.0, 300.0)), option)
        assertEquals(GridBox(80.0, 240.0, 940.0, 300.0), option.gridBox)
    }

    @Test
    fun `tolerates unknown enum strings and missing fields`() {
        val read = ChannelARead.fromJson(
            """{"app": "zoomcar", "screen_type": "trip_summary", "new_field": [1, 2],
                "options": [{"vehicle_class": "helicopter", "price_min": 50, "bbox_0_1000": [1.5, 2, "x", 4]},
                            {"vehicle_class": 7, "label_text": "Bike"},
                            {}]}""",
        )
        assertNull(read.app)
        assertNull(read.screenType)
        assertNull(read.options[0].vehicleClass)
        assertEquals(50, read.options[0].priceMin)
        assertNull(read.options[0].bbox)
        assertNull(read.options[1].vehicleClass)
        assertEquals(ChannelAOption(), read.options[2])
        assertEquals(ChannelARead(), ChannelARead.fromJson("{}"))
        assertEquals(ChannelARead(app = Provider.DEMO_TAMARIND), ChannelARead.fromJson("""{"app": "demo_tamarind", "options": []}"""))
        assertEquals(ChannelARead(screenType = null), ChannelARead.fromJson("""{"screen_type": null, "app": null}"""))
    }

    @Test
    fun `truncates options to twelve`() {
        val options = (1..15).joinToString(",") { """{"price_min": $it}""" }
        val read = ChannelARead.fromJson("""{"options": [$options]}""")
        assertEquals(ChannelARead.MAX_OPTIONS, read.options.size)
        assertEquals(12, read.options.last().priceMin)
    }

    @Test
    fun `rejects wrongly typed values as a whole`() {
        assertThrows(Exception::class.java) { ChannelARead.fromJson("""{"options": [{"price_min": "₹110"}]}""") }
        assertThrows(Exception::class.java) { ChannelARead.fromJson("""{"options": {}}""") }
        assertNull(ChannelARead.fromJsonOrNull("not json"))
        assertNull(ChannelARead.fromJsonOrNull("""{"driver_eta_min": 4.5}"""))
    }

    @Test
    fun `round-trips with snake_case names and integer boxes`() {
        val read = ChannelARead.fromJson(contractExample)
        val json = read.toJson()
        assertTrue(json, json.contains("\"screen_type\":\"fare_options\""))
        assertTrue(json, json.contains("\"bbox_0_1000\":[80,240,940,300]"))
        assertTrue(json, json.contains("\"app\":\"uber\""))
        assertEquals(read, ChannelARead.fromJson(json))
    }

    @Test
    fun `decodes the endpoint envelopes`() {
        val response = ReadScreenResponse.fromJson(
            """{"read": $contractExample, "source": "recorded", "model": "meta-models/Muse-Spark-Open-816B-A42B",
               "latency_ms": 2310, "request_id": "r-1"}""",
        )
        assertEquals("recorded", response.source)
        assertEquals(2310L, response.latencyMs)
        assertEquals(Provider.UBER, response.read.app)

        val reread = RereadResponse.fromJson(
            """{"rows": [{"row_id": "r1", "vehicle_class": "auto", "label_text": "Auto", "price_min": 110,
                          "price_max": 110, "eta_min": 3}], "source": "live", "request_id": "r-2"}""",
        )
        assertEquals(RereadRow("r1", VehicleClass.AUTO, "Auto", 110, 110, 3), reread.rows.single())
        assertEquals("""{"app":"uber","rows":[{"row_id":"r1"}]}""", RereadMeta("uber", listOf(RereadRowRef("r1"))).toJson())
    }
}
