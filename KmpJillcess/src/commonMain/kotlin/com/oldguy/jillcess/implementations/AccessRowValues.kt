package com.oldguy.jillcess.implementations

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ionspin.kotlin.bignum.decimal.DecimalMode
import com.ionspin.kotlin.bignum.decimal.RoundingMode
import com.oldguy.common.io.ByteBuffer
import com.oldguy.common.io.Charset
import com.oldguy.common.io.UByteBuffer
import com.oldguy.jillcess.toHexString
import com.oldguy.jillcess.writeHexString
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.experimental.and
import kotlin.experimental.inv
import kotlin.experimental.xor

class AccessRow : Iterable<AccessRowValue<*>> {
    var rowValues = emptyList<AccessRowValue<*>>().toMutableList()

    fun add(colVal: AccessRowValue<*>) {
        rowValues.add(colVal)
    }

    override fun iterator(): Iterator<AccessRowValue<*>> {
        return rowValues.iterator()
    }

    operator fun get(i: Int): AccessRowValue<*> {
        return rowValues[i]
    }

    operator fun set(i: Int, value: Int) {
        when (val x = rowValues[i]) {
            is AccessRowValue.IntValue -> x.value = value
            is AccessRowValue.LongValue -> x.value = value.toLong()
            is AccessRowValue.ShortValue -> {
                if (value > Short.MAX_VALUE || value < Short.MIN_VALUE)
                    throw IllegalArgumentException("Int $value to a short would cause truncation")
                x.value = value.toShort()
            }
            else ->
                throw IllegalArgumentException("Int $value cannot be assigned to this type")
        }
    }

    operator fun set(i: Int, value: String) {
        when (val x = rowValues[i]) {
            is AccessRowValue.TextValue -> x.value = value
            is AccessRowValue.MemoValue -> x.value = value
            is AccessRowValue.GUIDValue -> x.value = value
            else ->
                throw IllegalArgumentException("String $value cannot be assigned to this type")
        }
    }
}

/**
 * All classes that map individual Access values on a row to/from kotlin data types
 */
sealed class AccessRowValue<VT>(val type: ValueType) {
    var isNull: Boolean = false
    val isTextual
        get() = when (type) {
            ValueType.SmallText, ValueType.Memo -> true
            else -> false
        }
    val isNumber
        get() = when (type) {
            ValueType.ShortType, ValueType.IntegerType, ValueType.Currency, ValueType.FloatType, ValueType.DoubleType, ValueType.FixedPoint -> true
            else -> false
        }

    protected abstract var internalValue: VT
    var value: VT
        get() = internalValue
        set(value) {
            internalValue = value
            isNull = false
        }
    var nullableValue: VT?
        get() = if (isNull) null else internalValue
        set(value) {
            if (value == null) isNull = true else {
                isNull = false
                internalValue = value
            }
        }
    var ascending: Boolean = true
        protected set
    var fixedLength: Boolean = true
        protected set

    open suspend fun parse(record: DataRecord, columnStructure: ColumnStructure) {
        isNull = record.isNull(columnStructure.columnIndex)
        fixedLength = columnStructure.fixedLength
    }

    internal fun createBigEndianBuffer(size: Int): ByteBuffer {
        return createBigEndianBuffer(size)
    }

    /**
     * Implementors must return a ByteArray that is empty if the RowValue isNull, or else translate
     * the content of the value to
     */
    abstract fun indexEncode(): ByteArray

    fun getFixedLengthContent(
        record: DataRecord,
        columnStructure: ColumnStructure
    ): UByteArray {
        return record.fixedFieldsContent.sliceArray(
            columnStructure.fixedOffset until columnStructure.fixedOffset + columnStructure.length
        )
    }

    fun getFixedLengthBytes(
        record: DataRecord,
        columnStructure: ColumnStructure,
        endian: Endian = Endian.Little
    ): UByteBuffer {
        return UByteBuffer(getFixedLengthContent(record, columnStructure), Endian.getOrder(endian))
    }

