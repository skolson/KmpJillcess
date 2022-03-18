package com.oldguy.jillcess.implementations

import com.oldguy.common.io.Buffer
import com.oldguy.common.io.Charset
import com.oldguy.common.io.Charsets

enum class PageSize(val size: Int) {
    Version3(2048),
    Version4(4096)
}

enum class SortOrder(val sortOrderValue: Int, val version: Int) {
    GeneralSortOrder(1033, 1),
    LegacySortOrder(1033, 0);

    companion object {
        fun fromValue(value: Int, version: Int): SortOrder {
            if (value == LegacySortOrder.sortOrderValue && version == LegacySortOrder.version)
                return LegacySortOrder
            return GeneralSortOrder
        }
    }
}

/**
 * Enum which indicates which version of Access created the database.
 * @usage _general_class_
 */
enum class FileExtensions(
    val fileExtension: String
) {

    /** A database which was created by MS Access 97  */
    V1997(".mdb"),

    /** A database which was most likely created programmatically (e.g. using
     * windows ADOX)  */
    GENERIC_JET4(".mdb"),

    /** A database which was created by MS Access 2000  */
    V2000(".mdb"),

    /** A database which was created by MS Access 2002/2003  */
    V2003(".mdb"),

    /** A database which was created by MS Access 2007  */
    V2007(".accdb"),

    /** A database which was created by MS Access 2010+  */
    V2010(".accdb"),

    /** A database which was created by MS Access 2016+  */
    V2016(".accdb"),

    /** A database which was created by MS Money  */
    MSISAM(".mny");

    override fun toString(): String {
        return "File extension: $fileExtension, name: $name "
    }

    companion object {
        private const val DEFAULT = "Default"

        /** value of the "AccessVersion" property for access 2000 dbs:
         * `"08.50"`  */
        private const val ACCESS_VERSION_2000 = "08.50"

        /** value of the "AccessVersion" property for access 2002/2003 dbs
         * `"09.50"`   */
        private const val ACCESS_VERSION_2003 = "09.50"

        val POSSIBLE_VERSION_3 = mapOf(DEFAULT to V1997)
        val POSSIBLE_VERSION_4 = mapOf(
            DEFAULT to GENERIC_JET4,
            ACCESS_VERSION_2000 to V2000,
            ACCESS_VERSION_2003 to V2003
        )
        val POSSIBLE_VERSION_12 = mapOf(DEFAULT to V2007)
        val POSSIBLE_VERSION_14 = mapOf(DEFAULT to V2010)
        val POSSIBLE_VERSION_16 = mapOf(DEFAULT to V2016)
        val POSSIBLE_VERSION_MSISAM = mapOf(DEFAULT to MSISAM)

        fun fromFilePath(filePath: String): List<FileExtensions> {
            val list = emptyList<FileExtensions>().toMutableList()
            values()
                .filter { filePath.endsWith(it.fileExtension, ignoreCase = true) }
                .forEach { list.add(it) }
            return list
        }

        fun getDefaultFileExtensions(possibleFormats: Map<String, FileExtensions>): FileExtensions {
            return if (possibleFormats.size == 1)
                possibleFormats.get(DEFAULT)
                    ?: throw IllegalStateException("Missing default jet: $possibleFormats")
            else
                throw IllegalStateException("Too many possible formats: ${possibleFormats.size}")
        }
    }
}

sealed class Jet(val name: String, var version: Version = Version.Access2007) {
    enum class Version(val value: Int) {
        Access97(0),
        Access2000_2002_2003(1),
        Access2007(2),
        Access2010(0x0103),
        Access2016(5);

        companion object {
            fun parse(version: Int): Version {
                return when (version) {
                    0 -> Access97
                    1 -> Access2000_2002_2003
                    2 -> Access2007
                    0x0103 -> Access2010
                    else -> throw FileStructureException("Expected Access version code invalid: $version")
                }
            }
        }
    }

    enum class CodecType {
        NONE, JET, MSISAM, OFFICE
    }

    val isVersion3: Boolean get() = version == Version.Access97
    val isVersion4: Boolean get() = version != Version.Access97
    val pageVersion = if (isVersion3) PageSize.Version3 else PageSize.Version4
    val pageSize = pageVersion.size
    val dataPageFreeSpace = pageSize - 14
    var endian = Buffer.ByteOrder.LittleEndian

    var codecType = CodecType.JET
        internal set
    var readOnly = false
        internal set
    var indexesSupported = true
        internal set
    var maxDatabaseSize: Long = 0
        internal set
    var maxRowSize: Int = pageSize - 36
        internal set

    val headerMask = rc4HeaderMask.toMutableList()
    var propertyMaskType: ByteArray = propertyMapTypes[1]
        internal set
    var possibleFileExtensions = FileExtensions.POSSIBLE_VERSION_3
        internal set
    var sortOrder: SortOrder = SortOrder.GeneralSortOrder
        internal set

    var charset: Charset = Charset(Charsets.Utf16le)
        internal set

    val maxCompressedUnicodeSize = 1024
    var legacyNumericIndexes = true
        internal set
    var offsetNextComplexAutoNumber = -1
        internal set

