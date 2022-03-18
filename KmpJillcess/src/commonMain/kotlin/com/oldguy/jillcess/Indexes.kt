package com.oldguy.jillcess

/**
 * An Index is owned by a table, and has access pointers for values of the one or more columns contained in the index.
 * Indexes also provide retrieval methods similar to those offered by a Table, but these use the index data to access
 * the table data based on the index values available. Records are retrieved in the order they are maintained within
 * the index.
 *
 */
abstract class Index(val table: Table) {
    val columns = emptyMap<String, Column>().toMutableMap()
    var name = ""

    fun add(col: Column) {
        columns[col.name] = col
    }
}