    /**
     * Verifies whether variable length content is available for this field, if not, isNull it set and nothing else
     * is done. If content is present, the bytesAvailable function is invoked containing the content
     */
    protected suspend fun getVariableLengthContent(
        record: DataRecord,
        columnStructure: ColumnStructure,
        bytesAvailable: suspend (bytes: UByteArray) -> Unit
    ) {
        if (columnStructure.fixedLength)
            throw IllegalArgumentException("Tried to retrieve Variable Length data from a fixed length column")
        val bytes = record.variableLengthContent(columnStructure.variableColumnIndex)
        if (bytes.isEmpty()) {
            isNull = true
        } else {
            isNull = false
            bytesAvailable(bytes)
        }
    }

    suspend fun getVariableLengthBytes(
        record: DataRecord,
        columnStructure: ColumnStructure,
        endian: Endian = Endian.Little
    ): UByteBuffer {

        var buf = UByteBuffer(0)
        getVariableLengthContent(record, columnStructure) {
            buf = UByteBuffer(it, Endian.getOrder(endian))
        }
        return buf
    }

    private val minimumLength = 12

    /**
     * parses variable length content that is supposed to be a Memo. Handles Inline, Single LVal, and Multiple LVal
     * Memo types.
     * @param record contains the variable length content
     * @param columnStructure is the column metadata used to parse this record
     * @param getLval lambda must retrieve Lval page indicated by the RecordPointer
     * @param memoContent lambda is invoked with a ByteBuffer containing all content no matter how stored
     */
    suspend fun parseMemo(
        record: DataRecord,
        columnStructure: ColumnStructure,
        endian: Endian,
        getLval: suspend (recordPointer: RecordPointer, isSingle: Boolean, remainingLength: Int) -> LvalDataPage,
        memoContent: (bytes: UByteBuffer) -> Unit
    ) {
        getVariableLengthContent(record, columnStructure) {
            val bytes = UByteBuffer(it, Endian.getOrder(endian))
            val memo = MemoField().parse(bytes)
            when {
                memo.inline -> {
                    if (it.size <= minimumLength)
                        throw FileStructureException("Memo field ${columnStructure.name} is Inline, but length of data is ${it.size}, must be > $minimumLength")
                    memoContent(bytes)
                }
                it.size != minimumLength -> {
                    throw FileStructureException("Memo field ${columnStructure.name} is not Inline, but length of data is ${it.size}, must be $minimumLength")
                }
                memo.singleLval -> {
                    val lvalPage = getLval(memo.lvalRecordPointer, true, memo.length)
                    memoContent(
                        UByteBuffer(lvalPage.content.toUByteArray(), Endian.getOrder(endian))
                    )
                }
                memo.multipleLval -> {
                    var remainingLength = memo.length
                    var nextRecord = memo.lvalRecordPointer
                    val buf = UByteBuffer(remainingLength, Endian.getOrder(endian))
                    while (remainingLength > 0) {
                        val lvalPage = getLval(nextRecord, false, remainingLength)
                        buf.put(lvalPage.content.toUByteArray())
                        remainingLength -= lvalPage.content.size
                        nextRecord = lvalPage.nextPage
                    }
                    buf.positionLimit(0, remainingLength)
                    memoContent(buf)
                }
            }
        }
    }

    fun flagByte(): Byte {
        return IndexEntry.EntryValueFlag.getEntryValueFlag(isNull, ascending).byteValue
    }

    fun flipFirstBit(bytes: ByteArray): ByteArray {
        if (bytes.isNotEmpty())
            bytes[0] = bytes[0] xor 0x80.toByte()
        return bytes
    }

    fun invert(bytes: ByteArray): ByteArray {
        for (i in bytes.indices) {
            bytes[i] = bytes[i].inv()
        }
        return bytes
    }

    fun invertDescending(bytes: ByteArray): ByteArray {
        if (!ascending) invert(bytes)
        return bytes
    }

    class BooleanValue : AccessRowValue<Boolean>(ValueType.BooleanType) {
        override var internalValue: Boolean = false

        override suspend fun parse(record: DataRecord, columnStructure: ColumnStructure) {
            // value from null mask, not from data, since Boolean is not nullable in access
            internalValue = !record.isNull(columnStructure.columnIndex)
        }

        override fun indexEncode(): ByteArray {
            val bytes = ByteArray(1)
            bytes[0] = if (internalValue) 0x00.toByte() else 0xFF.toByte()
            return invertDescending(bytes)
        }
    }

