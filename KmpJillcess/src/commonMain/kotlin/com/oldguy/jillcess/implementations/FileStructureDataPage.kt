package com.oldguy.jillcess.implementations

import com.oldguy.common.io.BitSet
import com.oldguy.common.io.ByteBuffer
import com.oldguy.common.io.Charset
import com.oldguy.common.io.UByteBuffer

/**
 *
 *
 */

class CompressedString(val charset: Charset) {
    fun decode(bytes: ByteArray): String {
        val isCompressed = bytes.size > 2 &&
                bytes[0] == textCompressionHeader[0] &&
                bytes[1] == textCompressionHeader[1]
        return if (isCompressed) decompress(bytes, charset) else charset.decode(bytes)
    }

    /**
     * Produces ByteArray with a compression header and removes high order byte from each char (undoes
     * unicode).
     */
    fun compress(value: String): ByteArray {
        if (!isCompressible(value))
            throw IllegalArgumentException("value $value not compressible")

        val compressedBytes = ByteArray(textCompressionHeader.size + value.length)
        compressedBytes[0] = textCompressionHeader[0]
        compressedBytes[1] = textCompressionHeader[1]
        val bytes = charset.encode(value)
        if (bytes.size < value.length * 2)
            throw IllegalArgumentException("charset $charset not unicode")

        for (i in 0 until value.length) {
            compressedBytes[i + textCompressionHeader.size] = bytes[(i * 2) + 1]
        }
        return compressedBytes
    }

    /**
     * looks for null terminated segments of text, turns the "compressed" ones back into UTF-16 by
     * adding second null back to each byte
     */
    private fun decompress(bytes: ByteArray, charset: Charset): String {
        var start = textCompressionHeader.size
        var end = start
        var text = ""

        var inCompressedMode = true
        while (end < bytes.size) {
            if (bytes[end] == 0x00.toByte()) {
                text = text + decodeTextSegment(bytes, start, end, inCompressedMode, charset)
                inCompressedMode = !inCompressedMode
                ++end
                start = end
            } else {
                ++end
            }
        }
        // handle last segment
        return text + decodeTextSegment(bytes, start, end, inCompressedMode, charset)
    }

    private fun decodeTextSegment(
        bytes: ByteArray,
        start: Int,
        end: Int,
        inCompressedMode: Boolean,
        charset: Charset
    ): String {
        if (end <= start)
            return ""
        var buf = bytes
        val length = end - start
        if (inCompressedMode) {
            val tmpData = ByteArray(length * 2)
            var tmpIdx = 0
            for (i in start until end) {
                tmpData[tmpIdx] = buf[i]
                tmpIdx += 2
            }
            buf = tmpData
            return charset.decode(buf)
        }
        return charset.decode(buf.copyOfRange(start, start + length))
    }

    companion object {
        private val textCompressionHeader = byteArrayOf(0xFF.toByte(), 0XFE.toByte())
        private const val minCompressChar = 1.toChar()
        private const val maxCompressChar = 0xFF.toChar()

        fun isCompressible(text: String): Boolean {
            if (text.length <= textCompressionHeader.size) {
                return false
            }

            for (i in 0 until text.length) {
                val c = text[i]
                if (c < minCompressChar || c > maxCompressChar) {
                    return false
                }
            }
            return true
        }
    }
}

/**
 * Note that DataRecord objects are stored from end of page to beginning, so first row should
 * have the pageSize specified. Length of each field is the diff between the start of the last
 * field and the location of the new one.
 */
class DataRecordLocation(val previousPosition: Short) : PagePortion<DataRecordLocation>() {
    var position: Short = -1
        private set
    var length: Short = -1
        private set
    val end get() = position + length - 1
    var ignore: Boolean = false
        private set
    var recordPointer: Boolean = false

    /**
     * Since only 12 bits are needed position on a page, the 4 high order bits are used for flags
     */
    override fun parse(bytes: UByteBuffer): DataRecordLocation {
        val work = bytes.short.toInt()
        ignore = 0x8000 and work > 0
        recordPointer = 0x4000 and work > 0
        position = (work and 0x0FFF).toShort()
        length = if (ignore && position == 0.toShort()) 0
        else (previousPosition - position).toShort()
        return this
    }

