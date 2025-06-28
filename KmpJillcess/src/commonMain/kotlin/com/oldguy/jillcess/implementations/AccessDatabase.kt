package com.oldguy.jillcess.implementations

import com.oldguy.common.io.File
import com.oldguy.common.io.UByteBuffer
import com.oldguy.common.io.charsets.Charset
import com.oldguy.jillcess.Database
import com.oldguy.jillcess.Row
import com.oldguy.jillcess.SystemCatalog
import com.oldguy.jillcess.Table
import com.oldguy.jillcess.configuration.Configuration
import com.oldguy.jillcess.implementations.AccessDatabase.Companion.systemCatalogName

/**
 * Metadata about the database. First table is MSysObjects in Page 2 of any database.  It has rows for
 * Tables, forms, queries etc.
 * ParentId: Int - points to the logical owner's/parent Id of current row.
 * Id:
 *      0x0f000001 - Tables row - this is a 'logical' parent, not a physical page in file
 *      0x0f000002 - Databases row - this is a 'logical' parent, not a physical page in file
 *      0x0f000003 - Relationships row - this is a 'logical' parent, not a physical page in file
 *      0x0f000006 - MSysDb row - this is a 'logical' parent, not a physical page in file
 *
 *  ParentId:
 *      0x0f000000 - no entry with this ID in the MSysCatalog, seems to be a special case value to server
 *          as parent for the 'logical' Ids listed above
 *      0x80nnnnnn - high order bit set indicates linked resource
 */
class AccessSystemCatalog : SystemCatalog() {
    enum class Type(val value: Int) {
        LocalTable(1), MSysDb(2), LogicalParent(3),
        Query(5), LinkedTable(6),
        Databases(0x800B), Form(0x8001), Report(0x8004),
        Unknown7(7), Unknown8(8);

        companion object {
            fun getType(value: Int): Type {
                return when (value) {
                    1 -> LocalTable
                    2 -> MSysDb
                    3 -> LogicalParent
                    5 -> Query
                    6 -> LinkedTable
                    7 -> Unknown7
                    8 -> Unknown8
                    -32757 -> Databases
                    -32768 -> Form
                    -32764 -> Report
                    else -> throw IllegalArgumentException("Unsupported System Catalog type $value")
                }
            }
        }
    }

    class CatalogRow(
        val isVersion4: Boolean,
        val charset: Charset,
        val row: Row
    ) {
        val type = Type.getType(row.requireShort(catalogType).toInt())
        val id = row.requireInt(ID)
        val name = row.requireString(NAME)
        val parentId = row.requireInt(PARENT_ID)

        val isLinkedDb = !row.isNull(DATABASE)
        val linkedDb get() = row.requireString(DATABASE).substringAfterLast('\\')
        val foreignName = row.getString(FOREIGN_NAME) ?: name

        val isTable = when (type) {
            Type.LocalTable, Type.LinkedTable -> true
            else -> false
        }
        val isSystemTable = isTable && (type == Type.LocalTable || type == Type.LinkedTable) &&
                name.startsWith(AccessDatabase.systemTablePrefix)

        val isRootChild = parentId == rootParentId

        private lateinit var lvProperties: LvProperties

        fun isChild(parentId: Int): Boolean {
            return row.requireInt(PARENT_ID) == parentId
        }

        fun parseLvProp(endian: Endian): CatalogRow {
            lvProperties = LvProperties(isVersion4, charset)
            if (row.isNull(LVPROP)) return this
            val content = row.getBinary(LVPROP)
            if (content.size < 6)
                return this
            lvProperties.parse(UByteBuffer(content.toUByteArray(), Endian.getOrder(endian)))
            return this
        }

        companion object {
            internal const val ID = "Id"
            private const val DATABASE = "Database"
            private const val NAME = "Name"
            private const val PARENT_ID = "ParentId"
            private const val FOREIGN_NAME = "ForeignName"
            private const val catalogType = "Type"
            private const val rootParentId = 0xF000000
            private const val LVPROP = "LvProp"

            val basicColumns = listOf(
                ID, PARENT_ID, NAME, catalogType, DATABASE, FOREIGN_NAME,
                "Owner", "DateCreate", "DateUpdate", "Flags"
            )
        }
    }

    private val parents = emptyMap<String, Int>().toMutableMap()
    val systemTables = emptyMap<String, CatalogRow>().toMutableMap()