    /**
     * To properly parse if value is not null, must pass in the offset into the fixedLengthContent
     * as calculated by the column owner, typically a TableStructure
     */
    class ByteValue() : AccessRowValue<Byte>(ValueType.ByteType) {
        override var internalValue: Byte = 0

        constructor(ascending: Boolean) : this() {
            this.ascending = ascending
        }

        override suspend fun parse(record: DataRecord, columnStructure: ColumnStructure) {
            super.parse(record, columnStructure)
            internalValue = 0
            if (!isNull)
                internalValue = getFixedLengthBytes(record, columnStructure).byte.toByte()
        }

        override fun indexEncode(): ByteArray {
            val byte = ByteArray(1)
            byte[0] = internalValue
            return invertDescending(byte)
        }
    }

    /**
     * Maps a Short from and to the bytes in the fixed length data portion of the DataRecord
     */
    class ShortValue(private val endian: Endian) : AccessRowValue<Short>(ValueType.ShortType) {
        override var internalValue: Short = 0

        constructor(endian: Endian, ascending: Boolean) : this(endian) {
            this.ascending = ascending
        }

        override suspend fun parse(record: DataRecord, columnStructure: ColumnStructure) {
            super.parse(record, columnStructure)
            internalValue = 0
            if (!isNull) {
                internalValue = if (fixedLength)
                    getFixedLengthBytes(record, columnStructure, endian).short
                else
                    getVariableLengthBytes(record, columnStructure, endian).short
            }
        }

        override fun indexEncode(): ByteArray {
            val buf = createBigEndianBuffer(2)
            buf.short = internalValue
            return invertDescending(flipFirstBit(buf.contentBytes))
        }
    }

    /**
     * Maps an Int from and to the bytes in the fixed length data portion of the DataRecord
     *
     */
    class IntValue(private val endian: Endian) : AccessRowValue<Int>(ValueType.IntegerType) {
        override var internalValue: Int = 0

        constructor(endian: Endian, ascending: Boolean) : this(endian) {
            this.ascending = ascending
        }

        override suspend fun parse(record: DataRecord, columnStructure: ColumnStructure) {
            super.parse(record, columnStructure)
            internalValue = 0
            if (!isNull) {
                internalValue = if (fixedLength)
                    getFixedLengthBytes(record, columnStructure, endian).int
                else
                    getVariableLengthBytes(record, columnStructure, endian).int
            }
        }

        override fun indexEncode(): ByteArray {
            val buf = createBigEndianBuffer(4)
            buf.int = internalValue
            return invertDescending(flipFirstBit(buf.contentBytes))
        }
    }

    /**
     * Maps an Int from and to the bytes in the fixed length data portion of the DataRecord
     *
     */
    class LongValue(private val endian: Endian) : AccessRowValue<Long>(ValueType.LongType) {
        override var internalValue: Long = 0L

        constructor(endian: Endian, ascending: Boolean) : this(endian) {
            this.ascending = ascending
        }

        override suspend fun parse(record: DataRecord, columnStructure: ColumnStructure) {
            super.parse(record, columnStructure)
            internalValue = 0L
            if (!isNull) {
                internalValue = if (fixedLength)
                    getFixedLengthBytes(record, columnStructure, endian).long
                else
                    getVariableLengthBytes(record, columnStructure, endian).long
            }
        }

        override fun indexEncode(): ByteArray {
            val buf = createBigEndianBuffer(8)
            buf.long = internalValue
            return invertDescending(flipFirstBit(buf.contentBytes))
        }
    }

