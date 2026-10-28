package app.ridemate.core.mask

/** What the masker hides (goal 24). Defaults cover Uber, Ola, Rapido and the demo providers in English and Hindi. */
data class MaskConfig(
    /** Top band of every frame that is always blurred (status bar: clock, carrier, notification icons). */
    val statusBarFraction: Double = 0.045,
    /** Words after which a driver's name follows. Matched case-insensitively as whole words. */
    val nameAnchors: List<String> = DEFAULT_NAME_ANCHORS,
    /** Phrases a driver's name comes right before ("Ramesh is on the way"). Matched like [nameAnchors]. */
    val trailingNameAnchors: List<String> = DEFAULT_TRAILING_NAME_ANCHORS,
    /** Labels near which a 4–6 digit code is an OTP/PIN. Matched case-insensitively as whole words. */
    val otpAnchors: List<String> = DEFAULT_OTP_ANCHORS,
    /** Capitalised words that end a name (statuses, brands, vehicle and payment words), compared lower-cased. */
    val nameStopwords: Set<String> = DEFAULT_NAME_STOPWORDS,
) {
    init {
        require(statusBarFraction in 0.0..0.2) { "status bar band must be 0–20% of the frame" }
    }

    companion object {
        val DEFAULT_NAME_ANCHORS: List<String> = listOf(
            "your captain", "your driver", "captain", "driver", "with",
            "आपके कैप्टन", "आपके ड्राइवर", "कैप्टन", "ड्राइवर", "चालक",
        )

        val DEFAULT_TRAILING_NAME_ANCHORS: List<String> = listOf(
            "is your driver", "is your captain", "is on the way", "is on his way", "is on her way", "is arriving",
            "has arrived", "is waiting", "will reach", "आ रहे हैं", "पहुंच गए", "पहुँच गए", "पहुंच रहे हैं",
        )

        val DEFAULT_OTP_ANCHORS: List<String> = listOf(
            "start ride code", "start ride pin", "ride code", "start code", "trip code", "ride pin", "start pin",
            "verification code", "otp", "pin", "ओटीपी", "पिन",
        )

        val DEFAULT_NAME_STOPWORDS: Set<String> = setOf(
            // statuses and function words
            "is", "has", "have", "will", "was", "are", "be", "arriving", "arrived", "assigned", "accepted",
            "cancelled", "canceled", "coming", "found", "reached", "waiting", "on", "at", "in", "to", "for", "the",
            "a", "an", "and", "or", "of", "your", "you", "us", "this", "that", "here", "now", "soon", "away",
            "rating", "rated", "details", "info", "verified", "partner", "captain", "driver", "otp", "pin",
            // brands, vehicles, payment
            "uber", "ola", "rapido", "ondc", "demo", "kestrel", "pepper", "tamarind", "cash", "upi", "paytm",
            "wallet", "money", "card", "auto", "bike", "cab", "cabs", "moto", "go", "premier", "xl", "mini", "prime",
            "sedan", "suv", "comfort", "pool", "share", "rental", "rentals", "intercity", "parcel", "taxi",
            // actions and screen words
            "call", "message", "chat", "safety", "support", "help", "cancel", "ride", "rides", "trip", "booking",
            "book", "booked", "pickup", "drop", "location", "min", "mins", "minutes", "km", "contact",
            // Hindi
            "है", "हैं", "आ", "रहे", "रहा", "रही", "आपके", "आपका", "आपकी", "आप", "आपको", "पास", "पहुंच", "पहुँच",
            "पहुंचे", "ने", "को", "का", "की", "के", "से", "में", "पर", "और", "रद्द", "कर", "दिया", "गया", "गए",
            "मिनट", "दूर", "राइड", "यात्रा", "ओटीपी", "पिन", "कैप्टन", "ड्राइवर",
        )

        val DEFAULT: MaskConfig = MaskConfig()
    }
}
