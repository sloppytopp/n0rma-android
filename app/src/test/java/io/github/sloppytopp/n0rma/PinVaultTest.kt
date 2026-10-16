package io.github.sloppytopp.n0rma

import io.github.sloppytopp.n0rma.data.PinVault
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinVaultTest {
    private val store = HashMap<String, String>()
    private fun vault() = PinVault(read = { store[it] }, write = { m -> m.forEach { (k, v) -> if (v == null) store.remove(k) else store[k] = v } })

    @Test fun correctAndWrongPin() {
        val v = vault(); v.save("1234")
        assertTrue(v.check("1234")); assertFalse(v.check("1235")); assertFalse(v.check(""))
        assertTrue(v.hasPin)
    }

    @Test fun savedWithPbkdf2NotPlainSha() {
        vault().save("1234")
        assertEquals(PinVault.ITERATIONS.toString(), store["pin_iter"])
        assertEquals(32, store["pin_salt"]!!.length)   // 16-byte salt, hex
        assertEquals(64, store["pin_hash"]!!.length)
        val plain = MessageDigest.getInstance("SHA-256").digest((store["pin_salt"] + "1234").toByteArray()).joinToString("") { "%02x".format(it) }
        assertTrue(store["pin_hash"] != plain)
    }

    @Test fun removingPinClearsEverything() {
        val v = vault(); v.save("1234"); v.attempt("0000", 1_000); v.save(null)
        assertFalse(v.hasPin); assertTrue(store.isEmpty())
    }

    @Test fun oldSha256PinStillWorksAndIsUpgraded() {
        val salt = "0011223344556677"
        store["pin_salt"] = salt
        store["pin_hash"] = MessageDigest.getInstance("SHA-256").digest((salt + "4321").toByteArray()).joinToString("") { "%02x".format(it) }
        val v = vault()
        assertFalse(v.check("1111")); assertNull(store["pin_iter"])       // a wrong try does not migrate
        assertTrue(v.check("4321"))
        assertNotNull(store["pin_iter"]); assertEquals(32, store["pin_salt"]!!.length)
        assertTrue(v.check("4321")); assertFalse(v.check("1111"))          // still right after the upgrade
    }

    @Test fun fiveWrongTriesLockAndTheLockSurvivesARestart() {
        vault().save("1234")
        val v = vault()
        repeat(4) { assertFalse(v.attempt("0000", 1_000L)); assertEquals(0L, v.lockedForMs(1_000L)) }
        assertFalse(v.attempt("0000", 1_000L))
        assertEquals(PinVault.LOCK_MS, v.lockedForMs(1_000L))
        val afterRestart = vault()                                      // new object over the same stored values
        assertEquals(PinVault.LOCK_MS - 5_000, afterRestart.lockedForMs(6_000L))
        assertFalse(afterRestart.attempt("1234", 6_000L))                // right PIN is refused while locked
        assertTrue(afterRestart.attempt("1234", 1_000L + PinVault.LOCK_MS))
        assertEquals(0L, afterRestart.lockedForMs(1_000L + PinVault.LOCK_MS))
    }

    @Test fun failedCounterSurvivesARestartAndResetsOnSuccess() {
        vault().save("1234")
        repeat(3) { vault().attempt("0000", 1_000L) }
        assertEquals("3", store["pin_fails"])
        assertTrue(vault().attempt("1234", 2_000L)); assertNull(store["pin_fails"])
        repeat(4) { vault().attempt("0000", 3_000L) }
        assertEquals(0L, vault().lockedForMs(3_000L))                    // earlier fails did not carry over
    }
}
