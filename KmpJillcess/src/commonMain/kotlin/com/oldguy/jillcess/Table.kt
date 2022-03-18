package com.oldguy.jillcess

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.soywiz.klock.DateTime

enum class ColumnType { String, Byte, Short, Int, Long, Float, Double, Decimal, DateTime, Boolean, Blob, Clob }

abstract class Column(val name: String, val index: Int) {
    var type: ColumnType = ColumnType.String
    var isNullable: Boolean = false
    var isPrimaryKey: Boolean = false
}

abstract class RowValue<T>(var isNull: Boolean, var value: T, val column: Column) {
    val nullString = "null"
    var nullableValue: T?
        get() = if (isNull) null else value
        set(inValue) {
            if (inValue == null) isNull = true else {
                isNull = false
                value = inValue
            }
        }
}

class StringValue(isNull: Boolean, value: String, column: Column) :
    RowValue<String>(isNull, value, column) {
    override fun toString(): String {
        return if (isNull) "" else value
    }
}

class DateTimeValue(isNull: Boolean, value: DateTime, column: Column) :
    RowValue<DateTime>(isNull, value, column) {

    override fun toString(): String {
        return if (isNull) nullString else value.format(formatter)
    }

    companion object {
        val formatter = JillcessDateTime.dateTimeFormatter
    }
}

class BinaryValue(isNull: Boolean, value: ByteArray, column: Column) :
    RowValue<ByteArray>(isNull, value, column) {
    override fun toString(): String {
        return if (isNull) nullString else {
            if (value.size > 10) "Binary ${value.size} bytes" else "0x${value.toHexString()}"
        }
    }
}

private const val FALSE = "false"
private const val TRUE = "true"

class BooleanValue(isNull: Boolean, value: Boolean, column: Column) :
    RowValue<Boolean>(isNull, value, column) {

    override fun toString(): String {
        return if (isNull) nullString else if (value) TRUE else FALSE
    }
}

class DecimalValue(isNull: Boolean, value: BigDecimal, column: Column) :
    RowValue<BigDecimal>(isNull, value, column) {

    override fun toString(): String {
        return if (isNull) nullString else value.toStringExpanded()
    }
}

class LongValue(isNull: Boolean, value: Long, column: Column) :
    RowValue<Long>(isNull, value, column) {

    override fun toString(): String {
        return if (isNull) nullString else value.toString(10)
    }
}

enum class IntSize(val length: Int) { Byte(1), Short(2), Integer(4) }
class IntValue(isNull: Boolean, value: Int, val length: IntSize = IntSize.Integer, column: Column) :
    RowValue<Int>(isNull, value, column) {

    override fun toString(): String {
        return if (isNull) nullString else value.toString(10)
    }
}

class FloatValue(isNull: Boolean, value: Float, column: Column) :
    RowValue<Float>(isNull, value, column) {

    override fun toString(): String {
        return if (isNull) nullString else value.toString()
    }
}

class DoubleValue(isNull: Boolean, value: Double, column: Column) :
    RowValue<Double>(isNull, value, column) {

    override fun toString(): String {
        return if (isNull) nullString else value.toString()
    }
}

class Row : Iterable<RowValue<out Any>> {
    val rowValues = emptyList<RowValue<out Any>>().toMutableList()
    private val nameMap = emptyMap<String, RowValue<out Any>>().toMutableMap()

    private fun noSuchColumn(columnName: String): IllegalArgumentException {
        return IllegalArgumentException("No such column $columnName")
    }

    private fun requireGotNull(columnName: String): IllegalArgumentException {
        return IllegalArgumentException("null value encountered for $columnName")
    }

    private fun requireGotNull(columnIndex: Int): IllegalArgumentException {
        return IllegalArgumentException("null value encountered for index $columnIndex")
    }

    fun isNull(columnIndex: Int): Boolean {
        verifyColumnIndex(columnIndex)
        return rowValues[columnIndex].isNull
    }

    fun isNull(columnName: String): Boolean {
        return nameMap[columnName]?.isNull
            ?: throw noSuchColumn(columnName)
    }

