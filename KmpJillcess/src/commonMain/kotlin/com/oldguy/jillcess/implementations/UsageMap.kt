package com.oldguy.jillcess.implementations

import com.oldguy.common.io.ByteBuffer
import com.oldguy.common.io.UByteBuffer

/**
 * Manages the underlying structs that represent the physical structure of Usage maps.
 * There can either be an InlineUsageMap, or an Indirect usage map that points at from 1 to
 * 16 ReferencedPages that hold Usage maps.
 */
class UsageMap : Iterable<Int> {
    lateinit var content: UsageMapContent

    suspend fun parseReferences(
        map: IndirectUsageMap,
        content: UsageMapContent,
        accessFile: AccessFile
    ) {
        this.content = content
        for (i in map.referencePages.indices) {
            val pageNumber = map.referencePages[i]
            val referencedPageMap = ReferencedUsageMap(i, accessFile.jet.pageVersion, this.content)
            val page = accessFile.readSidePage(pageNumber)
            referencedPageMap.parse(page)
        }
    }

    override fun iterator(): Iterator<Int> {
        return UsageMapIterator(this)
    }
}

class UsageMapIterator(private val usageMap: UsageMap, startPage: Int = 0) : Iterator<Int> {
    private var nextPage = startPage

    override fun hasNext(): Boolean {
        return when {
            nextPage < 0 -> false
            nextPage >= usageMap.content.map.numberOfBits -> false
            else -> usageMap.content.map.nextSetBit(nextPage) >= 0
        }
    }

    override fun next(): Int {
        val page = usageMap.content.map.nextSetBit(nextPage)
        nextPage = page + 1
        return page
    }
}

/**
 * Data page 1 is special case and contains the global usage map. It is a data page with one or more records.
 * Jackcess used first record as the global UsageMap, 2nd record is also Inline, but is standard
 * table usagemap length of 69. Dunno what it is for yet
 */
class GlobalUsageMap(jet: Jet) : AccessPage<DataPageStructure>(PageType.DataPage, jet) {
    private val globalMap: UsageMap = UsageMap()

    override suspend fun parsePage(page: UByteBuffer, pageNumber: Int): DataPageStructure {
        if (pageNumber != 1)
            throw IllegalArgumentException("Global usage map is page 1, not $pageNumber")
        val dataPage = DataPageStructure(1, jet.pageSize).parse(page)
        val record = dataPage.recordOffsets[0]
        page.position = record.position.toInt()
        when (UsageMapType.fromByte(page.get(record.position.toInt()))) {
            UsageMapType.Inline -> globalMap.content =
                InlineUsageMap(record.length, isVersion4).parse(page)
            else -> {
                val map = IndirectUsageMap(record.length, jet.pageVersion)
                globalMap.content = map.parse(page)
            }
        }
        return dataPage
    }
}
