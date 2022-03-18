package com.oldguy.jillcess.implementations

import com.oldguy.common.io.UByteBuffer
import com.oldguy.jillcess.Table

/**
 * Since Table metadata can be large for a complex table, it can span one or more linked-list
 * TableDefinition pages. This class traverses the linked list of table definition pages, and
 * concatenates all the parsable data into one contiguous ByteBuffer
 */
class TableDefinitionPages(
    private val accessFile: AccessFile,
    private val tableObserver: (table: Table) -> Unit
) : AccessPage<TableStructure>(PageType.TableDefinition, accessFile.jet) {
    private val tablePageConfig = TableConfiguration(jet)
    private val pageNumbers = emptyList<Int>().toMutableList()
    private var tableName = AccessDatabase.systemCatalogName

    constructor(
        name: String,
        accessFile: AccessFile,
        tableObserver: (table: Table) -> Unit
    ) :
            this(accessFile, tableObserver) {
        tableName = name
    }

    /**
     * Table definitions can span pages, this function traverses the linked "list"
     * and produces a concatenated buffer of all the definition page data. That buffer is then
     * parsed into a TableStructure. Once all that is parsed, an AccessTable
     * is produced and the tableObeserver is notified
     * @param page contains an already read page of table definition type
     * @param pageNumber is the page Number of the root defintion page for this table
     */
    override suspend fun parsePage(page: UByteBuffer, pageNumber: Int): TableStructure {
        pageNumbers.clear()
        pageNumbers.add(pageNumber)
        val signature = page.short
        if (signature != 0x0102.toShort())
            throw FileStructureException("Unexpected table page signature: $signature")
        page.short
        page.position = tablePageConfig.offsetNextTableDef
        var nextPage = page.int
        page.rewind()
        val filledBuffer = UByteBuffer(page.contentBytes, page.order)
        while (nextPage > 0) {
            val onePage = accessFile.readSidePage(nextPage)
            if (PageType.fromByte(onePage.get(0)) != PageType.TableDefinition)
                throw FileStructureException("Table defintion extra page number $nextPage, linked to from $pageNumber")
            onePage.position = 8
            filledBuffer.position = filledBuffer.limit
            filledBuffer.expand(onePage)
            pageNumbers.add(nextPage)
            onePage.position = tablePageConfig.offsetNextTableDef
            nextPage = onePage.int
        }
        filledBuffer.rewind()
        val tableStructure = TableStructure(tablePageConfig).parse(filledBuffer)

        val table = AccessTable(
            tableName,
            accessFile, tableStructure, pageNumbers
        ).apply { initialize() }
        tableObserver.invoke(table)
        return tableStructure
    }
}
