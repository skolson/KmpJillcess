package com.oldguy.jillcess.implementations

import com.oldguy.common.io.Charset
import com.oldguy.jillcess.Column
import com.oldguy.jillcess.ColumnType
import com.oldguy.jillcess.Row
import com.oldguy.jillcess.Table

class AccessColumn(name: String, index: Int) : Column(name, index) {
    lateinit var def: ColumnStructure
    val usedPagesMap = UsageMap()
    val freeSpacePagesMap = UsageMap()

    val isDecimal get() = def.isDecimal
    val isFixedLength get() = def.fixedLength
    val isVariableLength get() = !def.fixedLength

    constructor(columnStructureIn: ColumnStructure) :
            this(columnStructureIn.name, columnStructureIn.columnIndex.toInt()) {
        def = columnStructureIn
        mapColumnType()
    }

    private fun mapColumnType() {
        type = when (def.columnType) {
            ValueType.BooleanType -> ColumnType.Boolean
            ValueType.ByteType -> ColumnType.Byte
            ValueType.ShortType -> ColumnType.Short
            ValueType.IntegerType -> ColumnType.Int
            ValueType.Currency -> ColumnType.Decimal
            ValueType.FloatType -> ColumnType.Float
            ValueType.DoubleType -> ColumnType.Double
            ValueType.DateTimeType -> ColumnType.DateTime
            ValueType.SmallBinary -> ColumnType.Blob
            ValueType.SmallText -> ColumnType.String
            ValueType.OLE -> ColumnType.Blob
            ValueType.Memo -> ColumnType.String
            ValueType.Unknown13 -> ColumnType.Blob
            ValueType.GUID -> ColumnType.String
            ValueType.FixedPoint -> ColumnType.Decimal
            ValueType.Unknown17 -> ColumnType.Blob
            ValueType.Complex -> ColumnType.Blob
            ValueType.LongType -> ColumnType.Long
        }
    }

    suspend fun rowValue(
        record: DataRecord,
        endian: Endian,
        charset: Charset,
        jet: Jet,
        getLval: suspend (recordPointer: RecordPointer, isSingle: Boolean, remainingLength: Int) -> LvalDataPage
    ): AccessRowValue<*> {
        val accessValue = emptyRowValue(endian, charset, jet)
        when (accessValue) {
            is AccessRowValue.MemoValue -> accessValue.getLval = getLval
            is AccessRowValue.LargeBinaryValue -> accessValue.getLval = getLval
            else -> {}
        }
        accessValue.parse(record, def)
        return accessValue
    }

    fun emptyRowValue(
        endian: Endian,
        charset: Charset,
        jet: Jet,
        ascending: Boolean = true
    ): AccessRowValue<*> {
        return when (def.columnType) {
            ValueType.BooleanType -> AccessRowValue.BooleanValue()
            ValueType.ByteType -> AccessRowValue.ByteValue(ascending)
            ValueType.ShortType -> AccessRowValue.ShortValue(endian, ascending)
            ValueType.IntegerType -> AccessRowValue.IntValue(endian, ascending)
            ValueType.Currency -> AccessRowValue.DecimalValue(endian, ascending, jet)
            ValueType.FloatType -> AccessRowValue.FloatValue(endian, ascending)
            ValueType.DoubleType -> AccessRowValue.DoubleValue(endian, ascending)
            ValueType.DateTimeType -> AccessRowValue.DateTimeValue(endian, ascending)
            ValueType.SmallBinary -> AccessRowValue.BinaryValue(jet.isVersion4, ascending)
            ValueType.SmallText -> AccessRowValue.TextValue(charset, jet.isVersion4, ascending)
            ValueType.OLE -> AccessRowValue.LargeBinaryValue(endian)
            ValueType.Memo -> AccessRowValue.MemoValue(charset, endian, ascending)
            ValueType.Unknown13 -> AccessRowValue.BinaryValue(jet.isVersion4)
            ValueType.GUID -> AccessRowValue.GUIDValue(endian, ascending)
            ValueType.FixedPoint -> AccessRowValue.DecimalValue(endian, ascending, jet)
            ValueType.Unknown17 -> AccessRowValue.BinaryValue(jet.isVersion4)
            ValueType.Complex -> AccessRowValue.ComplexValue()
            ValueType.LongType -> AccessRowValue.LongValue(endian, ascending)
        }
    }
}