    fun add(colVal: RowValue<out Any>) {
        rowValues.add(colVal)
        nameMap[colVal.column.name] = colVal
    }

    fun getValue(columnIndex: Int): RowValue<out Any> {
        verifyColumnIndex(columnIndex)
        return rowValues[columnIndex]
    }

    fun getValue(columnName: String): RowValue<out Any> {
        return nameMap[columnName]
            ?: throw noSuchColumn(columnName)
    }

    fun getBoolean(columnIndex: Int): Boolean {
        verifyColumnIndex(columnIndex)
        return getBoolean(rowValues[columnIndex].column.name)
    }

    fun getBoolean(columnName: String): Boolean {
        return when (val value = getValue(columnName).value) {
            is Boolean -> value
            else -> throw IllegalArgumentException("$columnName is not Boolean")
        }
    }

    fun getInt(columnIndex: Int): Int? {
        verifyColumnIndex(columnIndex)
        return getInt(rowValues[columnIndex].column.name)
    }

    fun getInt(columnName: String): Int? {
        if (isNull(columnName)) return null
        return when (val value = getValue(columnName).value) {
            is Int -> value
            is Byte -> value.toInt()
            is Short -> value.toInt()
            else -> throw IllegalArgumentException("$columnName is not an Int, Short or Byte")
        }
    }

    fun requireInt(columnName: String): Int {
        return getInt(columnName) ?: throw requireGotNull(columnName)
    }

    fun requireInt(columnIndex: Int): Int {
        return getInt(columnIndex) ?: throw requireGotNull(columnIndex)
    }

    fun requireShort(columnName: String): Short {
        val v = getValue(columnName)
        return if (v is IntValue && !v.isNull && (v.length == IntSize.Short || v.length == IntSize.Byte)) v.value.toShort()
        else throw IllegalArgumentException("Could not get Short from $columnName, value ${v.value}")
    }

    fun getDecimal(columnIndex: Int): BigDecimal? {
        verifyColumnIndex(columnIndex)
        return getDecimal(rowValues[columnIndex].column.name)
    }

    fun getDecimal(columnName: String): BigDecimal? {
        if (isNull(columnName)) return null
        return when (val value = getValue(columnName).value) {
            is Int -> BigDecimal.fromInt(value)
            is Byte -> BigDecimal.fromByte(value)
            is Short -> BigDecimal.fromShort(value)
            is Long -> BigDecimal.fromLong(value)
            is Float -> BigDecimal.fromFloat(value)
            is Double -> BigDecimal.fromDouble(value)
            is BigDecimal -> value
            else -> throw IllegalArgumentException("$columnName is not numeric type")
        }
    }

    fun requireDecimal(columnIndex: Int): BigDecimal {
        return getDecimal(columnIndex) ?: throw requireGotNull(columnIndex)
    }

    fun requireDecimal(columnName: String): BigDecimal {
        return getDecimal(columnName) ?: throw requireGotNull(columnName)
    }

    fun getLong(columnIndex: Int): Long? {
        verifyColumnIndex(columnIndex)
        return getLong(rowValues[columnIndex].column.name)
    }

    fun getLong(columnName: String): Long? {
        if (isNull(columnName)) return null
        return when (val value = getValue(columnName).value) {
            is Int -> value.toLong()
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Long -> value
            else -> throw IllegalArgumentException("$columnName is not a Long, Int, Short or Byte")
        }
    }

    fun requireLong(columnName: String): Long {
        val v = getLong(columnName)
        return v ?: throw requireGotNull(columnName)
    }

    fun requireLong(columnIndex: Int): Long {
        return getLong(columnIndex) ?: throw requireGotNull(columnIndex)
    }

    fun getFloat(columnIndex: Int): Float? {
        verifyColumnIndex(columnIndex)
        return getFloat(rowValues[columnIndex].column.name)
    }

    fun getFloat(columnName: String): Float? {
        if (isNull(columnName)) return null
        return when (val value = getValue(columnName).value) {
            is Float -> value
            else -> throw IllegalArgumentException("$columnName is not a Float")
        }
    }

    fun requireFloat(columnName: String): Float {
        val v = getFloat(columnName)
        return v ?: throw requireGotNull(columnName)
    }

