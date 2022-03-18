package com.oldguy.jillcess

import com.oldguy.jillcess.implementations.FileExtensions

open class SystemCatalog {
    val tables = emptyMap<String, Table>().toMutableMap()
    val linkedDatabaseNames = emptyList<String>().toMutableList()

    open fun close() {
        tables.clear()
        linkedDatabaseNames.clear()
    }

    fun table(tableName: String) =
        tables[tableName]
            ?: throw IllegalArgumentException("No such system catalong name: $tableName")
}

abstract class Database(val name: String, val systemCatalog: SystemCatalog) {

    var isOpen = false
        protected set
    val userCatalog = emptyMap<String, Table>().toMutableMap()

    abstract suspend fun open()

    abstract suspend fun create(fileExtensions: FileExtensions)

    open suspend fun close() {
        systemCatalog.close()
        userCatalog.clear()
    }

    abstract fun tableNameList(): List<String>

    /**
     * look up a user table with the specified name.
     * @param tableName case sensitive, must match
     * @return Table definition in this database
     * @throws IllegalArgumentException if table does not exist
     */
    abstract suspend fun table(tableName: String): Table

    abstract suspend fun linkedDatabase(name: String): Database

    fun systemCatalogList(): List<String> = systemCatalog.tables.keys.toList()

    fun systemCatalogTable(tableName: String): Table {
        return systemCatalog.table(tableName)
    }
}
