package com.oldguy.jillcess.implementations

/**
 * All classes in the "file" package are solely responsible for interacting at the ByteBuffer layer. Page IO is done
 * elsewhere, and any time IO is required during parsing, for example to resolve a RecordPointer or chain of RecordPointers,
 * function pointers are used to allow callers to provide lambdas handling the required IO.
 *
 * "file" package classes have only these responsibilities
 * 1 - handle all parsing of a ByteBuffer based on the binary format of Access data. Information about these formats is
 * from these non-Microsoft links:
 *      https://github.com/brianb/mdbtools/blob/master/HACKING
 *      http://jabakobob.net/mdb/
 *      https://jackcess.sourceforge.io/
 * Two format versions are supported, Jet3 and Jet4.  These versions of the specifications are very similar
 * 2 - handle all hardware "Endian" issues
 * 3 - perform charset decoding into Strings, from the UCS-2 (basically UTF-16LE) encoding used by Accesss, as well as
 * the funky/simple compression scheme used to reduce the disk footprint of UTF-16LE encoded strings
 * 4 - De-codings for all of the supported ValueType instances of row/column values supported by Access
 * 5 - decodings between Instant instances and Access proprietary/funky timestamp-encoded-in-a-double format
 *
 * See the "implementations" package for classes that use these "file" package classes to build AccessDatabase, AccessTable,
 * and UsageMap classes
 *
 * Long term goals for this package is to make it platform-neutral (iOS, Android, etc:
 *      Convert all ByteBuffer logic from Java (java.nio.ByteBuffer) to Kotlin multi-platform ByteBuffer
 *      Convert all Instant and BigDecimal to Kotlin native
 *
 * Two main class "trees":
 *      Page<> class and subclasses - Access Supports a variety of 2K (Jet3) or 4K (Jet4) page types.  Each type has a
 *      specific encoding. See the PageType enum class for a list of these
 *
 *      PagePortion<> class and subclasses. Each PageType has its own encoding scheme. Some like the DatabasePage from
 *      physical page 0 are simple and just have simple data elements.  Others like UsageMap, TableDefinition and DataPage
 *      have complex content. Logical breakdowns of that content in a page are subclasses of PagePortion, and the owning
 *      Page subclass manages its own collections of PagePortion<> content.
 *
 *      Page<> and PagePortion<> are both abstract classes that force all implementations to provide a standard
 *      parse(bytes:ByteBuffer) function. This function in each subclass is responsible for either directly parsing the
 *      bytes in the current page, or in delegating parsing to the appropriate PagePortion subclasses, as dictated by the
 *      Jet3/Jet4 format specification
 *
 *
 */

import com.oldguy.common.io.Buffer
import com.oldguy.common.io.UByteBuffer
import com.oldguy.jillcess.get3ByteInt
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.FormatStringsInDatetimeFormats
import kotlinx.datetime.format.byUnicodePattern
import kotlinx.datetime.periodUntil
import kotlinx.datetime.toInstant
import kotlin.experimental.and
import kotlin.math.absoluteValue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class AccessDataException(message: String) : Throwable(message)

enum class Endian {
    Little, Big;

    companion object {
        fun getOrder(endian: Endian): Buffer.ByteOrder {
            return when (endian) {
                Little -> Buffer.ByteOrder.LittleEndian
                Big -> Buffer.ByteOrder.BigEndian
            }
        }
    }
}

enum class PageType(val byte: Byte) {
    Database(0.toByte()),
    DataPage(1.toByte()),
    TableDefinition(2.toByte()),
    IntermediateIndex(3.toByte()),
    LeafIndex(4.toByte()),
    UsageMap(2.toByte()),
    NotSet(0xFF.toByte());

    companion object {
        fun fromByte(byte: UByte): PageType {
            return when (byte) {
                0.toUByte() -> Database
                1.toUByte() -> DataPage
                2.toUByte() -> TableDefinition
                3.toUByte() -> IntermediateIndex
                4.toUByte() -> LeafIndex
                5.toUByte() -> UsageMap
                else ->
                    throw FileStructureException("Invalid page type: ${byte.toString(16)}")
            }
        }
    }
}

abstract class PagePortion<T> {
    abstract fun parse(bytes: UByteBuffer): T
    abstract fun setBytes(bytes: UByteBuffer)
}

/**
 * Record pointers point to a specific record number on a physical page number which is always a DataPage
 */
data class RecordPointer(
    var record: Short = (-1).toShort(),
    var pageNumber: Int = -1
) : PagePortion<RecordPointer>() {
    val isValid get() = pageNumber > 0

    override fun parse(bytes: UByteBuffer): RecordPointer {
        if (bytes.remaining < 4)
            throw AccessDataException("Record pointer must be four bytes, only ${bytes.remaining} in buffer")
        record = bytes.byte.toShort()
        pageNumber = bytes.get3ByteInt()
        return this
    }

    override fun setBytes(bytes: UByteBuffer) {
    }
}