    /**
     * Maps an 8 byte Long from and to the bytes in the fixed length data portion of the DataRecord
     * and turns it into a BigDecimal, then sets the scale specified by the column
     *
     * NOTE - since Access encodes the currency/money type in 8 bytes, a
     */
    class DecimalValue(private val endian: Endian, private val jet: Jet) :
        AccessRowValue<BigDecimal>(ValueType.Currency) {
        override var internalValue = BigDecimal.ZERO

        constructor(endian: Endian, ascending: Boolean, jet: Jet) : this(endian, jet) {
            this.ascending = ascending
        }

        override suspend fun parse(record: DataRecord, columnStructure: ColumnStructure) {
            super.parse(record, columnStructure)
            internalValue = BigDecimal.ZERO
            if (!isNull) {
                val long = if (fixedLength)
                    getFixedLengthBytes(record, columnStructure, endian).long
                else
                    getVariableLengthBytes(record, columnStructure, endian).long
                internalValue = BigDecimal.fromLong(
                    long,
                    DecimalMode(columnStructure.scale.toLong(), RoundingMode.ROUND_HALF_CEILING)
                )
            }
        }

        override fun indexEncode(): ByteArray {
            val buf = createBigEndianBuffer(8)
            val work =
                internalValue.roundToDigitPositionAfterDecimalPoint(
                    4,
                    RoundingMode.ROUND_HALF_CEILING
                )
                    .multiply(BigDecimal.fromInt(10000))
            buf.long = work.longValue(true)

            // bit twiddling rules:
            // isAsc && !isNeg => setReverseSignByte             => FF 00 00 ...
            // isAsc && isNeg => flipBytes, setReverseSignByte   => 00 FF FF ...
            // !isAsc && !isNeg => flipBytes, setReverseSignByte => FF FF FF ...
            // !isAsc && isNeg => setReverseSignByte             => 00 00 00 ...

            // v2007 bit twiddling rules (old ordering was a bug, MS kb 837148):
            // isAsc && !isNeg => setSignByte 0xFF            => FF 00 00 ...
            // isAsc && isNeg => setSignByte 0xFF, flipBytes  => 00 FF FF ...
            // !isAsc && !isNeg => setSignByte 0xFF           => FF 00 00 ...
            // !isAsc && isNeg => setSignByte 0xFF, flipBytes => 00 FF FF ...
            val array = buf.contentBytes
            val isNegative = array[0] and 0x80.toByte() != 0.toByte()
            when (jet.version) {
                Jet.Version.Access97,
                Jet.Version.Access2000_2002_2003 -> {
                    if (isNegative == ascending) {
                        invert(array)
                    }
                    array[0] = if (isNegative) 0x00.toByte() else 0xFF.toByte()
                }
                Jet.Version.Access2007,
                Jet.Version.Access2010,
                Jet.Version.Access2016 -> {
                    array[0] = 0xFF.toByte()
                    if (isNegative == ascending) {
                        invert(array)
                    }
                }
            }
            return array
        }
    }

    /**
     * Maps a float from and to the bytes in the fixed length data portion of the DataRecord
     */
    class FloatValue(val endian: Endian) : AccessRowValue<Float>(ValueType.FloatType) {
        override var internalValue: Float = 0F

        constructor(endian: Endian, ascending: Boolean) : this(endian) {
            this.ascending = ascending
        }

        override suspend fun parse(record: DataRecord, columnStructure: ColumnStructure) {
            super.parse(record, columnStructure)
            internalValue = 0F
            if (!isNull) {
                internalValue = if (fixedLength)
                    getFixedLengthBytes(record, columnStructure, endian).float
                else
                    getVariableLengthBytes(record, columnStructure, endian).float
            }
        }

        override fun indexEncode(): ByteArray {
            val buf = createBigEndianBuffer(4)
            buf.float = internalValue

            // bit twiddling rules:
            // isAsc && !isNeg => flipFirstBit
            // isAsc && isNeg => flipBytes
            // !isAsc && !isNeg => flipFirstBit, flipBytes
            // !isAsc && isNeg => nothing
            val array = buf.contentBytes
            val isNegative = array[0] and 0x80.toByte() != 0.toByte()
            if (!isNegative) {
                flipFirstBit(array)
            }
            if (isNegative == ascending) {
                invert(array)
            }
            return array
        }
    }

    /**
     * Maps a double from and to the bytes in the fixed length data portion of the DataRecord
     */
    class DoubleValue(val endian: Endian) : AccessRowValue<Double>(ValueType.DoubleType) {
        override var internalValue: Double = 0.toDouble()

        constructor(endian: Endian, ascending: Boolean) : this(endian) {
            this.ascending = ascending
        }

        override suspend fun parse(record: DataRecord, columnStructure: ColumnStructure) {
            super.parse(record, columnStructure)
            internalValue = 0.toDouble()
            if (!isNull) {
                internalValue = if (fixedLength)
                    getFixedLengthBytes(record, columnStructure, endian).double
                else
                    getVariableLengthBytes(record, columnStructure, endian).double
            }
        }

