package app.ridemate.core.replay

import app.ridemate.core.config.NotificationTemplatesConfig

/**
 * Synthetic notification templates and wordings for the three ride apps. These are test fixtures written for the
 * replay suite (the real templates come from the owner's Recorder); they exercise every template event, the optional
 * eta/dist/plate groups and a structural `redacted` template.
 */
object Fixtures {
    const val RAPIDO = "com.rapido.passenger"
    const val UBER = "com.ubercab"
    const val OLA = "com.olacabs.customer"

    /** JSON-escaped (double backslashes) because it is spliced into the JSON payload below. */
    private const val PLATE = """[A-Z]{2}\\s?\\d{1,2}\\s?[A-Z]{0,3}\\s?\\d{3,4}"""

    val TEMPLATES_JSON: String = """
    {"schema": "1.0",
     "apps": {
      "$RAPIDO": [
       {"min_version_code": 0,
        "templates": [
          {"id": "rapido.searching.v1", "event": "SEARCHING", "pattern": "(?i)looking for (?:a )?captain"},
          {"id": "rapido.assigned.v1", "event": "ASSIGNED",
           "pattern": "(?is)captain (?P<name>[a-z ]+) is on the way(?:.*?(?P<eta>\\d+)\\s*min)?(?:.*?(?P<dist>\\d+(?:\\.\\d+)?\\s*km))?(?:.*?vehicle (?P<plate>$PLATE))?"},
          {"id": "rapido.arriving.v1", "event": "ARRIVING", "pattern": "(?i)captain (?:has arrived|is arriving)"},
          {"id": "rapido.driver_cancelled.v1", "event": "DRIVER_CANCELLED", "pattern": "(?is)captain cancelled.*finding (?:you )?another"},
          {"id": "rapido.no_drivers.v1", "event": "NO_DRIVERS", "pattern": "(?i)no captains? (?:are )?available"},
          {"id": "rapido.cancelled.v1", "event": "CANCELLED", "pattern": "(?i)your ride (?:has been|was) cancelled"},
          {"id": "rapido.trip_started.v1", "event": "TRIP_STARTED", "pattern": "(?i)ride started"},
          {"id": "rapido.ended.v1", "event": "ENDED", "pattern": "(?i)ride completed"}
        ],
        "redacted": [{"event": "ASSIGNED", "channel_id": "ride_status", "ongoing": true, "id_changed": true}],
        "cancelled_keywords": ["cancelled", "रद्द"],
        "ongoing_channel_ids": ["ride_status"]}
      ],
      "$UBER": [
       {"min_version_code": 0,
        "templates": [
          {"id": "uber.searching.v1", "event": "SEARCHING", "pattern": "(?i)finding your ride"},
          {"id": "uber.assigned.v1", "event": "ASSIGNED",
           "pattern": "(?is)is on the way.*?(?P<plate>$PLATE).*?(?P<eta>\\d+)\\s*min away(?:.*?(?P<dist>\\d+(?:\\.\\d+)?\\s*(?:km|m))\\b)?"},
          {"id": "uber.arriving.v1", "event": "ARRIVING", "pattern": "(?i)driver is arriving now|is here"},
          {"id": "uber.driver_cancelled.v1", "event": "DRIVER_CANCELLED", "pattern": "(?i)driver cancelled.*finding (?:you )?a new driver"},
          {"id": "uber.no_drivers.v1", "event": "NO_DRIVERS", "pattern": "(?i)no drivers available"},
          {"id": "uber.cancelled.v1", "event": "CANCELLED", "pattern": "(?i)your trip (?:has been|was) cancelled"},
          {"id": "uber.trip_started.v1", "event": "TRIP_STARTED", "pattern": "(?i)heading to your destination"},
          {"id": "uber.ended.v1", "event": "ENDED", "pattern": "(?i)you've arrived|thanks for riding"}
        ],
        "cancelled_keywords": ["cancelled", "canceled"],
        "ongoing_channel_ids": ["trip"]}
      ],
      "$OLA": [
       {"min_version_code": 0,
        "templates": [
          {"id": "ola.searching.v1", "event": "SEARCHING", "pattern": "(?i)searching for (?:a )?cab"},
          {"id": "ola.assigned.v1", "event": "ASSIGNED",
           "pattern": "(?is)your driver .*? is arriving in (?P<eta>\\d+) min(?:.*?(?P<plate>$PLATE))?"},
          {"id": "ola.arriving.v1", "event": "ARRIVING", "pattern": "(?i)driver has arrived"},
          {"id": "ola.driver_cancelled.v1", "event": "DRIVER_CANCELLED", "pattern": "(?i)driver cancelled.*allocating another"},
          {"id": "ola.no_drivers.v1", "event": "NO_DRIVERS", "pattern": "(?i)no cabs available"},
          {"id": "ola.cancelled.v1", "event": "CANCELLED", "pattern": "(?i)booking (?:has been )?cancelled"},
          {"id": "ola.trip_started.v1", "event": "TRIP_STARTED", "pattern": "(?i)trip started"},
          {"id": "ola.ended.v1", "event": "ENDED", "pattern": "(?i)trip completed"}
        ],
        "cancelled_keywords": ["cancelled"],
        "ongoing_channel_ids": ["booking"]}
      ]
     }}
    """.trimIndent()