/**
 * Strings are a variable length set of bytes encoded in the formatCharset.  There is always a two byte (Short) length
 * followed by the encoded String content
 */
data class VariableLengthText(val jet: Jet) : PagePortion<VariableLengthText>() {
    var text: String = ""

    override fun parse(bytes: UByteBuffer): VariableLengthText {
        val length = if (jet.isVersion3) bytes.byte.toInt() else bytes.short.toInt()
        UByteArray(length).apply {
            bytes.get(this)
            text = jet.charset.decode(this.toByteArray())
        }
        return this
    }

    override fun setBytes(bytes: UByteBuffer) {
    }
}

data class RealIndexStructure(val isVersion4: Boolean, var indexRowCount: Int = 0) :
    PagePortion<RealIndexStructure>() {
    private var unknownA1: Int = 0
    private var unknownA2: Int = 0
    val columnFlags = emptyList<Pair<Int, Boolean>>().toMutableList()
    var firstIndexPage = 0
    var unique: Boolean = false
    var ignoreNulls: Boolean = false
    var required: Boolean = false
    var usageBitmapPointer = 0

    override fun parse(bytes: UByteBuffer): RealIndexStructure {
        if (bytes.remaining <= 12)
            throw FileStructureException("RealIndex structure is 12 bytes, only ${bytes.remaining} found")
        unknownA1 = bytes.int
        indexRowCount = bytes.int
        if (isVersion4)
            unknownA2 = bytes.int
        return this
    }

    fun parsePart2(bytes: UByteBuffer): RealIndexStructure {
        if (isVersion4) {
            val unknownB1 = bytes.int
            if (unknownB1 != 0 && unknownB1 != 1923)
                throw FileStructureException("Unexpected real index unknownB1: $unknownB1")
        }
        for (i in 1..10) {
            val columnId = bytes.short.toInt()
            val ascending = bytes.byte > 0u
            if (columnId >= 0)
                columnFlags.add(Pair(columnId, ascending))
        }
        usageBitmapPointer = bytes.int
        firstIndexPage = bytes.int
        val flags = if (isVersion4) bytes.short else bytes.byte.toShort()
        unique = flags and 0x01 > 0
        ignoreNulls = flags and 0x02 > 0
        required = flags and 0x08 > 0
        if (isVersion4) {
            bytes.int // val unknownB3 =
            bytes.int // val unknownB4 =
        }
        return this
    }

    override fun setBytes(bytes: UByteBuffer) {
    }
}

enum class IndexType {
    Normal, PrimaryKey, Logical;

    companion object {
        fun fromByte(indexTypeCode: Byte): IndexType {
            return when (indexTypeCode) {
                0.toByte() -> Normal
                1.toByte() -> PrimaryKey
                2.toByte() -> Logical
                else -> throw FileStructureException("Invalid index type value: $indexTypeCode")
            }
        }
    }
}

data class AllIndexStructure(val jet: Jet) : PagePortion<AllIndexStructure>() {
    var indexNumber = 0
    var referencedRealIndexNumber = 0
    var indexType: IndexType = IndexType.Normal
    var indexName = ""
    var realIndex: RealIndexStructure? = null
    var fkTableType: Byte = 0
    var fkIndexNumber = 0
    var fkTablePage = 0
    var updatesCascade = false
    var deletesCascade = false

    override fun parse(bytes: UByteBuffer): AllIndexStructure {
        if (jet.isVersion4)
            bytes.int
        indexNumber = bytes.int
        referencedRealIndexNumber =
            bytes.int // mdbhacking says this is logical's ref to real index. same as indexNumber on reals
        fkTableType = bytes.byte.toByte()
        fkIndexNumber = bytes.int
        fkTablePage = bytes.int
        if (bytes.byte > 0u) updatesCascade = true
        if (bytes.byte > 0u) deletesCascade = true
        indexType = IndexType.fromByte(bytes.byte.toByte())
        if (jet.isVersion4)
            bytes.int // val unknown
        return this
    }

    fun parseName(bytes: UByteBuffer): AllIndexStructure {
        indexName = VariableLengthText(jet).parse(bytes).text
        return this
    }

    override fun setBytes(bytes: UByteBuffer) {
    }
}

/**
 * Metadata for a column in a table definition.
 */
