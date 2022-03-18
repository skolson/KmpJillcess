package com.oldguy.jillcess.implementations

import com.oldguy.common.io.BitSet
import com.oldguy.common.io.UByteBuffer

/**
 * Usage map whose map is written inline in the same page.  For Jet4, this
 * type of map can usually contains a maximum of 512 pages (64 byte length).
 * Free space maps are always inline/Basic, used space maps may be inline/Basic
 * or reference/Indirection to a Referenced map.
 * Structure seems to always start with an Inline, which may be followed by an Indirect based on the inline, and the
 * Indirect map points to one or more Referenced maps
 * Referenced page type code is same as PageType.UsageMap
 *
 */

internal const val pagesPerByte = 8

enum class UsageMapType(val typeCode: Byte) {
    Inline(0.toByte()), Indirect(1.toByte()), Referenced(5.toByte());

    companion object {
        private const val mapLengthVersion4 = 64.toShort()
        private const val mapLengthVersion3 = 128.toShort()
        internal fun mapLength(isVersion: Boolean): Short {
            return if (isVersion) mapLengthVersion4 else mapLengthVersion3
        }

        fun fromByte(typeCode: UByte): UsageMapType {
            return when (typeCode) {
                0x00.toUByte() -> Inline
                0x01.toUByte() -> Indirect
                0x05.toUByte() -> Referenced
                else -> throw FileStructureException("Invalid usage map type $typeCode")
            }
        }
    }
}

class UsageMapContent(val basePageNumber: Int, pagesCount: Int) {
    val map = BitSet(pagesCount)
    private val endPage = basePageNumber + map.numberOfBits

    fun processMap(buffer: UByteBuffer, startingPageIndex: Int = 0) {
        var byteCount = 0
        while (buffer.hasRemaining) {
            val b = buffer.byte
            if (b != 0.toUByte()) {
                for (i in 0..7) {
                    if (b.toInt() and (1 shl i) != 0) {
                        val pageNumberOffset = byteCount * pagesPerByte + i
                        val pageNumber = pageNumberOffset + basePageNumber
                        if (pageNumber < basePageNumber || pageNumber >= endPage) {
                            throw IllegalStateException(
                                "found page number $pageNumber in usage map outside of expected range: $basePageNumber to $endPage"
                            )
                        }
                        map.set(startingPageIndex + pageNumberOffset, true)
                    }
                }
            }
            byteCount++
        }
    }
}

class InlineUsageMap(
    private val recordLength: Short,
    val isVersion4: Boolean
) : PagePortion<UsageMapContent>() {
    private val headerLength = 5
    lateinit var usageMapContent: UsageMapContent
        private set

    /**
     * ByteBuffer MUST be positioned at beginning of the UsageMap record so that first bye is UsageMapType,
     * and limit() must be at least position()+recordLength or greater (up to page size)
     */
    override fun parse(bytes: UByteBuffer): UsageMapContent {
        bytes.limit = bytes.position + recordLength
        val mapType = UsageMapType.fromByte(bytes.byte)
        if (mapType != UsageMapType.Inline)
            throw FileStructureException("Inline usage map expected map type of ${UsageMapType.Inline.typeCode}, got $mapType")
        val minLength = headerLength + UsageMapType.mapLength(isVersion4)
        if (recordLength < minLength)
            throw FileStructureException("Inline usage map minimum length is $minLength, request is $recordLength")
        usageMapContent = UsageMapContent(bytes.int, (recordLength - headerLength) * pagesPerByte)
        usageMapContent.processMap(bytes)
        return usageMapContent
    }

    override fun setBytes(bytes: UByteBuffer) {
        bytes.byte = UsageMapType.Inline.typeCode.toUByte()
        bytes.int = usageMapContent.basePageNumber
        bytes.put(usageMapContent.map.toByteArray().toUByteArray())
    }
}

class IndirectUsageMap(private val recordLength: Short, private val pageSize: PageSize) :
    PagePortion<UsageMapContent>() {
    val referencePages = emptyList<Int>().toMutableList()

    /**
     * ByteBuffer must be positioned at the byte containing the map type.
     */
    override fun parse(bytes: UByteBuffer): UsageMapContent {
        bytes.limit = bytes.position + recordLength - 1
        val mapType = UsageMapType.fromByte(bytes.byte)
        if (mapType != UsageMapType.Indirect)
            throw FileStructureException("Indirect usage map expected map type of ${UsageMapType.Indirect.typeCode}, got $mapType")
        val refPageCount = (recordLength - 1) / 4
        if (refPageCount < 0 || refPageCount > 17)
            throw FileStructureException("Indirect usage map expected 1 to 17 references pages, recordLength:$recordLength indicates $refPageCount")

        for (i in 1..refPageCount) {
            val refPageNumber = bytes.int
            if (refPageNumber > 0)
                referencePages.add(refPageNumber)
        }
        val pagesPerMapPage = (pageSize.size - ReferencedUsageMap.pageHeaderLength) * pagesPerByte
        return UsageMapContent(0, pagesPerMapPage * referencePages.size)
    }

    override fun setBytes(bytes: UByteBuffer) {
        bytes.byte = UsageMapType.Indirect.typeCode.toUByte()
        for (i in referencePages)
            bytes.int = i
    }
}

/**
 * Usage map whose map is written across one or more entire separate pages
 * of page type USAGE_MAP.  For Jet4, this type of map can contain 32736
 * pages per reference page, and a maximum of 17 reference map pages for a
 * total maximum of 556512 pages (2 GB).
 *
 * Constructor requires the index (between 0 and maxForPageSize) of the page being parsed,
 * the current pageSize, and the UsageMapContent built from parsing the Indirect usage map that created the
 * UsageMapContent in use
 */
class ReferencedUsageMap(
    private val refPageIndex: Int,
    private val pageSize: PageSize,
    private val usageMapContent: UsageMapContent
) : Page<UsageMapContent>(PageType.UsageMap) {
    private val pagesPerMapPage = (pageSize.size - pageHeaderLength) * pagesPerByte

    /**
     * This setMap ONLY parses the page header and leaves the ByteBuffer positioned at the start of the map
     *
     */
    override fun parse(bytes: UByteBuffer): UsageMapContent {
        bytes.rewind()
        val mapType = UsageMapType.fromByte(bytes.byte)
        if (mapType != UsageMapType.Referenced)
            throw FileStructureException("Referenced usage map expected page type of ${PageType.UsageMap}, got $mapType")
        bytes.byte
        usageMapContent.processMap(bytes, refPageIndex * pagesPerMapPage)
        return usageMapContent
    }

    /**
     * ByteBuffer must be positioned at the start of the full map including the byte containing the map type
     */
    override fun setBytes(bytes: UByteBuffer) {
        bytes.byte = 0x05u
        bytes.byte = 0x01u
        bytes.short = 0
        // this part needs debug
        val workMap = usageMapContent.map
        val startIndex = (refPageIndex * pagesPerMapPage) / pagesPerByte
        val endIndex = startIndex + (pagesPerMapPage / pagesPerByte)
        val slice = workMap.toByteArray().sliceArray(startIndex..endIndex)
        if (slice.size != (pageSize.size - pageHeaderLength))
            error("UsageMap Slice size ${slice.size} not equal pageSize ${pageSize.size} - header length $pageHeaderLength")
        bytes.put(slice.toUByteArray())
    }

    companion object {
        internal const val pageHeaderLength = 4
    }
}
