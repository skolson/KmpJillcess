package com.oldguy.jillcess.implementations
/*
This file contains classes that perform encoding operations used during Access index searches.
It is adapted from the Jackcess library.  It uses translation tables loaded from configuration files.
These files are accessed using a Configuration instance.  The intent is that this instance will be
platform-specific in a multi-platform implementation.  Since the current file IO in this code is
JVM based, it will have to be changed to a platform independent file IO interface layer yet to be
built.
 */

abstract class CharHandler(val type: EncodingType) {
    open val inlineBytes: ByteArray get() = ByteArray(0)
    open val extraBytes: ByteArray get() = ByteArray(0)
    open val unprintableBytes: ByteArray get() = ByteArray(0)
    open val extraByteModifier: Byte get() = 0
    open val crazyFlag: UByte get() = 0u
}

private class SimpleCharHandler(override val inlineBytes: ByteArray) :
    CharHandler(EncodingType.SIMPLE)

private class InternationalCharHandler(
    override val inlineBytes: ByteArray,
    override val extraBytes: ByteArray
) : CharHandler(EncodingType.INTERNATIONAL)

private class UnprintableCharHandler(override val unprintableBytes: ByteArray) :
    CharHandler(EncodingType.UNPRINTABLE)

private class UnprintableExtCharHandler(override val extraByteModifier: Byte) :
    CharHandler(EncodingType.UNPRINTABLE_EXT)

private class InternationalExtCharHandler(
    override val inlineBytes: ByteArray,
    override val extraBytes: ByteArray,
    override val crazyFlag: UByte
) : CharHandler(EncodingType.INTERNATIONAL_EXT)

class IgnoredCharHandler :
    CharHandler(EncodingType.IGNORED)

class SurrogateCharHandler internal constructor() :
    CharHandler(EncodingType.IGNORED) {
    override val inlineBytes: ByteArray
        get() = throw IllegalStateException("Surrogate pair chars are not handled")
}

enum class EncodingType(val prefixCode: String) {
    SIMPLE("S") {
        override fun parseCodes(codeStrings: Array<String>): CharHandler {
            return parseSimpleCodes(codeStrings)
        }
    },
    INTERNATIONAL("I") {
        override fun parseCodes(codeStrings: Array<String>): CharHandler {
            return parseInternationalCodes(codeStrings)
        }
    },
    UNPRINTABLE("U") {
        override fun parseCodes(codeStrings: Array<String>): CharHandler {
            return parseUnprintableCodes(codeStrings)
        }
    },
    UNPRINTABLE_EXT("P") {
        override fun parseCodes(codeStrings: Array<String>): CharHandler {
            return parseUnprintableExtCodes(codeStrings)
        }
    },
    INTERNATIONAL_EXT("Z") {
        override fun parseCodes(codeStrings: Array<String>): CharHandler {
            return parseInternationalExtCodes(codeStrings)
        }
    },
    IGNORED("X") {
        override fun parseCodes(codeStrings: Array<String>): CharHandler {
            return IgnoredCharHandler()
        }
    };

    abstract fun parseCodes(codeStrings: Array<String>): CharHandler

    fun parseSimpleCodes(codeStrings: Array<String>): CharHandler {
        check(codeStrings.size == 1) {
            "Unexpected code strings " + listOf(*codeStrings)
        }
        return SimpleCharHandler(codesToBytes(codeStrings[0], true))
    }

    fun parseInternationalCodes(codeStrings: Array<String>): CharHandler {
        check(codeStrings.size == 2) {
            "Unexpected code strings " + listOf(*codeStrings)
        }
        return InternationalCharHandler(
            codesToBytes(codeStrings[0], true),
            codesToBytes(codeStrings[1], true)
        )
    }

    fun parseUnprintableCodes(codeStrings: Array<String>): CharHandler {
        check(codeStrings.size == 1) {
            "Unexpected code strings " + listOf(*codeStrings)
        }
        return UnprintableCharHandler(codesToBytes(codeStrings[0], true))
    }

    fun parseUnprintableExtCodes(codeStrings: Array<String>): CharHandler {
        check(codeStrings.size == 1) {
            "Unexpected code strings " + listOf(*codeStrings)
        }
        val bytes: ByteArray = codesToBytes(codeStrings[0], true)
        check(bytes.size == 1) {
            "Unexpected code strings " + listOf(*codeStrings)
        }
        return UnprintableExtCharHandler(bytes[0])
    }

    private val crazyCode1 = 0x02.toUByte()
    private val crazyCode2 = 0x03.toUByte()

    fun parseInternationalExtCodes(codeStrings: Array<String>): CharHandler {
        check(codeStrings.size == 3) {
            "Unexpected code strings " + listOf(*codeStrings)
        }
        val crazyFlag: UByte =
            if ("1" == codeStrings[2]) crazyCode1 else crazyCode2
        return InternationalExtCharHandler(
            codesToBytes(codeStrings[0], true),
            codesToBytes(codeStrings[1], false),
            crazyFlag
        )
    }

    private fun codesToBytes(
        codes: String,
        required: Boolean
    ): ByteArray {
        if (codes.isEmpty()) {
            check(!required) { "empty code bytes" }
            return ByteArray(0)
        }
        var work = codes
        if (codes.length % 2 != 0) {
            // stripped a leading 0
            work = "0$work"
        }
        val bytes = ByteArray(work.length / 2)
        for (i in bytes.indices) {
            val charIdx = i * 2
            bytes[i] = work.substring(charIdx, charIdx + 2).toInt(
                16
            ).toByte()
        }
        return bytes
    }
}

class IndexCodes(
    private val codes: Array<CharHandler>,
    private val extCodes: Array<CharHandler>
) {
    private val lastChar = 255u
    private val firstExtChar = 256u

    fun getCharHandler(c: Char): CharHandler {
        val uc = c.code.toUInt()
        if (uc <= lastChar) return codes[c.code]
        return extCodes[(uc - firstExtChar).toInt()]
    }
}