data class ColumnStructure(val inTableIndex: Int, val config: TableConfiguration) :
    PagePortion<ColumnStructure>() {
    var columnType: ValueType = ValueType.IntegerType
    private val isTextual
        get() = when (columnType) {
            ValueType.SmallText, ValueType.Memo -> true
            else -> false
        }
    val isDecimal get() = columnType == ValueType.Currency || columnType == ValueType.FixedPoint
    private val isComplex get() = columnType == ValueType.Complex
    var columnId: Short = 0
    var variableColumnIndex: Short = 0
    var columnIndex: Short = 0
    var fixedOffset: Short = 0

    /**
     * field size for fixed width columns, maximum length for variable length columns, 0 for MEMO and OLE
     */
    var length: Short = 0

    /*
    The following fields usage depends on columnType and file version
     */
    private var collation: Short = 0
    private var v4CollationVersion: Byte = 0
    private var version3CodePage: Short = 0
    private var precision: Byte = 0
    var scale: Byte = 0
    private var complexFieldTableDefPage: Int = 0

    /**
     * The following are flags describing the column. Some are only used in Version4
     */
    var fixedLength: Boolean = true
        private set
    private var nullable: Boolean = false
    private var autonumber: Boolean = false
    private var replication: Boolean = false
    private var automaticGUID: Boolean = false
    private var hyperlink: Boolean = false
    private var compressedUnicode: Boolean = false
    private var oleField: Boolean = false
    private var unknown4: Boolean = false
    private var unknown8: Boolean = false

    // The name is stored separately, but is set into this during table definition parsing
    var name = ""

    val usedPagesPointer = RecordPointer()
    val freePagesPointer = RecordPointer()

    /**
     * parses a ByteBuffer with its position set to the start of this structure's bytes. Note
     * that some gets in this logic are done to skip unknown fields, the results of the gets are unused,
     * they only serve to change the ByteBuffer position
     */
    override fun parse(bytes: UByteBuffer): ColumnStructure {
        columnType = ValueType.fromByte(bytes.byte)
        if (config.version == PageSize.Version4)
            bytes.int
        columnId = bytes.short

        // variableColumnIndex has a value for all fields, whether it's variable or not.
        variableColumnIndex = bytes.short
        columnIndex = bytes.short
        if (config.isVersion3) {
            when {
                isTextual -> {
                    collation = bytes.short
                    version3CodePage = bytes.short
                    bytes.short
                }
                isDecimal -> {
                    bytes.short
                    precision = bytes.byte.toByte()
                    scale = bytes.byte.toByte()
                    bytes.short
                }
                else -> bytes.position += 6
            }
        } else {
            when {
                isTextual -> {
                    collation = bytes.short
                    bytes.byte
                    v4CollationVersion = bytes.byte.toByte()
                }
                isDecimal -> {
                    precision = bytes.byte.toByte()
                    scale = bytes.byte.toByte()
                    bytes.short
                }
                isComplex -> {
                    complexFieldTableDefPage = bytes.int
                }
                else -> bytes.position += 4
            }
        }
        if (config.isVersion3) {
            val flags = bytes.byte.toInt()
            fixedLength = flags and 0x01 > 0
            nullable = flags and 0x02 > 0
            autonumber = flags and 0x03 > 0
            replication = flags and 0x10 > 0
            automaticGUID = flags and 0x40 > 0
            hyperlink = flags and 0x80 > 0
        } else {
            val flags = bytes.short.toInt()
            fixedLength = flags and 0x01 > 0
            nullable = flags and 0x02 > 0
            autonumber = flags and 0x03 > 0
            replication = flags and 0x10 > 0
            automaticGUID = flags and 0x40 > 0
            hyperlink = flags and 0x80 > 0
            compressedUnicode = flags and 0x0100 > 0
            oleField = flags and 0x1000 > 0
            unknown4 = flags and 0x4000 > 0
            unknown8 = flags and 0x8000 > 0
            bytes.int
        }
        val fo = bytes.short
        if (fixedLength)
            fixedOffset = fo

        length = bytes.short
        return this
    }

    override fun setBytes(bytes: UByteBuffer) {
    }

    override fun toString(): String {
        var sb =
            "Column[name: $name, indexInTable: $inTableIndex, id: $columnId, type: $columnType], columnIndex: $columnIndex"
        if (isDecimal) {
            sb = "$sb, precision: $precision, scale: $scale"
        }
        sb = if (fixedLength) {
            "$sb, length: $length, fixedOffset: $fixedOffset"
        } else {
            "$sb, maxLength: $length, variableColumnIndex: $variableColumnIndex"
        }
        return sb
    }
}

/**
 * Simple helper for assisting with concatenating the linked list of TableDefinition pages that some larger tables
 * require to hold all their metadata
 */
data class TableConfiguration(val jet: Jet) {
    val version: PageSize = jet.pageVersion
    val offsetNextTableDef = 4

    val isVersion3: Boolean get() = jet.isVersion3
    val isVersion4: Boolean get() = jet.isVersion4
}

