package app.ridemate.ondc

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException

/** Why the `ondc_txn` listener stopped. */
enum class OndcListenFailure {
    /** Firestore rules refused the read (only the transaction's owner may read it). */
    DENIED,

    /** Firestore is unreachable. */
    OFFLINE,

    /** The free daily Firestore quota is used up (ONDC is unavailable until it resets, about 13:30 IST). */
    QUOTA,
    OTHER,
}

sealed interface OndcTxnSnapshot {
    /** The document, or null while the backend hasn't written it yet. */
    data class Doc(val doc: OndcTxnDoc?) : OndcTxnSnapshot

    data class Failed(val reason: OndcListenFailure) : OndcTxnSnapshot
}

/** The Firestore seam: a snapshot listener on `ondc_txn/{txnId}` (owner-only reads; clients never write). */
fun interface OndcTxnSource {
    /** Calls [onEvent] with every snapshot (on the main thread) until the result is closed. */
    fun listen(txnId: String, onEvent: (OndcTxnSnapshot) -> Unit): AutoCloseable
}

/** Production [OndcTxnSource] over the Firestore SDK. Firestore is created on first use (never in unit tests). */
class FirestoreOndcTxnSource(
    private val firestore: () -> FirebaseFirestore = { FirebaseFirestore.getInstance() },
) : OndcTxnSource {
    override fun listen(txnId: String, onEvent: (OndcTxnSnapshot) -> Unit): AutoCloseable {
        val registration = try {
            firestore().collection(COLLECTION).document(txnId).addSnapshotListener { snapshot, error ->
                when {
                    error != null -> onEvent(OndcTxnSnapshot.Failed(failureOf(error.code)))
                    snapshot == null || !snapshot.exists() -> onEvent(OndcTxnSnapshot.Doc(null))
                    else -> onEvent(OndcTxnSnapshot.Doc(OndcTxnDoc.fromMap(txnId, snapshot.data.orEmpty())))
                }
            }
        } catch (_: RuntimeException) {
            // Firebase not initialised or misconfigured: report it instead of crashing the session.
            onEvent(OndcTxnSnapshot.Failed(OndcListenFailure.OTHER))
            return AutoCloseable { }
        }
        return AutoCloseable { registration.remove() }
    }

    companion object {
        const val COLLECTION = "ondc_txn"

        fun failureOf(code: FirebaseFirestoreException.Code): OndcListenFailure = when (code) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED, FirebaseFirestoreException.Code.UNAUTHENTICATED -> OndcListenFailure.DENIED
            FirebaseFirestoreException.Code.UNAVAILABLE, FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> OndcListenFailure.OFFLINE
            FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED -> OndcListenFailure.QUOTA
            else -> OndcListenFailure.OTHER
        }
    }
}