    fun getDouble(columnIndex: Int): Double? {
        verifyColumnIndex(columnIndex)
        return getDouble(rowValues[columnIndex].column.name)
    }

    fun getDouble(columnName: String): Double? {
        if (isNull(columnName)) return null
        return when (val value = getValue(columnName).value) {
            is Float -> value.toDouble()
            is Double -> value
            else -> throw IllegalArgumentException("$columnName is not a Float or Double")
        }
    }

    fun requireDouble(columnName: String): Double {
        val v = getDouble(columnName)
        return v ?: throw requireGotNull(columnName)
    }

    fun getDateTime(columnIndex: Int): DateTime? {
        verifyColumnIndex(columnIndex)
        return getDateTime(rowValues[columnIndex].column.name)
    }

    fun getDateTime(columnName: String): DateTime? {
        if (isNull(columnName)) return null
        return when (val value = getValue(columnName).value) {
            is DateTime -> value
            else -> throw IllegalArgumentException("$columnName is not a LocalDateTime")
        }
    }

    fun requireDateTime(columnName: String): DateTime {
        return getDateTime(columnName) ?: throw requireGotNull(columnName)
    }

    fun getBinary(columnIndex: Int): ByteArray {
        verifyColumnIndex(columnIndex)
        return getBinary(rowValues[columnIndex].column.name)
    }

    fun getBinary(columnName: String): ByteArray {
        if (isNull(columnName)) return ByteArray(0)
        return when (val value = getValue(columnName).value) {
            is ByteArray -> value
            else -> throw IllegalArgumentException("$columnName is not Binary")
        }
    }

    /**
     * Convenience method for getting a String from most types.  If null on any type, null is returned.
     * String type is returned as is.  DateTime is returned in default format for the locale, Boolean
     * is returned as "true" or "false", never null
     * Attempting this on a binary type throws an exception
     */
    fun getString(columnIndex: Int): String? {
        verifyColumnIndex(columnIndex)
        if (rowValues[columnIndex].isNull) return null
        return rowValues[columnIndex].toString()
    }

    fun getString(columnName: String): String? {
        return if (isNull(columnName)) null else nameMap[columnName]?.toString()
            ?: throw noSuchColumn(columnName)
    }

    fun requireString(columnName: String): String {
        val v = getString(columnName)
        return v ?: throw requireGotNull(columnName)
    }

    fun requireString(columnIndex: Int): String {
        verifyColumnIndex(columnIndex)
        val v = getString(columnIndex)
        return v ?: throw requireGotNull(columnIndex)
    }

    private fun verifyColumnIndex(columnIndex: Int) {
        if (columnIndex in 0 until rowValues.size)
            return
        throw IllegalArgumentException("Invalid row columnIndex $columnIndex, must be between 0 and ${rowValues.size - 1}")
    }

    override fun toString(): String {
        var s = "Row["
        for ((name, rowValue) in nameMap) {
            s = "$s$name=$rowValue,"
        }
        return "$s]"
    }

    override fun iterator(): Iterator<RowValue<out Any>> {
        return rowValues.iterator()
    }
}

/**
 * Tables have a name and a collection of Columns.  Since there is no SQL dialect etc, they also have simple
 * retrieve functions. One returns a List of Rows containing the content, and one invokes a lambda for each
 * Row of content.
 */
abstract class Table(val name: String, val systemCatalog: Boolean = false) {
    open val columns = emptyList<Column>().toMutableList()
    val indexes = emptyMap<String, Index>().toMutableMap()
    val references = emptyList<Table>().toMutableList()
    val primaryKey = emptyList<Column>().toMutableList()

    abstract suspend fun retrieveList(selectedColumns: List<String> = emptyList()): List<Row>
    abstract suspend fun retrieveAll(
        selectedColumns: List<String> = emptyList(),
        oneRow: (rowCount: Int, Row) -> Unit
    ): Int

    abstract suspend fun retrieve(
        selectedColumns: List<String> = emptyList(),
        oneRow: (rowCount: Int, Row) -> Boolean
    ): Int

    fun columnNames(): List<String> {
        return columns.map { it.name }
    }
}