    class Jet3 : Jet("VERSION_3", Version.Access97) {
        init {
            readOnly = true
            maxDatabaseSize = 1L * 1024L * 1024L * 1024L
            indexesSupported = false
            headerMask.removeAt(headerMask.size - 1)
            headerMask.removeAt(headerMask.size - 1)
            propertyMaskType = propertyMapTypes[0]
            charset = Charset(Charsets.Iso8859_1)
            sortOrder = SortOrder.LegacySortOrder
        }
    }

    open class Jet4(name: String = "VERSION_4", version: Version = Version.Access2000_2002_2003) :
        Jet(name, version) {
        init {
            maxDatabaseSize = 2L * 1024L * 1024L * 1024L
            possibleFileExtensions = FileExtensions.POSSIBLE_VERSION_4
        }
    }

    class JetMSISAM : Jet("MSISAM", Version.Access2000_2002_2003) {
        init {
            possibleFileExtensions = FileExtensions.POSSIBLE_VERSION_MSISAM
            codecType = CodecType.MSISAM
        }
    }

    open class Jet12(name: String = "VERSION_12", version: Version = Version.Access2007) :
        Jet4(name, version) {
        init {
            possibleFileExtensions = FileExtensions.POSSIBLE_VERSION_12
            codecType = CodecType.OFFICE
            legacyNumericIndexes = false
            offsetNextComplexAutoNumber = 28
        }
    }

    open class Jet14(name: String = "VERSION_14", version: Version = Version.Access2010) :
        Jet12(name, version) {
        init {
            possibleFileExtensions = FileExtensions.POSSIBLE_VERSION_14
            codecType = CodecType.OFFICE
            legacyNumericIndexes = false
        }
    }

    class Jet16 :
        Jet14("VERSION_16", Version.Access2016) {
        init {
            possibleFileExtensions = FileExtensions.POSSIBLE_VERSION_16
        }
    }

    companion object {
        val utf8Charset = Charset(Charsets.Utf8)

        /** known intro bytes for property maps  */
        val propertyMapTypes = arrayOf(
            byteArrayOf(
                'M'.code.toByte(),
                'R'.code.toByte(),
                '2'.code.toByte(),
                '\u0000'.code.toByte()
            ), // access 2000+
            byteArrayOf(
                'K'.code.toByte(),
                'K'.code.toByte(),
                'D'.code.toByte(),
                '\u0000'.code.toByte()
            ) // access 97
        )

        /** mask used to obfuscate the db header RC4 key 0x6b39dac7 */
        val rc4HeaderMask = ubyteArrayOf(
            0xB5u,
            0x6Fu,
            0x03u,
            0x62u,
            0x61u,
            0x08u,
            0xC2u,
            0x55u,
            0xEBu,
            0xA9u,
            0x67u,
            0x72u,
            0x43u,
            0x3Fu,
            0x00u,
            0x9Cu,
            0x7Au,
            0x9Fu,
            0x90u,
            0xFFu,
            0x80u,
            0x9Au,
            0x31u,
            0xC5u,
            0x79u,
            0xBAu,
            0xEDu,
            0x30u,
            0xBCu,
            0xDFu,
            0xCCu,
            0x9Du,
            0x63u,
            0xD9u,
            0xE4u,
            0xC3u,
            0x7Bu,
            0x42u,
            0xFBu,
            0x8Au,
            0xBCu,
            0x4Eu,
            0x86u,
            0xFBu,
            0xECu,
            0x37u,
            0x5Du,
            0x44u,
            0x9Cu,
            0xFAu,
            0xC6u,
            0x5Eu,
            0x28u,
            0xE6u,
            0x13u,
            0xB6u,
            0x8Au,
            0x60u,
            0x54u,
            0x94u,
            0x7Bu,
            0x36u,
            0xF5u,
            0x72u,
            0xDFu,
            0xB1u,
            0x77u,
            0xF4u,
            0x13u,
            0x43u,
            0xCFu,
            0xAFu,
            0xB1u,
            0x33u,
            0x34u,
            0x61u,
            0x79u,
            0x5Bu,
            0x92u,
            0xB5u,
            0x7Cu,
            0x2Au,
            0x05u,
            0xF1u,
            0x7Cu,
            0x99u,
            0x01u,
            0x1Bu,
            0x98u,
            0xFDu,
            0x12u,
            0x4Fu,
            0x4Au,
            0x94u,
            0x6Cu,
            0x3Eu,
            0x60u,
            0x26u,
            0x5Fu,
            0x95u,
            0xF8u,
            0xD0u,
            0x89u,
            0x24u,
            0x85u,
            0x67u,
            0xC6u,
            0x1Fu,
            0x27u,
            0x44u,
            0xD2u,
            0xEEu,
            0xCFu,
            0x65u,
            0xEDu,
            0xFFu,
            0x07u,
            0xC7u,
            0x46u,
            0xA1u,
            0x78u,
            0x16u,
            0x0Cu,
            0xEDu,
            0xE9u,
            0x2Du,
            0x62u,
            0xD4u
        )

        fun jetFactory(versionCode: Int, isMsisam: Boolean): Jet {
            return when (Version.parse(versionCode)) {
                Version.Access97 -> Jet3()
                Version.Access2000_2002_2003 -> if (isMsisam) JetMSISAM() else Jet4()
                Version.Access2007 -> Jet12()
                Version.Access2010 -> Jet14()
                Version.Access2016 -> Jet16()
            }
        }
    }
}