abstract class Page<T>(var pageType: PageType) {
    abstract fun parse(bytes: UByteBuffer): T
    abstract fun setBytes(bytes: UByteBuffer)
}

/**
 * Table description metadata is held in one or more pages per table.  This is just the Table and Column and related
 * UsageMap data, not rows.  Since Table metadata can be large for a complex table, it can span one or more linked
 * TableDefinition pages.  This class assumes the caller has parsed any such linked list of TableDefinition pages
 * enough to concatenate all their content into one ByteBuffer which this class can then parse. Pretty much all
 * known fields in the format specs are parsed into usable properties.
 *
 * See class TableDefinitionPages for the main user of this class, it performs the aforementioned multiple table definition
 * pages linked-list traversal, then uses this class to parse the defintion data collected.
 */
private const val tableHeaderLength = 8

data class TableStructure(val config: TableConfiguration) :
    Page<TableStructure>(PageType.TableDefinition) {
    private var freeBytes: Short = 0
    private var definitionLength = 0
    private var rowCount = 0
    private var nextAutoNumber = 0
    private var nextAutoNumberIncrement = 1
    private var nextComplexAutoNumber = -1
    private var tableType: Byte = 0.toByte()
    private var nextColumnId: Short = 0
    private var variableColumnsCount: Short = 0
    private var columnsCount: Short = 0
    private var allIndexesCount = 0
    private var realIndexesCount = 0

    lateinit var rowPageMap: RecordPointer
    lateinit var freeSpacePageMap: RecordPointer

    private val realIndexes = emptyList<RealIndexStructure>().toMutableList()
    val allIndexes = emptyList<AllIndexStructure>().toMutableList()
    val columns = emptyList<ColumnStructure>().toMutableList()
    private val variableLengthColumnIds = emptyList<Short>().toMutableList()

    /**
     *
     */
    override fun parse(bytes: UByteBuffer): TableStructure {
        bytes.rewind()
        val signature = bytes.short
        if (signature != 0x0102.toShort())
            throw FileStructureException("Table definition signature invalid: $signature")
        freeBytes = if (config.version == PageSize.Version4) bytes.short else 0

        bytes.position = tableHeaderLength
        definitionLength = bytes.int
        if (config.isVersion4) bytes.int
        rowCount = bytes.int // sb offset 16 on V4
        nextAutoNumber = bytes.int

        if (config.isVersion4) {
            nextAutoNumberIncrement = bytes.int
            nextComplexAutoNumber = bytes.int
            bytes.int
            bytes.int
        }
        tableType = bytes.byte.toByte()
        nextColumnId = bytes.short
        variableColumnsCount = bytes.short
        columnsCount = bytes.short
        allIndexesCount = bytes.int
        realIndexesCount = bytes.int
        rowPageMap = RecordPointer().parse(bytes)
        freeSpacePageMap = RecordPointer().parse(bytes)

        for (i in 1..realIndexesCount) {
            realIndexes.add(RealIndexStructure(config.jet.isVersion4).parse(bytes))
        }

        for (i in 1..columnsCount) {
            columns.add(ColumnStructure(i - 1, config).parse(bytes))
        }

        // ensure columns list is ordered by columnIndex (should already be)
        for (i in 1..columnsCount) {
            columns[i - 1].name = VariableLengthText(config.jet).parse(bytes).text
        }

        columns.sortWith(compareBy { it.columnId })
        for (i in columns.indices) {
            columns[i].columnIndex = i.toShort()
            if (!columns[i].fixedLength)
                variableLengthColumnIds.add(columns[i].columnId)
        }

        for (index in realIndexes) {
            index.parsePart2(bytes)
        }

        for (i in 1..allIndexesCount) {
            val logicalIndex = AllIndexStructure(config.jet).parse(bytes)
            logicalIndex.realIndex = realIndexes[logicalIndex.referencedRealIndexNumber]
            allIndexes.add(logicalIndex)
        }
        for (index in allIndexes)
            index.parseName(bytes)

        /**
         * end of table def area has column IDs of variable length columns, except for SmallText and SmallBinary which evidently
         * always have their data inline. The columns below have used pages and free pages UsageMap record pointers.
         */
        var columnId: Short
        while (bytes.position < definitionLength + tableHeaderLength) {
            columnId = bytes.short
            if (columnId.toInt() >= 0) {
                if (!variableLengthColumnIds.contains(columnId))
                    throw FileStructureException("End of table def says columnId=$columnId should be variable length with usage maps")
                columns.filter { it.columnId == columnId }.forEach {
                    it.usedPagesPointer.parse(bytes)
                    it.freePagesPointer.parse(bytes)
                }
            } else {
                bytes.int
                bytes.int
            }
        }
        return this
    }

    override fun setBytes(bytes: UByteBuffer) {
    }
}
