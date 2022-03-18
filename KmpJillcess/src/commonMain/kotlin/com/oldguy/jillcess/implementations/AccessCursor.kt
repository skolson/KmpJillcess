package com.oldguy.jillcess.implementations

import com.oldguy.jillcess.*

class AccessCursor constructor(
    private val columns: List<Column>,
    private val accessFile: AccessFile
) {
    val isVersion4 = accessFile.jet.isVersion4
    val endian = accessFile.endian
    val charset = accessFile.jet.charset
    val pageSize = accessFile.pageSize

    suspend fun parseLvalPage(
        recordPointer: RecordPointer,
        isSingle: Boolean,
        remainingLength: Int
    ): LvalDataPage {
        val bytes = accessFile.readSidePage(recordPointer.pageNumber)
        return LvalDataPage(
            recordPointer,
            pageSize,
            isSingle, remainingLength
        ).parse(bytes)
    }

    suspend fun parseRow(selectedColumns: List<String>, record: DataRecord): Row {
        val row = Row()
        columns
            .filter { selectedColumns.isEmpty() || selectedColumns.contains(it.name) }
            .forEach {
                val accessColVal = (it as AccessColumn).rowValue(
                    record,
                    endian,
                    charset,
                    accessFile.jet
                ) { recordPointer, isSingle, remainingLength ->
                    parseLvalPage(recordPointer, isSingle, remainingLength)
                }
                val colVal: RowValue<out Any>
                when (accessColVal) {
                    is AccessRowValue.BooleanValue -> colVal =
                        BooleanValue(accessColVal.isNull, accessColVal.value, it)
                    is AccessRowValue.ByteValue -> colVal =
                        IntValue(accessColVal.isNull, accessColVal.value.toInt(), IntSize.Byte, it)
                    is AccessRowValue.ShortValue -> colVal =
                        IntValue(accessColVal.isNull, accessColVal.value.toInt(), IntSize.Short, it)
                    is AccessRowValue.IntValue -> colVal =
                        IntValue(accessColVal.isNull, accessColVal.value, IntSize.Integer, it)
                    is AccessRowValue.LongValue -> colVal =
                        LongValue(accessColVal.isNull, accessColVal.value, it)
                    is AccessRowValue.DecimalValue -> colVal =
                        DecimalValue(accessColVal.isNull, accessColVal.value, it)
                    is AccessRowValue.FloatValue -> colVal =
                        FloatValue(accessColVal.isNull, accessColVal.value, it)
                    is AccessRowValue.DoubleValue -> colVal =
                        DoubleValue(accessColVal.isNull, accessColVal.value, it)
                    is AccessRowValue.DateTimeValue -> colVal =
                        DateTimeValue(accessColVal.isNull, accessColVal.value, it)
                    is AccessRowValue.GUIDValue -> colVal =
                        StringValue(accessColVal.isNull, accessColVal.value, it)
                    is AccessRowValue.TextValue -> colVal =
                        StringValue(accessColVal.isNull, accessColVal.value, it)
                    is AccessRowValue.MemoValue -> colVal =
                        StringValue(accessColVal.isNull, accessColVal.value, it)
                    is AccessRowValue.BinaryValue -> colVal =
                        BinaryValue(accessColVal.isNull, accessColVal.value, it)
                    is AccessRowValue.LargeBinaryValue -> colVal =
                        BinaryValue(accessColVal.isNull, accessColVal.value, it)
                    // todo ComplexValue will get parsing later
                    is AccessRowValue.ComplexValue -> colVal =
                        BinaryValue(accessColVal.isNull, accessColVal.value, it)
                }
                row.add(colVal)
            }
        return row
    }
}
