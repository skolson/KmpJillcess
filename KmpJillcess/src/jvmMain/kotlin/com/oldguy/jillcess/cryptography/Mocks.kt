package com.oldguy.jillcess.cryptography

/**
 * used only for unit testing of android implementations without instrumentation required.
 */
object Base64 {
    const val DEFAULT = -1
    fun decode(value: String, flags: Int): ByteArray {
        return java.util.Base64.getDecoder().decode(value)
    }
}
