package com.oldguy.jillcess.implementations

import com.oldguy.common.io.BitSet
import com.oldguy.common.io.Buffer
import com.oldguy.common.io.UByteBuffer
import com.oldguy.jillcess.compareTo
import com.oldguy.jillcess.get3ByteInt

/**
 * An Index record contains a flag Access uses for indicating null and forcing sorting to work in both
 * Ascending and Descending indexes. Next is the field content, which can have a simple prefix
 * elimination compression scheme.
 * Next is a RecordPointer, either to another index entry, or if this is a Leaf node, a DataRecord on a DataPage
 *
 */
class IndexEntry(
    private val entryPrefix: UByteArray,
    private val pageType: PageType
) : PagePortion<IndexEntry>(),
    Comparable<IndexEntry> {

    var fieldContent = UByteArray(0)

    var childPage = -1
        private set
    var recordPointer = RecordPointer()
        private set

    private val entryTrailerLength = when (pageType) {
        PageType.IntermediateIndex -> 8
        PageType.LeafIndex -> 4
        else -> throw IllegalArgumentException("PageType $pageType is illegal for IndexEntry")
    }

    /**
     * ByteBuffer MUST be positioned at the start of an entry, with the limit set to the entry length, or results will
     * wrong.
     */
    override fun parse(bytes: UByteBuffer): IndexEntry {
        val contentLength = bytes.remaining - entryTrailerLength
        val temp = bytes.getBytes(contentLength)
        if (entryPrefix.isNotEmpty()) {
            fieldContent = UByteArray(contentLength + entryPrefix.size)
            entryPrefix.copyInto(fieldContent)
            temp.copyInto(fieldContent, entryPrefix.size)
        } else
            fieldContent = temp

        when (pageType) {
            PageType.IntermediateIndex -> {
                val pageNumber = bytes.get3ByteInt()
                recordPointer = RecordPointer(bytes.byte.toShort(), pageNumber)
                childPage = bytes.int
            }
            PageType.LeafIndex -> {
                val pageNumber = bytes.get3ByteInt()
                recordPointer = RecordPointer(bytes.byte.toShort(), pageNumber)
            }
            else -> error("IndexEntry parse called on non-index page type of $pageType")
        }

        return this
    }

    override fun setBytes(bytes: UByteBuffer) {
    }

    override fun compareTo(other: IndexEntry): Int {
        if (this == other) return 0
        return fieldContent.toByteArray().compareTo(other.fieldContent.toByteArray())
    }

    /**
     * Each column value in an IndexEntry starts with a one byte flatg that can have one of four
     * values indicating sort order and isNull.
     */
    enum class EntryValueFlag(val byteValue: Byte) {
        Ascending(0x7F.toByte()),
        AscendingNull(0x00.toByte()),
        Descending(0x80.toByte()),
        DescendingNull(0xFF.toByte());

        companion object {

            fun getEntryValueFlag(isNull: Boolean, ascending: Boolean): EntryValueFlag {
                return if (ascending)
                    if (isNull)
                        AscendingNull
                    else
                        Ascending
                else if (isNull)
                    DescendingNull
                else
                    Descending
            }
        }
    }
}

/*
    Evaluate the bitmask to locate entries. Note that entries are Big Endian. Parse
    each entry and collect in a list. Entries are comparable and should be in order.
    All entries on a page can share the same 'prefix' data as a simple compression scheme.
    If one is present (entry prefix length not zero) it is the front of the first entry
    parsed
 */
abstract class IndexPage<T>(
    val isVersion4: Boolean,
    pageType: PageType
) : Page<T>(pageType) {
    private val bitmaskStart: Short = if (isVersion4) 27 else 22
    private val bitmaskLength: Short = if (isVersion4) 453 else 226
    private val firstEntryOffset: Int = if (isVersion4) 0x1e0 else 0xf8
    val records = emptyList<IndexEntry>().toMutableList()
    var entryPrefixLength: Short = 0
    private var entryPrefix = UByteArray(0)

    override fun parse(bytes: UByteBuffer): T {
        bytes.positionLimit(bitmaskStart, bitmaskLength)
        val bitSet = BitSet(bytes.toByteBuffer())
        var pos = firstEntryOffset
        var index = 0
        val entriesBytes = UByteBuffer(bytes.contentBytes, Buffer.ByteOrder.BigEndian)
        entriesBytes.position = pos
        do {
            index = bitSet.nextSetBit(index + 1)
            if (index < 0) break
            val length = firstEntryOffset + index - pos
            entriesBytes.positionLimit(pos, length)
            records.add(IndexEntry(entryPrefix, pageType).parse(entriesBytes))
            if (records.size == 1 && entryPrefixLength > 0) {
                entryPrefix = records[0].fieldContent.copyOfRange(0, entryPrefixLength.toInt())
            }
            pos += length
        } while (true)

        @Suppress("UNCHECKED_CAST")
        return this as T
    }
}

/**
 * Node level data page for Indexes.  Indexes for tables that have one data page do not use this.  Indexes for
 * tables where all index data can fit on one page also do not use this. Node pages are a doubly linked list of other
 * node pages on the same level. Entries on these pages point to a lower level intermediate node, or a leaf node
 * containing the value being looked up
 *
 * Note that while index data page headers are in Little Endian order in the ByteBuffer (like all other pages), for some
 * reason index entries are in Big Endian order
 */
class IndexNodePage(isVersion4: Boolean) :
    IndexPage<IndexNodePage>(isVersion4, PageType.IntermediateIndex) {
    private var freeSpace: Short = 0
    private var tableDefPage = -1
    private var previousPage: Int = -1
    private var nextPage: Int = -1
    var tailLeafPage = 0
    var isLeaf = false

    override fun parse(bytes: UByteBuffer): IndexNodePage {
        bytes.rewind()
        when (val signature = bytes.short) {
            0x0103.toShort() -> {
                isLeaf = false
                pageType = PageType.IntermediateIndex
            }
            0x0104.toShort() -> {
                isLeaf = true
                pageType = PageType.LeafIndex
            }
            else -> throw FileStructureException("IndexNode signature invalid: $signature")
        }
        freeSpace = bytes.short
        tableDefPage = bytes.int
        bytes.int // Unknown field, not used
        previousPage = bytes.int
        nextPage = bytes.int
        tailLeafPage = bytes.int
        entryPrefixLength = bytes.short
        super.parse(bytes)

        return this
    }

    override fun setBytes(bytes: UByteBuffer) {
    }
}
