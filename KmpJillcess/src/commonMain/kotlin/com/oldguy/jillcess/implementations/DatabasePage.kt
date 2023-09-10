package com.oldguy.jillcess.implementations

import com.oldguy.common.io.ByteBuffer
import com.oldguy.common.io.UByteBuffer
import korlibs.time.DateTime
import kotlin.experimental.xor

/**
 * Every database has a Page 0 (first physical 2K (Jet3) or 4K (Jet4) bytes) that defines the database type/version and other
 * file/level metadata. There is a simplistic RC4 obfuscation in use for a portion of this page, which this class removes
 * before parsing the page content into properties used by "implementations" package classes.
 *
 * For all Access Database versions:
 * Page 0 is this page
 * Page 1 is a Global UsageMap
 * Page 2 is the first TableDefinition page of the MSysObjects system catalog table.  MSysObjects must be parsed to determine
 * all other metadata in this database describing its content
 *
 * Notes for encryption content in this page
 * 1) The creation date for the database is a double holding an encoded Date using microsoft's scheme
 * It is also used as a password salt. for some reason the int portion of the double is also used as
 * a salt in some formats
 * 2) old MSISAM databases not using newer encryption, require some extra password processing
 * 3) later versions have metadata about the encoding provider. Since later versions do various
 * changes and enhancements over time, security-related fields parsing is
 * handled by a version-specific flavor of DatabaseExncryptionStructure.  The Access version is used to
 * determine which flavor to build
 */
class DatabasePageStructure(val pageNumber: Int = 0, val password: String = "") :
    Page<DatabasePageStructure>(PageType.Database) {
    var version: Int = 0
        private set
    private var fileFormatId: String = ""
    var jet: Jet = Jet.Jet4()
        private set
    private var codecKey: Int = 0
    val isEncoded get() = codecKey != 0

    private var defaultSortOrder: SortOrder = SortOrder.GeneralSortOrder
    var defaultCodePage: Short = 0
        private set
    private var creationDate = DateTime.nowLocal().local
    val jetHeader = UByteArray(15)

    lateinit var encryption: DatabaseEncryptionStructure

    /**
     * Parse enough of page 0 to determine Jet version page size.  If required page size is bigger,
     * make a new ByteBuffer of the correct size
     * @param bytes with at least the Jet3
     * @return properly sized ByteBuffer for current Jet version
     */
    fun determineJet(bytes: UByteBuffer): UByteBuffer {
        bytes.position = 4
        bytes.get(jetHeader)
        bytes.position = 4
        fileFormatId = nullTerminatedString(bytes, 16)
        bytes.position = 20
        version = bytes.int

        jet = Jet.jetFactory(
            version,
            msMoneyFileFormatId == fileFormatId
        )
        return if (bytes.capacity < jet.pageSize)
            UByteBuffer(jet.pageSize, bytes.order)
        else
            bytes
    }

    override fun parse(bytes: UByteBuffer): DatabasePageStructure {
        determineJet(bytes)

        removeRC4Mask(bytes, jet.headerMask)

        bytes.position += 34 // skip some unknown stuff
        var sortOrder = bytes.short.toInt() // only usable on version 3, gets overridden below
        defaultCodePage = bytes.short
        codecKey = bytes.int

        bytes.position = offsetSortOrder
        if (jet.isVersion4)
            sortOrder = bytes.int
        defaultSortOrder = SortOrder.fromValue(sortOrder, version)

        // Creation date which is an 8 byte double is also used as the password salt
        bytes.position = offsetCreationDate
        creationDate = AccessDateTime.dateFromBytes(bytes)

        encryption = when (jet.codecType) {
            Jet.CodecType.NONE -> DatabaseEncryptionStructure.NoEncryption(bytes, jet)
            Jet.CodecType.JET -> DatabaseEncryptionStructure.JetEncryption(bytes, jet, jetHeader)
            Jet.CodecType.MSISAM -> {
                val msiasm = DatabaseEncryptionStructure.MsiasmEncryption(bytes, jet, jetHeader)
                msiasm.parseBuffer()
                msiasm
                /*
                    if (msiasm.newEncryption)
                        DatabaseEncryptionStructure.JetEncryption(
                            bytes,
                            jet,
                            msiasm.getOldDecryptionKey(jetHeader)
                        )
                    else
                        DatabaseEncryptionStructure.MsiasmEncryption(bytes, jet)

                     */
            }
            Jet.CodecType.OFFICE -> DatabaseEncryptionStructure.OfficeEncryption(bytes, jet)
        }
        encryption.parseBuffer()

        bytes.position = 0
        bytes.limit = bytes.capacity
        return this
    }

    /**
     * Both Jet3 and 4 have a mask that is obfuscated with RC4.  This function removes it.  Position
     * of the ByteBuffer remains at the first byte of the mask after RC4 flip.
     * RC4 key 0x6b39dac7
     */
    fun removeRC4Mask(bytes: UByteBuffer, headerMask: MutableList<UByte>) {
        bytes.position = rc4Offset
        for (idx in headerMask.indices) {
            bytes.byte = bytes.getElementAt(bytes.position) xor headerMask[idx]
        }
        bytes.position = rc4Offset
    }

    override fun setBytes(bytes: UByteBuffer) {
    }

    companion object {
        private const val msMoneyFileFormatId = "MSISAM Database"

        internal const val rc4Offset = 0x18
        private const val offsetSortOrder = 0x56 + rc4Offset

        // Creation date which is an 8 byte double is also used as the password salt
        internal const val offsetCreationDate = 0x5A + rc4Offset

        private const val zeroByte: UByte = 0u
        private fun nullTerminatedString(bytes: UByteBuffer, sizeLimit: Int = -1): String {
            val length = if (sizeLimit >= 0) sizeLimit else bytes.remaining
            val searchBytes = bytes.contentBytes.slice(bytes.position..bytes.position + length)
            val index = searchBytes.indexOfFirst { it == zeroByte }
            return if (index < 0) "" else {
                Jet.utf8Charset.decode(searchBytes.slice(0 until index).toUByteArray().toByteArray())
            }
        }
    }
}
