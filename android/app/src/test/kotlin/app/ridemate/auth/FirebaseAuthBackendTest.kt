package app.ridemate.auth

import com.google.firebase.FirebaseNetworkException
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Firebase exception mapping (the Firebase calls themselves are exercised on a device). */
class FirebaseAuthBackendTest {
    @Test
    fun `firebase exceptions map to auth failures`() {
        assertEquals(AuthFailure.NETWORK, FirebaseAuthBackend.failureOf(FirebaseNetworkException("x")))
        assertEquals(AuthFailure.OTHER, FirebaseAuthBackend.failureOf(IllegalStateException("x")))
    }

    @Test
    fun `backend exceptions carry no text beyond the failure kind`() {
        val e = AuthBackendException(AuthFailure.RECENT_LOGIN_REQUIRED, RuntimeException("secret@example.com"))
        assertEquals("RECENT_LOGIN_REQUIRED", e.message)
    }
}
