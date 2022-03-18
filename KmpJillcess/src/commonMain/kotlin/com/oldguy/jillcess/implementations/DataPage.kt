package com.oldguy.jillcess.implementations

import com.oldguy.common.io.UByteBuffer

class DataPage(jet: Jet) : AccessPage<DataPageStructure>(PageType.DataPage, jet) {
    private val pageSize = jet.pageSize
    lateinit var dataPageStructure: DataPageStructure

    override suspend fun parsePage(page: UByteBuffer, pageNumber: Int): DataPageStructure {
        dataPageStructure = DataPageStructure(pageNumber, pageSize).parse(page)

        return dataPageStructure
    }
}
