package com.oldguy.jillcess.configuration

import com.oldguy.common.io.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Implementations of this class provide three categories of required configuration information. Implementations will
 * typically be singletons.
 *  1) initial set of name/value properties
 *
 *  2) File instances pointing to the four Index Code files used during parsing/creation of individual Index entries in
 *  the highly complex encoding scheme used to turn indexed data into a generic byte stream that will sort properly no
 *  matter what is encoded.
 *      index_codes_gen.txt         Access2010 and later
 *      index_codes_ext_gen.txt     Access2010 and later
 *      index_codes_genleg.txt      prior to Access2010
 *      index_codes_ext_genleg.txt  prior to Access2010
 *
 */
abstract class Configuration protected constructor(
    val configDirectory: File
) {
    protected val configMap: MutableMap<String, String>

    init {
        configMap = HashMap()
        add(TIMEZONE_PROPERTY, TimeZones.getDefaultId())
    }

    /**
     * Implementers must locate the named resource file in whatever platform-specific way required.
     *
     * @param resourceName will be one of these four names; index_codes_gen.txt, index_codes_ext_gen.txt,
     * index_codes_genleg.txt, index_codes_ext_genleg.txt
     */
    abstract suspend fun indexCodesFile(resourceName: String): TextFile

    suspend fun indexCodeGeneral(): TextFile {
        return indexCodesFile(indexCodeGeneralFileName)
    }

    suspend fun indexCodeGeneralExtra(): TextFile {
        return indexCodesFile(indexCodeGeneralExtraFileName)
    }

    suspend fun indexCodeGeneralLegacy(): TextFile {
        return indexCodesFile(indexCodeGeneralLegacyFileName)
    }

    suspend fun indexCodeGeneralLegacyExtra(): TextFile {
        return indexCodesFile(indexCodeGeneralLegacyExtraFileName)
    }

    protected fun add(propertyName: String, value: String) {
        configMap[propertyName] = value.trim()
    }

    fun getValue(propertyName: String): String {
        return configMap[propertyName]
            ?: throw IllegalArgumentException("Configuration does not contain a value for $propertyName")
    }

    fun containsValue(propertyName: String): Boolean {
        return configMap.contains(propertyName)
    }

    fun isTrue(booleanPropertyName: String): Boolean {
        return getValue(booleanPropertyName).lowercase().compareTo("true") == 0
    }

    companion object {
        const val TIMEZONE_PROPERTY = "timeZone"

        const val indexCodeGeneralFileName = "index_codes_gen.txt"
        const val indexCodeGeneralExtraFileName = "index_codes_ext_gen.txt"
        const val indexCodeGeneralLegacyFileName = "index_codes_genleg.txt"
        const val indexCodeGeneralLegacyExtraFileName = "index_codes_ext_genleg.txt"
    }
}

@Serializable
data class NameValuePair(
    val name: String,
    val value: String
)

class JsonConfiguration(resourcesDirectory: File, jsonData: String)
    : Configuration(resourcesDirectory) {

    override suspend fun indexCodesFile(resourceName: String): TextFile {
        return TextFile(File(configDirectory, resourceName), source = FileSource.File)
    }

    init {
        val list: List<NameValuePair> =
            Json.decodeFromString(ListSerializer(NameValuePair.serializer()), jsonData)
        list.forEach {
            add(it.name, it.value)
        }
    }

    companion object {
        private const val configurationName = "configuration.json"

        /**
         * Parse a JSON configuration file for properties used in Access Database file process
         * @param configDirectory File instance of the directory containing the json file [configurationName]
         * @return configuration instance
         */
        suspend fun build(configDirectory: File): JsonConfiguration {
            return TextFile(File(configDirectory, configurationName)).use {
                return@use JsonConfiguration(
                    configDirectory,
                    buildString {
                        var line = it.readLine()
                        while (line.trim().isNotEmpty()) {
                            append(line)
                            line = it.readLine()
                        }
                    }
                )
            }
        }
    }
}