class AccessTable(
    name: String,
    val isVersion4: Boolean,
    val accessFile: AccessFile
) : Table(name, isSystemTable(name)) {
    private val pageSize = accessFile.pageSize
    private val usedPagesMap = UsageMap()
    private val freeSpacePagesMap = UsageMap()
    private val charset = accessFile.jet.charset

    private lateinit var definitionPageNumbers: List<Int>
    private lateinit var tableStructure: TableStructure
    private lateinit var cursor: AccessCursor

    /**
     * Used for an existing table being retrieved from the AccessFile
     */
    constructor(
        name: String,
        accessFile: AccessFile,
        tableStructureIn: TableStructure,
        definitionPageNumbersIn: List<Int>
    ) : this(name, accessFile.jet.isVersion4, accessFile) {
        tableStructure = tableStructureIn
        definitionPageNumbers = definitionPageNumbersIn
    }

    suspend fun initialize() {
        initColumns()
        usageMap(tableStructure.rowPageMap, usedPagesMap)
        usageMap(tableStructure.freeSpacePageMap, freeSpacePagesMap)
        initIndexes()
        cursor =
            AccessCursor(
                columns,
                accessFile
            )
    }

    fun getFixedLengthOffset(columnIndex: Short, record: DataRecord): Short {
        var offset: Short = 0
        for (column in tableStructure.columns) {
            if (!column.fixedLength || record.isNull(column.columnIndex)) continue
            if (columnIndex == column.columnIndex) return offset
            offset = (offset + column.length).toShort()
        }
        throw IllegalArgumentException("columnIndex $columnIndex, columnName: ${columns[columnIndex.toInt()].name} is not a fixed length column in table $name")
    }

    private suspend fun initColumns() {
        for (columnStructure in tableStructure.columns) {
            val col = AccessColumn(columnStructure)
            if (columnStructure.usedPagesPointer.isValid)
                usageMap(columnStructure.usedPagesPointer, col.usedPagesMap)
            if (columnStructure.freePagesPointer.isValid)
                usageMap(columnStructure.freePagesPointer, col.freeSpacePagesMap)
            columns.add(col)
        }
    }

    private fun initIndexes() {
        for (indexStruct in tableStructure.allIndexes) {
            val index = AccessIndex(this, indexStruct)
            indexes[index.name] = index
        }
    }

    private suspend fun usageMap(usageRecordPointer: RecordPointer, usageMap: UsageMap) {
        val mapPage = usageRecordPointer.pageNumber
        val recordNumber = usageRecordPointer.record
        val page = accessFile.readSidePage(mapPage)
        val struct = DataPageStructure(mapPage, accessFile.pageSize).parse(page)
        val rec = struct.recordOffsets[recordNumber.toInt()]
        val map = when (val mapType = page.get(rec.position.toInt())) {
            0.toUByte() -> InlineUsageMap(rec.length, isVersion4)
            1.toUByte() -> IndirectUsageMap(rec.length, accessFile.jet.pageVersion)
            else -> throw FileStructureException("Table usageMap page $page, record $rec has invalid mapType of $mapType")
        }
        page.position = rec.position.toInt()
        val content = map.parse(page)
        when (map) {
            is InlineUsageMap -> usageMap.content = map.usageMapContent
            is IndirectUsageMap -> usageMap.parseReferences(map, content, accessFile)
            else -> throw IllegalStateException("Map $map is an unexpected type")
        }
    }

    override suspend fun retrieveList(selectedColumns: List<String>): List<Row> {
        val list = emptyList<Row>().toMutableList()
        retrieveAll(selectedColumns) { _, row ->
            list.add(row)
        }
        return list
    }

    /**
     * lambda will be called once for each row in the table, in table order.
     * No index used in this version. It iterates the usedPagesMap to get the page numbers of each DataPage holding
     * records for this table.  For each DataPage, individual DataRecords are parsed from the page. Note that some
     * DataRecords contain RecordPointers
     */
    override suspend fun retrieve(
        selectedColumns: List<String>,
        oneRow: (rowCount: Int, Row) -> Boolean
    ): Int {
        var rowCount = 0
        usedPagesMap.forEach { it ->
            val dataBytes = accessFile.readPage(it)
            val dataPage = DataPageStructure(it, pageSize)
            dataPage.parse(dataBytes)
                .parseRows(dataBytes) { recordPointer -> fetchOverflowRecord(recordPointer) }
            dataPage.records.forEach {
                if (!oneRow(++rowCount, cursor.parseRow(selectedColumns, it)))
                    return rowCount
            }
        }
        return rowCount
    }

    override suspend fun retrieveAll(
        selectedColumns: List<String>,
        oneRow: (rowCount: Int, Row) -> Unit
    ): Int {
        return retrieve(selectedColumns) { rowCount, row ->
            oneRow(rowCount, row)
            true
        }
    }

    private suspend fun fetchOverflowRecord(recordPointer: RecordPointer): DataRecord {
        val page = accessFile.readSidePage(recordPointer.pageNumber)
        val record = DataPage(accessFile.jet).parsePage(page, recordPointer.pageNumber)
        return record.fetchRecord(page, recordPointer.record)
    }

    suspend fun retrieveAccess(selectedColumns: List<String>, oneRow: (AccessRow) -> Unit) {
        usedPagesMap.forEach { it ->
            val dataBytes = accessFile.readPage(it)
            val dataPage = DataPageStructure(it, pageSize)
            dataPage.parse(dataBytes)
                .parseRows(dataBytes) { recordPointer -> fetchOverflowRecord(recordPointer) }
            dataPage.records.forEach {
                val row = parseAccessRow(selectedColumns, it)
                oneRow(row)
            }
        }
    }

    private suspend fun parseAccessRow(
        selectedColumns: List<String>,
        record: DataRecord
    ): AccessRow {
        val row = AccessRow()
        columns
            .filter { selectedColumns.isEmpty() || selectedColumns.contains(it.name) }
            .forEach {
                row.add(
                    (it as AccessColumn).rowValue(
                        record,
                        accessFile.endian,
                        charset,
                        accessFile.jet
                    ) { recordPointer, isSingle, remainingLength ->
                        cursor.parseLvalPage(recordPointer, isSingle, remainingLength)
                    })
            }
        return row
    }

    internal fun findColumnById(id: Short): AccessColumn {
        columns.map { it as AccessColumn }.forEach {
            if (it.def.columnId == id) return it
        }
        throw IllegalArgumentException("Table $name has no column with ID: $id")
    }

    fun findColumnByName(name: String): AccessColumn {
        columns.map { it as AccessColumn }.forEach {
            if (it.def.name == name) return it
        }
        throw IllegalArgumentException("Table $name has no column with name: $name")
    }

    suspend fun retrieveRow(target: RecordPointer, row: (AccessRow) -> Unit) {
        val dataBytes = accessFile.readPage(target.pageNumber)
        val dataPage = DataPageStructure(target.pageNumber, pageSize)
        dataPage.parse(dataBytes)
            .parseRows(dataBytes) { recordPointer -> fetchOverflowRecord(recordPointer) }
        val rec = dataPage.records[target.record.toInt()]
        row(parseAccessRow(emptyList(), rec))
    }

    companion object {
        fun isSystemTable(tableName: String): Boolean {
            return tableName.startsWith(AccessDatabase.systemTablePrefix)
        }
    }
}
