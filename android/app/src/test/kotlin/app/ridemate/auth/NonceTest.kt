package app.ridemate.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class NonceTest {
    @Test
    fun `raw nonces are random and url safe`() {
        val a = Nonce.newRaw()
        val b = Nonce.newRaw()
        assertNotEquals(a, b)
        assertEquals(43, a.length)
        assertEquals(true, a.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun `sha256 hex matches a known vector`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Nonce.sha256Hex("abc"))
    }

    @Test
    fun `nonce claim is read from the jwt payload`() {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val token = "h." + enc.encodeToString("""{"nonce":"n-1","sub":"x"}""".toByteArray()) + ".s"
        assertEquals("n-1", Nonce.claimOf(token))
        assertNull(Nonce.claimOf("garbage"))
        assertNull(Nonce.claimOf("a.###.c"))
        assertNull(Nonce.claimOf("h." + enc.encodeToString("""{"sub":"x"}""".toByteArray()) + ".s"))
    }
}
