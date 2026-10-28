package app.ridemate.ondc

/** A stand-in for the Firestore listener: tests write snapshots as the backend would. */
class FakeOndcTxnSource : OndcTxnSource {
    private val listeners = mutableMapOf<String, (OndcTxnSnapshot) -> Unit>()
    val listened = mutableListOf<String>()
    val closed = mutableListOf<String>()

    override fun listen(txnId: String, onEvent: (OndcTxnSnapshot) -> Unit): AutoCloseable {
        listened += txnId
        listeners[txnId] = onEvent
        return AutoCloseable {
            closed += txnId
            listeners.remove(txnId)
        }
    }

    fun isListening(txnId: String): Boolean = txnId in listeners

    /** The backend wrote `ondc_txn/{txnId}` (contracts §1.4 shape). */
    fun write(txnId: String, fields: Map<String, Any?>) {
        listeners[txnId]?.invoke(OndcTxnSnapshot.Doc(OndcTxnDoc.fromMap(txnId, fields)))
    }

    fun fail(txnId: String, reason: OndcListenFailure) {
        listeners[txnId]?.invoke(OndcTxnSnapshot.Failed(reason))
    }
}

/** Firestore-style documents for the mock seller (numbers arrive as Long, as the SDK gives them). */
object OndcDocs {
    val QUOTES: List<Map<String, Any?>> = listOf(
        mapOf("item_id" to "auto-1", "provider_name" to "Demo Seller Autos", "vehicle_class" to "auto", "price" to 92L, "eta_min" to 4L),
        mapOf("item_id" to "cab-1", "provider_name" to "Demo Seller Cabs", "vehicle_class" to "cab_economy", "price" to 210L, "eta_min" to 6L),
    )

    fun doc(
        state: String,
        quotes: List<Map<String, Any?>> = QUOTES,
        fee: Map<String, Any?>? = mapOf("amount" to 0L, "currency" to "INR"),
        driver: Map<String, Any?>? = null,
        selected: String? = null,
        demo: Boolean = true,
    ): Map<String, Any?> = mapOf(
        "uid" to "u1",
        "bpp_id" to "mock-bpp.ridemate",
        "bpp_uri" to "https://example.invalid/mock-bpp",
        "demo" to demo,
        "state" to state,
        "quotes" to quotes,
        "selected_item_id" to selected,
        "fee" to fee,
        "driver" to driver,
        "created_at" to "2026-10-28T10:00:00Z",
        "updated_at" to "2026-10-28T10:00:01Z",
    )
}