    override fun setBytes(bytes: UByteBuffer) {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun toString(): String {
        return "DRL[position: $position, length: $length, ignore:$ignore, rp:$recordPointer]"
    }
}

class MemoField : PagePortion<MemoField>() {
    var length = 0
        private set
    val lvalRecordPointer = RecordPointer()
    var inline = false
        private set
    var singleLval = false
        private set
    var multipleLval = false
        private set

    override fun parse(bytes: UByteBuffer): MemoField {
        val work = bytes.int
        inline = work < 0
        singleLval = work and 0x40000000 > 0
        multipleLval = work shr (30) == 0
        length = work and 0x3FFFFFFF
        lvalRecordPointer.parse(bytes)
        bytes.int // unused
        if (inline)
            bytes.limit = bytes.position + length
        return this
    }

    override fun setBytes(bytes: UByteBuffer) {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }
}

/**
 * Data records after parsing contain fixedLengthContent that requires further parsing when
 * all fixed length field metadata is available from the owning table. Also can contain
 * variable length fields content, with data that is either inline content, or a MemoField
 * pointing to one or more LVAL pages. Parsing this also requires owning table metadata
 */
data class DataRecord(
    val recordOffset: Short,
    val recordLength: Short,
    val isVersion4: Boolean
) : PagePortion<DataRecord>() {
    var fieldCount: Short = 0
        private set
    var nullFieldsBits = BitSet(0)
        private set
    var variableLengthFieldCount: Short = 0
        private set
    var v3JumpTable = UByteArray(0)
        private set
    private val variableLengthFieldLengths = emptyList<Short>().toMutableList()
    private val variableLengthFieldOffsets = emptyList<Short>().toMutableList()

    var variableFieldsDataEndOffset: Short = 0
        private set

    var fixedFieldsContent = UByteArray(0)
        private set
    private var variableFieldsContent = UByteArray(0)

    override fun parse(bytes: UByteBuffer): DataRecord {
        fieldCount = if (isVersion4) bytes.short else bytes.byte.toShort()
        val fixedLengthContentStart = bytes.position

        val nullFieldsLength = ((fieldCount + 7) / 8)
        val nullFieldBitsOffset = recordOffset + recordLength - nullFieldsLength
        bytes.position = nullFieldBitsOffset
        bytes.limit = nullFieldBitsOffset + nullFieldsLength

        // ensure that the built bitset only has capacity equal to the number of columns
        val workBits = BitSet(bytes.toByteBuffer())
        nullFieldsBits = BitSet(fieldCount.toInt())
        for (i in 0..(fieldCount - 1)) nullFieldsBits.set(i, workBits.get(i))

        // variable fields metadata parsing
        if (isVersion4) {
            bytes.position = nullFieldBitsOffset - 2
            variableLengthFieldCount = bytes.short
            val offsetsLength = (variableLengthFieldCount + 1) * 2
            bytes.positionLimit(
                nullFieldBitsOffset - 2 - offsetsLength,
                offsetsLength
            )
            variableFieldsDataEndOffset = bytes.short
            for (i in 1..variableLengthFieldCount)
                variableLengthFieldOffsets.add(bytes.short)
        } else {
            variableLengthFieldCount = bytes.get(nullFieldBitsOffset - 1).toShort()
            v3JumpTable = UByteArray((recordLength - 1) / 256)
            val offsetsLength = v3JumpTable.size + variableLengthFieldCount + 1
            bytes.positionLimit(nullFieldBitsOffset - 1 - offsetsLength, offsetsLength)
            variableFieldsDataEndOffset = bytes.byte.toShort()
            for (i in 1..variableLengthFieldCount)
                variableLengthFieldOffsets.add(bytes.byte.toShort())
            bytes.limit = bytes.position + v3JumpTable.size - 1
            bytes.get(v3JumpTable)
            convertJumpToOffsets(variableLengthFieldOffsets)
        }

        var lastOffset = variableFieldsDataEndOffset
        var contentLength = 0
        for (offset in variableLengthFieldOffsets) {
            val length = lastOffset - offset
            variableLengthFieldLengths.add(length.toShort())
            contentLength += length
            lastOffset = offset
        }
        // reverse the variableLength offset lists to make access by variableColumnIndex easier
        variableLengthFieldOffsets.reverse()
        variableLengthFieldLengths.reverse()

        var fixedLengthContentLength = nullFieldBitsOffset - fixedLengthContentStart
        if (variableLengthFieldCount > 0) {
            variableFieldsContent = UByteArray(contentLength)
            bytes.positionLimit(
                recordOffset + variableLengthFieldOffsets.first().toInt(),
                contentLength
            )
            bytes.get(variableFieldsContent)
            fixedLengthContentLength =
                recordOffset + variableLengthFieldOffsets.first() - fixedLengthContentStart
        }
        bytes.positionLimit(fixedLengthContentStart, fixedLengthContentLength)
        fixedFieldsContent = bytes.getBytes(fixedLengthContentLength)
        return this
    }

    fun variableLengthContent(varColumnIndex: Short): UByteArray {
        var start = 0
        for (i in 0..varColumnIndex) {
            val length = variableLengthFieldLengths[i].toInt()
            if (i == varColumnIndex.toInt()) {
                return if (length == 0) UByteArray(0)
                else variableFieldsContent.sliceArray(start..(start + length - 1))
            }
            start += length
        }
        throw IllegalArgumentException("Invalid variable column index $varColumnIndex, s/b between 0 and ${variableLengthFieldCount - 1}")
    }

    private fun convertJumpToOffsets(variableLengthFieldOffsets: MutableList<Short>) {
        if (v3JumpTable.size == 0)
            return
        val jumpColumnIndexes = v3JumpTable.sliceArray(0 until v3JumpTable.indexOf(0xFF.toUByte()))
        var jumpIndex = jumpColumnIndexes.size - 1
        var jumpColIndex = jumpColumnIndexes[jumpIndex]
        var jumpBump = 0
        var columnIndex = variableLengthFieldOffsets.size - 1
        for (i in 0..(variableLengthFieldOffsets.size - 1)) {
            if (columnIndex >= jumpColIndex.toInt()) {
                jumpBump++
                jumpIndex--
                jumpColIndex = v3JumpTable[jumpIndex]
            }
            variableLengthFieldOffsets[columnIndex] =
                (variableLengthFieldOffsets[columnIndex] + (jumpBump * 256)).toShort()
            columnIndex--
        }
    }

    override fun setBytes(bytes: UByteBuffer) {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    /**
     * Uses BitSet to track nulls, indexed by column. If bit is set, field is not null. If bit not set, field is null
     */
    fun isNull(columnIndex: Short): Boolean {
        if (columnIndex < 0 || columnIndex >= nullFieldsBits.numberOfBits)
            throw IllegalArgumentException("Invalid columnIndex of $columnIndex for nullFieldsBits size of ${nullFieldsBits.numberOfBits}")
        return !nullFieldsBits[columnIndex.toInt()]
    }
}

/**
 * A Data Page typically contains one or more DataRecord structures. Each Page is associated with one table, and has
 * one or more rows of table data encoded in a complex binary form.  This class by design does not have access to the
 * table and column metadata required to fully parse each DataRecord. This class and the related DataRecord class only
 * parses the bytes to get the encoded data organized and accessible for later additional parsing when column and table
 * metadata is available.
 *
 * DataPages also have special cases, where they only contain LvalPage records, or UsageMaps. These cannot be parsed into
 * standard DataRecord collections. See the parse function for parsing the DataPage header, which is required procesing for
 * all DataPage types. See the parseRows function for retrieving DataRecords from the page, only for Data Pages
 * containing table rows
 *
 */
open class DataPageStructure(val pageNumber: Int, val pageSize: Int) :
    Page<DataPageStructure>(PageType.DataPage) {
    val isVersion4 = pageSize == PageSize.Version4.size
    var availableBytes: Short = 0
        private set
    var owningTablePage: Int = -1
        private set
    var lvalPage: Boolean = false
    var recordsOnThisPage: Short = 0
    val recordOffsets = emptyList<DataRecordLocation>().toMutableList()
    val records = emptyList<DataRecord>().toMutableList()

    /**
     * Only parses the data page header info, as Data pages can have either row content or inline usage map content,
     * as determined by caller (see Global UsageMap setMap of page 1)
     */
    override fun parse(bytes: UByteBuffer): DataPageStructure {
        val signature = bytes.short
        if (signature != 0x0101.toShort())
            throw FileStructureException("Page number $pageNumber has invalid data page signature $signature")
        availableBytes = bytes.short
        val ownerBytes = bytes.getBytes(4)
        lvalPage = ownerBytes.contentEquals(lVal)
        if (!lvalPage) {
            bytes.position -= 4
            owningTablePage = bytes.int
        }
        if (isVersion4)
            bytes.int
        recordsOnThisPage = bytes.short
        var endPosition = pageSize.toShort()
        for (i in 1..recordsOnThisPage) {
            val recordPosition = DataRecordLocation(endPosition).parse(bytes)
            recordOffsets.add(recordPosition)
            if (recordPosition.position > 0)
                endPosition = recordPosition.position
        }
        return this
    }

    /**
     * Fetch one row from this page, indicated by the rowIndex. Position and limit of the ByteBuffer will be set to
     * the location of the row.  Typically this is used for overflow records pointed to by a RecordPointer. If the fetched
     * record is marked as another overflow record, FileStructureException is thrown.
     */
    fun fetchRecord(bytes: UByteBuffer, rowIndex: Short): DataRecord {
        val offset = recordOffsets[rowIndex.toInt()]
        if (offset.recordPointer)
            throw FileStructureException("Page $pageNumber, record $rowIndex is marked record pointer. Record pointers don't point to another record pointer")
        bytes.positionLimit(offset.position, offset.length)
        return DataRecord(offset.position, offset.length, isVersion4).parse(bytes)
    }

    suspend fun parseRows(
        bytes: UByteBuffer,
        fetchRecord: suspend (record: RecordPointer) -> DataRecord?
    ): DataPageStructure {
        var rowNum = 0
        // Using recordOffsets, setMap the content for each record out of this page
        for (recordOffset in recordOffsets) {
            if (recordOffset.ignore) continue
            rowNum++
            bytes.positionLimit(recordOffset.position, recordOffset.length)
            if (recordOffset.recordPointer) {
                val pointer = RecordPointer().parse(bytes)
                fetchRecord(pointer)?.also { records.add(it) }
            } else
                records.add(
                    DataRecord(
                        recordOffset.position,
                        recordOffset.length,
                        isVersion4
                    ).parse(bytes)
                )
        }
        return this
    }

    fun getRecord(recordNumber: Short): DataRecord {
        if (recordNumber < 0 || recordNumber >= recordOffsets.size)
            throw IllegalArgumentException("recordNumber: $recordNumber is not between 0 and ${recordOffsets.size - 1}")
        return records[recordNumber.toInt()]
    }

    override fun setBytes(bytes: UByteBuffer) {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    companion object {
        val lVal = Jet.utf8Charset.encode("LVAL").toUByteArray()
    }
}

class LvalDataPage(
    val recordPointer: RecordPointer,
    pageSize: Int,
    val isSingle: Boolean,
    val remainingLength: Int
) : DataPageStructure(recordPointer.pageNumber, pageSize) {
    val nextPage = RecordPointer()
    lateinit var content: ByteArray

    override fun parse(bytes: UByteBuffer): LvalDataPage {
        super.parse(bytes)
        if (recordsOnThisPage < recordPointer.record)
            throw FileStructureException("LValDataPage has $recordsOnThisPage records, recordPointer: $recordPointer")
        val offset = recordOffsets[recordPointer.record.toInt()]
        bytes.positionLimit(offset.position, offset.length)
        if (!isSingle) {
            if (!lvalPage)
                throw FileStructureException("LValDataPage part of multiple pages should have Owner=LAVL")
            nextPage.parse(bytes)
        }
        var lengthThisPage = bytes.remaining
        if (isSingle && remainingLength > lengthThisPage) {
            throw FileStructureException("Single LVAL record $lengthThisPage bytes, requested length is $remainingLength")
        }
        if (remainingLength <= lengthThisPage) {
            lengthThisPage = remainingLength
        }
        content = bytes.getBytes(lengthThisPage).toByteArray()
        return this
    }

    override fun setBytes(bytes: UByteBuffer) {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }
}