    val templates: NotificationTemplatesConfig by lazy { NotificationTemplatesConfig.parse(TEMPLATES_JSON) }

    /** The same payload without the structural `redacted` template (for scenario (h), second variant). */
    val templatesWithoutStructural: NotificationTemplatesConfig by lazy {
        NotificationTemplatesConfig.parse(TEMPLATES_JSON.replace(Regex(""""redacted": \[[^\]]*],"""), ""))
    }

    /** Channel and ongoing flag each app uses for its ride notification in these fixtures. */
    fun rideChannel(pkg: String): String = when (pkg) {
        RAPIDO -> "ride_status"
        UBER -> "trip"
        else -> "booking"
    }

    object Text {
        val rapidoSearching = "Looking for a captain" to "Hang on, we are finding a captain near you"
        fun rapidoAssigned(eta: Int = 4, km: String = "1.2", plate: String = "AS01AB1234") =
            "Captain Ravi Das is on the way" to "Arriving in $eta min · $km km away · vehicle $plate"
        val rapidoArriving = "Captain is arriving" to "Please be ready at the pickup point"
        val rapidoDriverCancelled = "Captain cancelled" to "Sorry! We are finding you another captain"
        val rapidoNoDrivers = "No captains available" to "Please try again in a few minutes"
        val rapidoCancelled = "Your ride has been cancelled" to "We hope to see you again"
        val rapidoTripStarted = "Ride started" to "Enjoy your ride"
        val rapidoEnded = "Ride completed" to "Pay ₹110 to your captain"

        val uberSearching = "Finding your ride" to "We're looking for a driver nearby"
        fun uberAssigned(eta: Int = 3, plate: String = "KA05MN4321", dist: String = "900 m") =
            "Ramesh is on the way" to "White Toyota Etios $plate · $eta min away · $dist"
        val uberDriverCancelled = "Your driver cancelled" to "Driver cancelled. Finding you a new driver"
        val uberNoDrivers = "No drivers available" to "Try again shortly"
        val uberCancelled = "Your trip has been cancelled" to "No fee was charged"

        val olaSearching = "Searching for a cab" to "We'll notify you once a driver accepts"
        fun olaAssigned(eta: Int = 5, plate: String = "AS01CD5678") =
            "Booking confirmed" to "Your driver Mohan is arriving in $eta min · $plate"
        val olaDriverCancelled = "Driver cancelled" to "Driver cancelled your ride. Allocating another driver"
        val olaNoDrivers = "No cabs available" to "All our cabs are busy"
        val olaCancelled = "Booking cancelled" to "Your booking has been cancelled"
    }
}