    suspend fun parseCatalog(
        isVersion4: Boolean,
        charset: Charset,
        endian: Endian,
        parseUserTable: (name: String, row: CatalogRow) -> Unit
    ) {
        val catalog = tables[systemCatalogName]
            ?: throw IllegalStateException("Cannot find $systemCatalogName")
        val rows = catalog.retrieveList().map {
            CatalogRow(isVersion4, charset, it).parseLvProp(endian)
        }
        rows.filter { it.isRootChild }
            .forEach {
                if (it.type != Type.LogicalParent)
                    throw IllegalStateException("Expected rootParentId to be ${Type.LogicalParent}, found ${it.type}")
                parents[it.name] = it.id
            }
        val tablesId = parents[logicalParentTables]
            ?: throw FileStructureException("No MSysObjects row for Tables logical parent")
        rows.filter { it.parentId == tablesId }
            .forEach {
                if (it.isSystemTable)
                    systemTables[it.name] = it
                else if (it.isTable) {
                    parseUserTable(it.name, it)
                    if (it.isLinkedDb) {
                        linkedDatabaseNames.add(it.linkedDb)
                    }
                }
            }
    }

    override fun close() {
        super.close()
        parents.clear()
        systemTables.clear()
    }

    companion object {
        internal const val systemCatalogPage = 2
        private const val logicalParentTables = "Tables"
    }
}

class AccessDatabase(
    val filePath: String,
    private val configuration: Configuration,
    private val mode: DatabaseFile.Mode = DatabaseFile.Mode.Read,
    val password: String = ""
) : Database(
    File(filePath).nameWithoutExtension,
    AccessSystemCatalog()
) {
    private var defaultCodePage: Short = 0
    var defaultSortOrder: SortOrder = SortOrder.GeneralSortOrder
    val accessSystemCatalog = systemCatalog as AccessSystemCatalog
    val linkedDatabases = emptyMap<String, AccessDatabase>().toMutableMap()

    lateinit var jet: Jet private set
    lateinit var accessFile: AccessFile

    var version: Int = 0
    val charset get() = jet.charset

    /*
    Disabled the timezone stuff while using klock since it doesn't support them explicitly. Klock
    can only do offset times. Also klock doesn't do different calendars, including proleptic
    Gregorian that access uses for dates before year 1582
    var timeZone: DateTimeZone = DateTimeZone.getDefault()
        set(value) {
            _chronology = GregorianChronology.getInstance(value)
        }
    private var _chronology = GregorianChronology.getInstance(timeZone)

     * Once database is changed to jodatime, change this Calendar thing to use _chronology
     * also add DateTime and Date formatters set into database.
     * Note that this is done to accomodate Access usage of a proleptic Gregorian calendar for
     * old times, that does not go to Julian in 1582 (or whenever :-)
    private val cal = (GregorianCalendar.getInstance(TimeZone.getTimeZone(timeZone.id)) as GregorianCalendar).also {
        it.gregorianChange = Date(Long.MIN_VALUE)
    }
     */
    val userTableRows = emptyMap<String, AccessSystemCatalog.CatalogRow>().toMutableMap()

    override suspend fun open() {
        if (isOpen) return
        accessFile = AccessFile(this, filePath, mode)
        accessFile.open()

        accessFile.parsePage(AccessSystemCatalog.systemCatalogPage)

        accessSystemCatalog.parseCatalog(
            accessFile.jet.isVersion4,
            charset,
            accessFile.endian
        ) { tableName, row ->
            userTableRows[tableName] = row
        }

        AccessIndex.configure(jet.version, configuration)
        isOpen = true
    }

    override suspend fun create(fileExtensions: FileExtensions) {
        accessFile = AccessFile(this, filePath, DatabaseFile.Mode.ReadWrite)
    }

    override suspend fun close() {
        accessFile.close()
        accessSystemCatalog.close()
        for ((_, db) in linkedDatabases) {
            db.close()
        }
        linkedDatabases.clear()
        userTableRows.clear()
        isOpen = false
    }

    override suspend fun linkedDatabase(name: String): Database {
        return AccessDatabase(
            File(File(filePath).path, name).fullPath,
            configuration,
            mode
        )
    }

    override fun tableNameList(): List<String> {
        return userTableRows.keys.toList()
    }

    override suspend fun table(tableName: String): Table {
        val tableRow = userTableRows[tableName]
            ?: throw IllegalArgumentException("No such table $tableName")
        if (tableRow.isLinkedDb) {
            val db = linkedDatabases[tableRow.linkedDb] ?: linkedDatabase(tableRow.linkedDb)
            db.open()
            linkedDatabases[tableRow.linkedDb] = db as AccessDatabase
            return db.table(tableRow.foreignName)
        }
        accessFile.parseTablePage(tableName, tableRow.id)
        return userCatalog[tableName]
            ?: throw IllegalStateException("Table name $tableName not parsed")
    }

    internal fun tableDefinition(table: Table) {
        if (table.systemCatalog)
            systemCatalog.tables[table.name] = table
        else
            userCatalog[table.name] = table
    }

    fun setUp(dbPage: DatabasePageStructure) {
        jet = dbPage.jet
        version = dbPage.version
        defaultCodePage = dbPage.defaultCodePage
        accessFile.jet = dbPage.jet
    }

    companion object {
        const val systemCatalogName = "MSysObjects"
        const val systemTablePrefix = "MSys"
    }
}
