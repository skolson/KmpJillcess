package com.oldguy.jillcess

import com.oldguy.common.io.Buffer
import com.oldguy.common.io.ByteBuffer
import com.oldguy.common.io.UByteBuffer
import kotlin.math.min

/**
 * ByteArray extension functions used by logic
 */

fun ByteArray.toHexString() = asUByteArray().joinToString("") { it.toString(16).padStart(2, '0') }

fun ByteArray.toHexString(offset: Int = 0, length: Int = size) =
    sliceArray(offset until offset + length).asUByteArray()
        .joinToString("") { it.toString(16).padStart(2, '0') }

fun UByteArray.toHexString(offset: Int = 0, length: Int = size) =
    sliceArray(offset until offset + length)
        .joinToString("") { it.toString(16).padStart(2, '0') }

/**
 * ByteArray comparison. Returns -1 if this < other, 0 if content is equal, 1 if this > other .
 * When lengths of arrays differ, return -1 if this is shorter than other, 1 if longer than other.
 */
fun ByteArray.compareTo(other: ByteArray): Int {
    if (this.contentEquals(other)) return 0
    val len = min(this.size, other.size)
    for (pos in 0 until len) {
        if (get(pos) < other[pos]) return -1
        if (get(pos) > other[pos]) return 1
    }
    return if (size < other.size) -1 else if (size > other.size) 1 else 0
}

/**
 * ByteBuffer extensions
 */

fun ByteBuffer.writeHexString(hexStr: String) {
    val hexChars = hexStr.toCharArray()
    if (hexChars.size % 2 != 0) {
        throw IllegalArgumentException("Hex string length must be even")
    }
    var i = 0
    while (i < hexChars.size) {
        val tmpStr = hexChars.concatToString(i, i + 2)
        byte = tmpStr.toInt(16).toByte()
        i += 2
    }
}

/**
 * Access has record pointers and an index entry item where an Int is represented by 3 bytes. This
 * function gets an int from three bytes starting at position, using Endian order.
 *
 * Position is incremented by 3 bytes.
 *
 * @throws IllegalStateException if remaining is less than 3 bytes
 */
fun UByteBuffer.get3ByteInt(): Int {
    if (remaining < 3)
        throw IllegalStateException("Position $position has remaining $remaining bytes, 3 are required")
    val rtn = when (order) {
        Buffer.ByteOrder.LittleEndian -> (getElementAsInt(position + 2) shl 16) or
                (getElementAsInt(position + 1) shl 8) or
                (getElementAsInt(position))
        Buffer.ByteOrder.BigEndian -> (getElementAsInt(position) shl 16) or
                (getElementAsInt(position + 1) shl 8) or
                (getElementAsInt(position + 2))
    }
    position += 3
    return rtn
}
