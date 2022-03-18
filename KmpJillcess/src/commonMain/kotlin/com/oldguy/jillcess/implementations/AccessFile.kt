package com.oldguy.jillcess.implementations

import com.oldguy.common.io.*
import com.oldguy.jillcess.Table
import com.oldguy.jillcess.cryptography.*
import kotlin.coroutines.CoroutineContext

/**
 * Will encapsulate IO to and from the Access jet disk file
 * Use this for reference when code doesn't determine correct answer
 *  https://github.com/brianb/mdbtools/blob/master/HACKING
 */
class AccessFile(
    private val accessDatabase: AccessDatabase,
    private val filePath: String,
    val mode: DatabaseFile.Mode = DatabaseFile.Mode.Read,
    val endian: Endian = Endian.Little
) : DatabaseFile {

    lateinit var jet: Jet internal set
    val pageSize get() = jet.pageSize
    lateinit var codec: Codec
    private lateinit var globalUsageMap: GlobalUsageMap

    private val byteOrder get() = if (endian == Endian.Little) Buffer.ByteOrder.LittleEndian else Buffer.ByteOrder.BigEndian
    val page by lazy { UByteBuffer(pageSize, byteOrder) }

    val name = File(filePath).nameWithoutExtension

    private lateinit var rawFile: RawFile

    override fun enableCoroutines(coroutineContext: CoroutineContext) {
    }

    override suspend fun open() {
        rawFile = RawFile(File(filePath), FileMode.Read)
        parsePage(0)

        // at this point format of the file being opened has been determined
        globalUsageMap = GlobalUsageMap(jet)
        globalUsageMap.parsePage(readPage(1), 1)
    }

    override suspend fun create(
        fileExtensions: FileExtensions
    ) {
        val opt = formatMap[fileExtensions] ?: error("Unsupported file ext: $fileExtensions")
        if (opt.format.readOnly)
            throw FileException("File jet $fileExtensions does not support writing for $filePath")

        rawFile = RawFile(File(filePath), FileMode.Write)
        val templateFile = RawFile(File(opt.emptyFilePath + fileExtensions.fileExtension))
        try {
            rawFile.truncate(0u)
            rawFile.transferFrom(
                templateFile, 0u,
                MAX_EMPTYDB_SIZE.toULong()
            )
            open()
        } finally {
            rawFile.close()
            templateFile.close()
        }
    }

    override suspend fun close() {
        rawFile.close()
    }

    private fun pagePosition(pageNumber: Int): Long {
        return if (pageNumber == 0) 0L else (pageNumber * pageSize).toLong()
    }

    suspend fun readPage(pageNumber: Int): UByteBuffer {
        // if root page, pageSize for rest of file is determined after first part of page read and parsed
        val pageBuf = if (pageNumber == 0)
            UByteBuffer(PageSize.Version3.size, byteOrder)
        else {
            page.clear()
            page
        }
        readPage(pageBuf, pageNumber)
        return pageBuf
    }

    suspend fun readSidePage(pageNumber: Int): UByteBuffer {
        val pageBuf = UByteBuffer(pageSize, byteOrder)
        readPage(pageBuf, pageNumber)
        return pageBuf
    }

    private suspend fun readPage(page: UByteBuffer, pageNumber: Int) {
        val bytes = rawFile.read(page, pagePosition(pageNumber).toULong(), true)
        if (bytes != page.capacity.toUInt())
            throw FileStructureException("Page $pageNumber is incomplete, expected ${page.capacity}, read $bytes")

        if (pageNumber > 0) {
            val decryptedPage = codec.decodePage(page, pageNumber)
            page.copy(decryptedPage)
            page.clear()
        }
    }

    override suspend fun parsePage(pageNumber: Int) {
        var pageBuf = readPage(pageNumber)
        var dbPage: DatabasePage? = null
        val pageParser = when (PageType.fromByte(pageBuf.get(0))) {
            PageType.Database -> {
                // determine Jet version which determines page size. If new page size is larger, re-read
                dbPage = DatabasePage(accessDatabase)
                val tempBuf = dbPage.determineJet(pageBuf)
                if (tempBuf.capacity > pageBuf.capacity) {
                    readPage(tempBuf, pageNumber)
                    pageBuf = tempBuf
                }
                dbPage
            }
            PageType.DataPage -> DataPage(jet)
            PageType.TableDefinition -> TableDefinitionPages(this) { table ->
                accessDatabase.tableDefinition(
                    table
                )
            }
            PageType.IntermediateIndex -> throw IllegalStateException("Index node pages are parsed by AccessIndex")
            PageType.LeafIndex -> throw IllegalStateException("Index leaf pages are parsed by AccessIndex")
            PageType.UsageMap -> throw IllegalStateException("UsageMap reference pages are read during parsing of the Indirect usage map that refers to it")
            PageType.NotSet -> throw FileStructureException(
                "Page nmber $pageNumber has an invalid type ${
                    pageBuf.get(
                        0
                    )
                }"
            )
        }
        pageParser.parsePage(pageBuf, pageNumber)
        if (dbPage != null) codec = dbPage.codec
    }

    override suspend fun parseTablePage(name: String, pageNumber: Int) {
        val pageBuf = readPage(pageNumber)
        val pageParser = TableDefinitionPages(
            name,
            this
        ) { table: Table -> accessDatabase.tableDefinition(table) }
        pageParser.parsePage(pageBuf, pageNumber)
        pageBuf.flip()
    }

    override suspend fun writePage(pageNumber: Int, buffer: ByteArray) {
    }

    private data class FileFormatNames(val format: Jet, val emptyFilePath: String = "")

    companion object {
        private const val MAX_EMPTYDB_SIZE = 370000L

        /** additional internal details about each FileFormat  */
        private val jet4 = Jet.Jet4()
        private val formatMap = mapOf(
            FileExtensions.V1997 to FileFormatNames(
                Jet.Jet3()
            ),
            FileExtensions.GENERIC_JET4 to FileFormatNames(
                jet4
            ),
            FileExtensions.V2000 to FileFormatNames(
                jet4,
                "empty"
            ),
            FileExtensions.V2003 to FileFormatNames(
                jet4,
                "empty2003"
            ),
            FileExtensions.V2007 to FileFormatNames(
                Jet.Jet12(),
                "empty2007"
            ),
            FileExtensions.V2010 to FileFormatNames(
                Jet.Jet14(),
                "empty2010"
            ),
            FileExtensions.V2016 to FileFormatNames(
                Jet.Jet16(),
                "empty2016"
            ),
            FileExtensions.MSISAM to FileFormatNames(
                Jet.JetMSISAM()
            )
        )
    }
}

