package com.oldguy.jillcess.implementations

import com.oldguy.common.io.TextFile
import com.oldguy.jillcess.Column
import com.oldguy.jillcess.Index
import com.oldguy.jillcess.configuration.Configuration
import kotlin.experimental.inv
import kotlin.experimental.or

class AccessIndexColumn(
    name: String,
    index: Int,
    val ascending: Boolean
) : Column(name, index)

class AccessIndex(private val accessTable: AccessTable, indexStruct: AllIndexStructure) :
    Index(accessTable) {

    var rowCount: Int = 0
    private var firstPage: Int = 0
    private val indexColumns = emptyMap<String, AccessIndexColumn>().toMutableMap()

    val idNumber = indexStruct.indexNumber
    val unique = indexStruct.realIndex?.unique ?: false
    val ignoreNulls = indexStruct.realIndex?.ignoreNulls ?: false
    val required = indexStruct.realIndex?.required ?: false
    val isPrimaryKey = indexStruct.indexType == IndexType.PrimaryKey
    val isNormal = indexStruct.indexType == IndexType.Normal
    val isLogical = indexStruct.indexType == IndexType.Logical

    init {
        name = indexStruct.indexName
        indexStruct.realIndex?.let { realIndexStructure ->
            rowCount = realIndexStructure.indexRowCount
            firstPage = realIndexStructure.firstIndexPage
            realIndexStructure.columnFlags.forEach {
                val col = accessTable.findColumnById(it.first.toShort())
                add(col)
                indexColumns[col.name] = AccessIndexColumn(col.name, col.index, it.second)
            }
        }
    }

    fun column(name: String): AccessColumn {
        return (columns[name] as AccessColumn?)
            ?: throw IllegalArgumentException("No such column in index: $name")
    }

    /**
     * Retrieve all records from this index, in index order. Invokes the designated function for
     * each entry found. Note that the lambda must return true to continue, or false to stop the
     * retrieve.
     */
    suspend fun retrieve(row: (AccessRow) -> Boolean) {
        search(firstPage, row)
    }

    /**
     * Standard recursive left-wise index tree traversal.  There is a special child leaf node that is
     * special cased at the end of the traversal.
     */
    private suspend fun search(pageNumber: Int, row: (AccessRow) -> Boolean) {
        val indexPage = IndexNodePage(accessTable.isVersion4)
            .parse(accessTable.accessFile.readPage(pageNumber))
        var rc = true
        // System.out.println("Page $pageNumber, leaf ${indexPage.isLeaf}, entries ${indexPage.records.size}")
        for (entry in indexPage.records) {
            if (!indexPage.isLeaf) {
                search(entry.childPage, row)
            } else {
                accessTable.retrieveRow(entry.recordPointer) {
                    rc = row(it)
                }
            }
            if (!rc) return
        }
        if (!indexPage.isLeaf && indexPage.tailLeafPage > 0) {
            search(indexPage.tailLeafPage, row)
        }
    }

    /**
     * Search through the index starting at the root page, looking for the leaf page(s) that hold
     * actual entries matching this keyValues entry. For each match, invoke the lambda, in the order
     * found in the index
     */
    suspend fun retrieve(keyValues: AccessRow, row: (AccessRow) -> Unit) {
        val keyEntry = indexEncode(keyValues)
        search(keyEntry, firstPage, row)
    }

    private suspend fun search(
        keyEntry: IndexEntry,
        pageNumber: Int,
        row: (AccessRow) -> Unit
    ) {
        val indexPage = IndexNodePage(accessTable.isVersion4)
            .parse(accessTable.accessFile.readPage(pageNumber))
        for (entry in indexPage.records) {
            val rc = entry.compareTo(keyEntry)
            if (rc < 0) continue
            if (rc == 0) {
                accessTable.retrieveRow(entry.recordPointer, row)
                return
            }
        }
        if (indexPage.isLeaf)
            error("Leaf page $pageNumber did not contain entry")
    }

    /**
     * Use this to construct a Row containing a RowValue for each index column.  These RowValues can
     * then be set with search/key values and used with the various flavors of retrieve methods.
     */
    fun emptyRow(): AccessRow {
        val row = AccessRow()
        for (col in indexColumns) {
            val accessColumn = accessTable.findColumnByName(col.key)
            row.add(
                accessColumn.emptyRowValue(
                    accessTable.accessFile.endian,
                    accessTable.accessFile.jet.charset,
                    accessTable.accessFile.jet,
                    col.value.ascending
                )
            )
        }
        return row
    }

    private fun indexEncode(keyValues: AccessRow): IndexEntry {
        val entry = IndexEntry(UByteArray(0), PageType.LeafIndex)
        var bytes = ByteArray(0)
        val entryBytes = mutableListOf<Byte>()

        for (rv in keyValues) {
            entryBytes.add(rv.flagByte())
            if (!rv.isNull) {
                when (rv.type) {
                    ValueType.BooleanType,
                    ValueType.ByteType,
                    ValueType.ShortType,
                    ValueType.IntegerType,
                    ValueType.Currency,
                    ValueType.FloatType,
                    ValueType.DoubleType,
                    ValueType.DateTimeType,
                    ValueType.GUID,
                    ValueType.FixedPoint,
                    ValueType.LongType,
                    ValueType.SmallBinary ->
                        bytes = rv.indexEncode()
                    ValueType.SmallText,
                    ValueType.Memo ->
                        bytes = indexEncodeText(rv.value as String, rv.ascending)
                    ValueType.OLE,
                    ValueType.Unknown13,
                    ValueType.Unknown17,
                    ValueType.Complex -> error("Index column type ${rv.type} unsupported")
                }
            }
            entryBytes.addAll(bytes.toList())
        }
        entry.fieldContent = entryBytes.toByteArray().toUByteArray()
        return entry
    }

    private fun indexEncodeText(value: String, isAscending: Boolean): ByteArray {
        val maxTextIndexLength = 255
        val endText = 0x01.toByte()
        val internationalExtraPlaceholder = 0x02.toByte()
        val crazyCodesUnprintSuffix = 0xFF.toByte()
        val endExtraText = 0x00.toByte()

        var str = value
        if (value.length > maxTextIndexLength)
            str = value.substring(maxTextIndexLength)

        var charOffset = 0
        val encoded = mutableListOf<Byte>()
        val extraCodes = mutableListOf<Byte>()
        val unprintableCodes = mutableListOf<Byte>()
        val crazyCodes = mutableListOf<Byte>()
        var stats = ExtraCodesStats()
        for (element in str) {
            val charHandler = indexCodes.getCharHandler(element)
            val curCharOffset = charOffset
            var bytes = charHandler.inlineBytes
            if (bytes.isNotEmpty()) {
                encoded.addAll(bytes.toList())
                charOffset++
            }
            if (charHandler.type == EncodingType.SIMPLE) continue

            bytes = charHandler.extraBytes
            val extraCodeModifier = charHandler.extraByteModifier
            if (bytes.isNotEmpty() || extraCodeModifier.toInt() != 0) {
                // keep track of the extra codes for later
                stats = writeExtraCodes(
                    curCharOffset,
                    bytes,
                    extraCodeModifier,
                    extraCodes
                )
            }

            bytes = charHandler.unprintableBytes
            if (bytes.isNotEmpty()) {
                writeUnprintableCodes(
                    curCharOffset, bytes, unprintableCodes,
                    extraCodes, stats
                )
            }

            val crazyFlag = charHandler.crazyFlag.toByte()
            if (crazyFlag != 0.toByte()) {
                crazyCodes.add(crazyFlag)
            }
        }
        encoded.add(endText)

        val hasExtraCodes = trimExtraCodes(
            extraCodes, 0.toByte(), internationalExtraPlaceholder
        )
        if (hasExtraCodes || unprintableCodes.isNotEmpty() || crazyCodes.isNotEmpty()) {
            // we write all the international extra bytes first
            if (hasExtraCodes) {
                encoded.addAll(extraCodes.toList())
            }

            if (crazyCodes.isNotEmpty() || unprintableCodes.isNotEmpty()) {
                // write 2 more end flags
                encoded.add(endText)
                encoded.add(endText)

                // next come the crazy flags
                if (crazyCodes.isNotEmpty()) {
                    writeCrazyCodes(crazyCodes, encoded)

                    // if we are writing unprintable codes after this, tack on another
                    // code
                    if (unprintableCodes.isNotEmpty()) {
                        encoded.add(crazyCodesUnprintSuffix)
                    }
                }

                // then we write all the unprintable extra bytes
                if (unprintableCodes.isNotEmpty()) {

                    // write another end flag
                    encoded.add(endText)
                    encoded.addAll(unprintableCodes)
                }
            }
        }

        // handle descending order by inverting the bytes
        if (!isAscending) {
            // we actually write the end byte before flipping the bytes, and write
            // another one after flipping
            encoded.add(endExtraText)

            // flip the bytes that we have written thus far for this text value
            for (i in 0 until encoded.size) {
                encoded[i] = encoded[i].inv()
            }
        }

        // write end extra text
        encoded.add(endExtraText)
        return encoded.toByteArray()
    }

    private data class ExtraCodesStats(
        var unprintablePrefixLength: Int = 0,
        var characterCount: Int = 0
    )

    private fun writeExtraCodes(
        charOffset: Int,
        bytes: ByteArray,
        extraCodeModifier: Byte,
        extraCodes: MutableList<Byte>
    ): ExtraCodesStats {
        val internationalExtraPlaceholder = 0x02.toByte()

        // we fill in a placeholder value for any chars w/out extra codes
        val numChars: Int = extraCodes.size
        val stats = ExtraCodesStats()
        if (numChars < charOffset) {
            for (i in 0 until charOffset - numChars) {
                extraCodes.add(internationalExtraPlaceholder)
                stats.characterCount++
            }
        }
        if (bytes.isNotEmpty()) {
            extraCodes.addAll(bytes.toList())
            stats.characterCount += bytes.size
        } else {

            // extra code modifiers modify the existing extra code bytes and do not
            // count as additional extra code chars
            val lastIdx: Int = extraCodes.size - 1
            if (lastIdx >= 0) {
                // the extra code modifier is added to the last extra code written
                var lastByte = extraCodes[lastIdx]
                lastByte = (lastByte + extraCodeModifier).toByte()
                extraCodes[lastIdx] = lastByte
            } else {
                // there is no previous extra code, add a new code (but keep track of
                // this "unprintable code" prefix)
                extraCodes.add(extraCodeModifier)
                stats.unprintablePrefixLength = 1
            }
        }
        return stats
    }

    private fun writeUnprintableCodes(
        charOffset: Int,
        bytes: ByteArray,
        unprintableCodes: MutableList<Byte>,
        extraCodes: List<Byte>,
        stats: ExtraCodesStats
    ) {
        val unprintableCountStart = 7
        val unprintableCountMultiplier = 4
        val unprintableOffsetFlags = 0x8000
        val unprintableMidfix = 0x06.toByte()

        // the offset seems to be calculated based on the number of bytes in the
        // "extra codes" part of the entry (even if there are no extra codes bytes
        // actually written in the final entry).
        var unprintCharOffset = charOffset
        if (extraCodes.isNotEmpty()) {
            // we need to account for some extra codes which have not been written
            // yet.  additionally, any unprintable bytes added to the beginning of
            // the extra codes are ignored.
            unprintCharOffset = extraCodes.size +
                    (charOffset - stats.characterCount) -
                    stats.unprintablePrefixLength
        }

        // we write a whacky combo of bytes for each unprintable char which
        // includes a funky offset and extra char itself
        val offset = (unprintableCountStart +
                unprintableCountMultiplier * unprintCharOffset
                or unprintableOffsetFlags)

        // write offset as big-endian short
        unprintableCodes.add((offset shr 8 and 0xFF).toByte())
        unprintableCodes.add((offset and 0xFF).toByte())
        unprintableCodes.add(unprintableMidfix)
        unprintableCodes.addAll(bytes.toList())
    }

    private fun writeCrazyCodes(crazyCodes: MutableList<Byte>, encoded: MutableList<Byte>) {
        val crazyCode2 = 0x03.toByte()
        val crazyCodeStart = 0x80.toByte()
        val crazyCodesSuffix = byteArrayOf(
            0xFF.toByte(),
            0x02.toByte(),
            0x80.toByte(),
            0xFF.toByte(),
            0x80.toByte()
        )

        // CRAZY_CODE_2 flags at the end are ignored, so ditch them
        trimExtraCodes(crazyCodes, crazyCode2, crazyCode2)
        if (crazyCodes.isNotEmpty()) {

            // the crazy codes get encoded into 6 bit sequences where each code is 2
            // bits (where the first 2 bits in the byte are a common prefix).
            var curByte = crazyCodeStart
            var idx = 0
            for (i in 0 until crazyCodes.size) {
                var nextByte = crazyCodes[i]
                nextByte = (nextByte.toInt() shl (2 - idx) * 2).toByte()
                curByte = curByte or nextByte
                ++idx
                if (idx == 3) {
                    // write current byte and reset
                    encoded.add(curByte)
                    curByte = crazyCodeStart
                    idx = 0
                }
            }

            // write last byte
            if (idx > 0) {
                encoded.add(curByte)
            }
        }

        // write crazy code suffix (note, we write this even if all the codes are
        // trimmed
        encoded.addAll(crazyCodesSuffix.toList())
    }

    private fun trimExtraCodes(
        extraCodes: MutableList<Byte>,
        minTrimCode: Byte,
        maxTrimCode: Byte
    ): Boolean {
        if (extraCodes.isEmpty()) {
            return false
        }
        var idx = extraCodes.size - 1
        while (idx >= 0) {
            if (extraCodes[idx] in minTrimCode..maxTrimCode) {
                extraCodes.removeAt(idx)
                --idx
            } else {
                break
            }
        }
        // anything left?
        return extraCodes.isNotEmpty()
    }

    companion object {
        private lateinit var indexCodes: IndexCodes
        private val firstChar = 0u
        private val lastChar = 255u
        private val firstExtChar = 256u
        private val lastExtChar = 0xFFFFu

        suspend fun configure(
            version: Jet.Version,
            configuration: Configuration
        ) {
            indexCodes = IndexCodes(
                when (version) {
                    Jet.Version.Access97, Jet.Version.Access2000_2002_2003, Jet.Version.Access2007 ->
                        loadCodes(configuration.indexCodeGeneralLegacy(), firstChar, lastChar)
                    Jet.Version.Access2010, Jet.Version.Access2016 ->
                        loadCodes(configuration.indexCodeGeneral(), firstChar, lastChar)
                },
                when (version) {
                    Jet.Version.Access97, Jet.Version.Access2000_2002_2003, Jet.Version.Access2007 ->
                        loadCodes(
                            configuration.indexCodeGeneralLegacyExtra(),
                            firstExtChar,
                            lastExtChar
                        )
                    Jet.Version.Access2010, Jet.Version.Access2016 ->
                        loadCodes(configuration.indexCodeGeneralExtra(), firstExtChar, lastExtChar)
                }
            )
        }

        private suspend fun loadCodes(
            codesFile: TextFile,
            firstChar: UInt,
            lastChar: UInt
        ): Array<CharHandler> {
            val values =
                Array<CharHandler>((lastChar - firstChar).toInt() + 1) { IgnoredCharHandler() }
            val prefixMap: MutableMap<String, EncodingType> = mutableMapOf()
            for (type in EncodingType.values()) {
                prefixMap[type.prefixCode] = type
            }
            for (i in firstChar..lastChar) {
                val c = i.toInt().toChar()
                val ch = if (c.isHighSurrogate() || c.isLowSurrogate()) {
                    // surrogate chars are not included in the codes files
                    SurrogateCharHandler()
                } else {
                    val codeLine = codesFile.readLine()
                    parseCodes(prefixMap, codeLine)
                }
                values[(i - firstChar).toInt()] = ch
            }
            codesFile.close()
            return values
        }

        private fun parseCodes(
            prefixMap: Map<String, EncodingType>,
            codeLine: String
        ): CharHandler {
            val prefix = codeLine.substring(0, 1)
            val suffix = if (codeLine.length > 1) codeLine.substring(1) else ""
            val type: EncodingType = prefixMap[prefix] ?: error("Unsupported encoding type")
            return type.parseCodes(suffix.split(",").toTypedArray())
        }
    }
}