        override fun indexEncode(): ByteArray {
            val buf = createBigEndianBuffer(8)
            buf.double = internalValue

            // bit twiddling rules:
            // isAsc && !isNeg => flipFirstBit
            // isAsc && isNeg => flipBytes
            // !isAsc && !isNeg => flipFirstBit, flipBytes
            // !isAsc && isNeg => nothing
            val array = buf.contentBytes
            val isNegative = array[0] and 0x80.toByte() != 0.toByte()
            if (!isNegative) {
                flipFirstBit(array)
            }
            if (isNegative == ascending) {
                invert(array)
            }
            return array
        }
    }

    /**
     * Maps a double from and to the bytes in the fixed length data portion of the DataRecord
     */
    class DateTimeValue(val endian: Endian) :
        AccessRowValue<Instant>(ValueType.DateTimeType) {
        override var internalValue: Instant = Clock.System.now()

        constructor(endian: Endian, ascending: Boolean) : this(endian) {
            this.ascending = ascending
        }

        override suspend fun parse(record: DataRecord, columnStructure: ColumnStructure) {
            super.parse(record, columnStructure)
            if (!isNull) {
                val wrkBuf = if (fixedLength)
                    getFixedLengthBytes(record, columnStructure, endian)
                else
                    getVariableLengthBytes(record, columnStructure, endian)
                internalValue = AccessDateTime.dateFromBytes(wrkBuf)
            }
        }

        override fun indexEncode(): ByteArray {
            val buf = createBigEndianBuffer(8)
            buf.double = AccessDateTime.doubleFromDate(internalValue)
            // same bit manipulations as a Double
            val array = buf.contentBytes
            val isNegative = array[0] and 0x80.toByte() != 0.toByte()
            if (!isNegative) {
                flipFirstBit(array)
            }
            if (isNegative == ascending) {
                invert(array)
            }
            return array
        }
    }

    /**
     * Maps a double from and to the bytes in the fixed length data portion of the DataRecord
     */
    class GUIDValue(val endian: Endian) : AccessRowValue<String>(ValueType.GUID) {
        override var internalValue: String = ""
        private val guidRegex =
            Regex("\\s*[{]?([\\p{XDigit}]{8})-([\\p{XDigit}]{4})-([\\p{XDigit}]{4})-([\\p{XDigit}]{4})-([\\p{XDigit}]{12})[}]?\\s*")

        constructor(endian: Endian, ascending: Boolean) : this(endian) {
            this.ascending = ascending
        }

