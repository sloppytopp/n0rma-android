package io.github.sloppytopp.n0rma.data

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PIN storage and lockout, kept free of Android types so it can be unit-tested.
 * [read] returns a stored string (or null); [write] applies a batch of changes (a null value removes the key).
 */
class PinVault(private val read: (String) -> String?, private val write: (Map<String, String?>) -> Unit) {
    val hasPin: Boolean get() = read("pin_hash") != null

    fun save(pin: String?) {
        if (pin == null) {
            write(mapOf("pin_hash" to null, "pin_salt" to null, "pin_iter" to null, "pin_fails" to null, "pin_lock_until" to null))
            return
        }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        write(mapOf("pin_salt" to hex(salt), "pin_iter" to ITERATIONS.toString(), "pin_hash" to pbkdf2(pin, salt, ITERATIONS),
            "pin_fails" to null, "pin_lock_until" to null))
    }

    /** True if [pin] is right. A PIN saved by an older version (one plain SHA-256) is checked the old way, then re-saved with PBKDF2. */
    fun check(pin: String): Boolean {
        val saltHex = read("pin_salt") ?: return false
        val want = read("pin_hash") ?: return false
        val iterations = read("pin_iter")?.toIntOrNull()
        val ok = if (iterations == null) sha256(saltHex + pin) == want else pbkdf2(pin, unhex(saltHex), iterations) == want
        if (ok && iterations == null) save(pin)
        return ok
    }

    /** Milliseconds left on the lockout, 0 if the PIN may be tried. The counter and the deadline survive an app restart. */
    fun lockedForMs(now: Long): Long = ((read("pin_lock_until")?.toLongOrNull() ?: 0L) - now).coerceAtLeast(0L)

    /** Run one attempt. Returns true on success; five wrong tries in a row lock for 30 s. */
    fun attempt(pin: String, now: Long): Boolean {
        if (lockedForMs(now) > 0) return false
        if (check(pin)) { write(mapOf("pin_fails" to null, "pin_lock_until" to null)); return true }
        val fails = (read("pin_fails")?.toIntOrNull() ?: 0) + 1
        if (fails >= MAX_FAILS) write(mapOf("pin_fails" to null, "pin_lock_until" to (now + LOCK_MS).toString()))
        else write(mapOf("pin_fails" to fails.toString()))
        return false
    }

    companion object {
        const val ITERATIONS = 310_000
        const val MAX_FAILS = 5
        const val LOCK_MS = 30_000L

        private fun pbkdf2(pin: String, salt: ByteArray, iterations: Int): String =
            hex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.toCharArray(), salt, iterations, 256)).encoded)

        private fun sha256(s: String) = hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))
        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