/**
 * Every page type must implement these
 */
abstract class AccessPage<T>(val pageType: PageType, val jet: Jet) {
    val isVersion4 get() = jet.isVersion4

    abstract suspend fun parsePage(page: UByteBuffer, pageNumber: Int): T
}

/**
 * Parses page zero, creates an AccessDatabase. The observer is notified, and the AccessDatabase is
 * returned as well. If the database has encryption, a Codec is assigned
 */
class DatabasePage(private val accessDatabase: AccessDatabase) :
    AccessPage<DatabasePageStructure>(PageType.Database, Jet.Jet3()) {

    lateinit var codec: Codec

    /**
     * Parse enough of page 0 to determine Jet version page size.  If required page size is bigger,
     * make a new ByteBuffer of the correct size
     * @param bytes with at least the Jet3
     * @return properly sized ByteBuffer for current Jet version
     */
    fun determineJet(bytes: UByteBuffer): UByteBuffer {
        return DatabasePageStructure().determineJet(bytes)
    }

    override suspend fun parsePage(page: UByteBuffer, pageNumber: Int): DatabasePageStructure {
        val dbPage = DatabasePageStructure(pageNumber, accessDatabase.password)
        dbPage.parse(page)
        dbPage.encryption.let {
            codec = when (it) {
                is DatabaseEncryptionStructure.NoEncryption -> NoopCodec()
                is DatabaseEncryptionStructure.MsiasmEncryption ->
                    MsiasmCodec(accessDatabase.password, it)
                is DatabaseEncryptionStructure.JetEncryption ->
                    JetCodec(it, accessDatabase.password)
                is DatabaseEncryptionStructure.OfficeEncryption ->
                    when (it.provider) {
                        DatabaseEncryptionStructure.OfficeEncryption.EncryptionAlgorithm.None -> TODO()
                        DatabaseEncryptionStructure.OfficeEncryption.EncryptionAlgorithm.Agile ->
                            AgileCodec(accessDatabase.password, it)
                        DatabaseEncryptionStructure.OfficeEncryption.EncryptionAlgorithm.OfficeBinaryDocRC4 -> TODO()
                        DatabaseEncryptionStructure.OfficeEncryption.EncryptionAlgorithm.ECMAStandard -> TODO()
                        DatabaseEncryptionStructure.OfficeEncryption.EncryptionAlgorithm.RC4CryptoAPI ->
                            OfficeRC4Codec(accessDatabase.password, it)
                        DatabaseEncryptionStructure.OfficeEncryption.EncryptionAlgorithm.NonStandard -> TODO()
                    }
                is DatabaseEncryptionStructure.JetBaseEncryption ->
                    throw IllegalStateException("JetBaseEncryption is abstract")
            }
        }
        accessDatabase.setUp(dbPage)
        return dbPage
    }
}