        override suspend fun parse(record: DataRecord, columnStructure: ColumnStructure) {
            internalValue = ""
            super.parse(record, columnStructure)
            if (isNull) return
            val buf = if (fixedLength)
                getFixedLengthBytes(record, columnStructure, endian)
            else {
                getVariableLengthBytes(record, columnStructure, endian)
            }

            internalValue = "{${
                buf.uint.toString(16).padStart(8, '0')
            }-${
                buf.ushort.toString(16).padStart(4, '0')
            }-${
                buf.ushort.toString(16).padStart(4, '0')
            }-${
                buf.contentBytes.toHexString(8, 2)
            }-${buf.contentBytes.toHexString(10, 6)}}"
                .uppercase()
        }

        override fun indexEncode(): ByteArray {
            val match = guidRegex.find(internalValue) ?: error("GUID _value does not parse")
            if (match.groupValues.size != 5)
                throw IllegalStateException("$internalValue does not have 5 groups from regex")

            val buf = createBigEndianBuffer(16)
            buf.int = match.groupValues[0].toInt()
            buf.short = match.groupValues[1].toShort()
            buf.short = match.groupValues[2].toShort()
            buf.writeHexString(match.groupValues[3])
            buf.writeHexString(match.groupValues[4])
            return invertDescending(buf.contentBytes)
        }
    }

    /**
     * Determines if a text field is compressed, and if it is then un-compresses it. Compression method is strange.
     * Bytes are decoded using configured Charset
     */
    class TextValue(val charset: Charset, val isVersion4: Boolean) :
        AccessRowValue<String>(ValueType.SmallText) {
        override var internalValue: String = ""

        constructor(charset: Charset, isVersion4: Boolean, ascending: Boolean) : this(
            charset,
            isVersion4
        ) {
            this.ascending = ascending
        }

        override suspend fun parse(record: DataRecord, columnStructure: ColumnStructure) {
            internalValue = ""
            super.parse(record, columnStructure)
            if (fixedLength)
                throw IllegalStateException("Bug: need to support fixed length strings?")
            getVariableLengthContent(record, columnStructure) {
                internalValue = CompressedString(charset).decode(it.toByteArray())
            }
        }

        override fun indexEncode(): ByteArray {
            return if (CompressedString.isCompressible(internalValue))
                CompressedString(charset).compress(internalValue)
            else
                charset.encode(internalValue)
        }
    }

    /**
     * Straight binary data, no translation etc.
     */
    class BinaryValue(val isVersion4: Boolean) : AccessRowValue<ByteArray>(ValueType.SmallBinary) {
        override var internalValue = ByteArray(0)

        constructor(isVersion4: Boolean, ascending: Boolean) : this(isVersion4) {
            this.ascending = ascending
        }

        override suspend fun parse(record: DataRecord, columnStructure: ColumnStructure) {
            internalValue = ByteArray(0)
            super.parse(record, columnStructure)
            if (fixedLength)
                throw IllegalStateException("Bug: need to support fixed length binary?")
            getVariableLengthContent(record, columnStructure) { internalValue = it.toByteArray() }
        }

        override fun indexEncode(): ByteArray {
            val buf = createBigEndianBuffer(internalValue.size)
            buf.put(internalValue)
            return buf.contentBytes
        }
    }

    /**
     * Parses a Memo structure that either contains or points to text.
     * Determines if is is Inline, Single LVal or Multiple LVal.  callbacks are used to
     * request LVal page data.
     * If the length of the field is 0, isNull is set. If length is < 12, exception is thrown
     * Bytes are decoded using configured Charset
     */
    class MemoValue(
        val charset: Charset,
        val endian: Endian
    ) : AccessRowValue<String>(ValueType.Memo) {
        override var internalValue: String = ""
        lateinit var getLval: suspend (recordPointer: RecordPointer, isSingle: Boolean, remainingLength: Int) -> LvalDataPage

        constructor(charset: Charset, endian: Endian, ascending: Boolean) : this(charset, endian) {
            this.ascending = ascending
        }

        override suspend fun parse(record: DataRecord, columnStructure: ColumnStructure) {
            internalValue = ""
            super.parse(record, columnStructure)
            parseMemo(record, columnStructure, endian, getLval) {
                internalValue = CompressedString(charset)
                    .decode(it.getBytes(it.remaining).toByteArray())
            }
        }

        override fun indexEncode(): ByteArray {
            error("See AccessIndex indexEncodeText")
        }
    }

    /**
     * Determines if a text field is compressed, and if it is then un-compresses it. Compression method is strange.
     * Bytes are decoded using configured Charset
     */
    class LargeBinaryValue(val endian: Endian) : AccessRowValue<ByteArray>(ValueType.OLE) {
        override var internalValue: ByteArray = ByteArray(0)
        lateinit var getLval: suspend (recordPointer: RecordPointer, isSingle: Boolean, remainingLength: Int) -> LvalDataPage

        override suspend fun parse(record: DataRecord, columnStructure: ColumnStructure) {
            internalValue = ByteArray(0)
            super.parse(record, columnStructure)
            parseMemo(record, columnStructure, endian, getLval) {
                internalValue = it.contentBytes.toByteArray()
            }
        }

        override fun indexEncode(): ByteArray {
            val buf = createBigEndianBuffer(internalValue.size)
            buf.put(internalValue)
            return buf.contentBytes
        }
    }

    /**
     * Complex values are evidently like miniature tables
     */
    class ComplexValue : AccessRowValue<ByteArray>(ValueType.Complex) {
        override var internalValue: ByteArray = ByteArray(0)

        override suspend fun parse(record: DataRecord, columnStructure: ColumnStructure) {
            internalValue = ByteArray(0)
            super.parse(record, columnStructure)
            if (fixedLength)
                throw IllegalStateException("Bug: need to support fixed length CoplexValues?")
            getVariableLengthContent(record, columnStructure) {
                // todo parse complex value entry (see old code)
            }
        }

        override fun indexEncode(): ByteArray {
            val buf = createBigEndianBuffer(internalValue.size)
            buf.put(internalValue)
            return buf.contentBytes
        }
    }
}
