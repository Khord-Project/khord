package org.khord.shared.crypto

import com.ionspin.kotlin.crypto.util.LibsodiumRandom

/**
 * Cryptographically secure random bytes via libsodium's `randombytes_buf`,
 * which sources entropy from the OS CSPRNG (e.g. `getrandom(2)` on Linux,
 * `SecRandomCopyBytes` on Apple platforms, `BCryptGenRandom` on Windows).
 */
@OptIn(ExperimentalUnsignedTypes::class)
internal object Random {
    /** Return `n` cryptographically secure random bytes. */
    fun bytes(n: Int): ByteArray {
        require(n >= 0) { "n must be non-negative" }
        return LibsodiumRandom.buf(n).toByteArray()
    }
}

/** Zero-fill `this` in place — for wiping sensitive material after use. */
internal fun ByteArray.wipe() {
    fill(0)
}
