package com.oldguy.jillcess.cryptography

import com.oldguy.common.io.Base64
import com.oldguy.common.io.charsets.Utf8
import com.oldguy.xml.Attribute
import com.oldguy.xml.Node
import kotlinx.cinterop.*


/**
 * Linux platform-specific implementation uses libxml2 pull parser and Base64 decode to parse the XML metadata used
 * by the Access Agile encryption algorithm.
 *
 * See schema info at:
 */
@OptIn(ExperimentalForeignApi::class)
actual class OfficeAgile actual constructor() {
    actual fun parseXml(xml: String): CTEncryption {
        val rootName = "encryption"
        val result = CTEncryption()
        memScoped {
            val uBytes = allocArray<UByteVar>(xml.length)
            Utf8().encode(xml).forEachIndexed { i, c -> uBytes[i] = c.toUByte() }
            val doc = xmlParseDoc(uBytes)
                ?: throw IllegalStateException("Failed to parse XML Agile metadata")
            mapNode(xmlDocGetRootElement(doc)).apply {
                if (name != rootName)
                    throw IllegalStateException("Failed to find $rootName root node in Agile metadata")
                find("keyData")?.let {
                    mapKeyData(it, result)
                }
                find("dataIntegrity")?.let {
                    mapDataIntegrity(it, result)
                }
                find("keyEncryptors")?.let { node ->
                    node.children.forEach { keyEnc ->
                        if (keyEnc.name != "keyEncryptor")
                            throw IllegalStateException("Unexpected child: ${keyEnc.name}, should be keyEncryptor")
                        keyEnc.findAttribute("uri")?.let { uri ->
                            val ct = CTKeyEncryptor(uri.value)
                            if (keyEnc.children.size != 1)
                                throw IllegalStateException("Unexpected number of keyEncryptor children: ${keyEnc.children.size}")
                            keyEnc.children[0].also { pEncKey ->
                                if (pEncKey.name != "p:encryptedKey") {
                                    throw IllegalStateException("Unexpected child: ${pEncKey.name}, should be p:encryptedKey")
                                }
                                ct.keyEncryptor.apply {
                                    pEncKey.attributes.values.forEach {
                                        when (it.name) {
                                            "saltSize" -> saltSize = it.value.toInt()
                                            "blockSize" -> blockSize = it.value.toInt()
                                            "keyBits" -> keyBits = it.value.toInt()
                                            "hashSize" -> hashSize = it.value.toInt()
                                            "cipherAlgorithm" -> cipherAlgorithm = it.value
                                            "cipherChaining" -> cipherChaining = it.value
                                            "hashAlgorithm" -> hashAlgorithm = it.value
                                            "saltValue" -> saltValue =
                                                Base64.decodeToBytes(it.value).toUByteArray()
                                            "spinCount" -> spinCount = it.value.toInt()
                                            "encryptedVerifierHashInput" -> encryptedVerifierHashInput =
                                                Base64.decodeToBytes(it.value)
                                                    .toUByteArray()
                                            "encryptedVerifierHashValue" -> encryptedVerifierHashValue =
                                                Base64.decodeToBytes(it.value)
                                                    .toUByteArray()
                                            "encryptedKeyValue" -> encryptedKeyValue =
                                                Base64.decodeToBytes(it.value)
                                                    .toUByteArray()
                                        }
                                    }
                                }
                            }
                            result.keyEncryptors.encryptorList.add(ct)
                        }
                    }
                }
            }
            xmlFreeDoc(doc)
            xmlCleanupParser()
            xmlMemoryDump()
        }
        return result
    }

    fun mapKeyData(node: Node, result: CTEncryption) {
        node.attributes.values.forEach {
            when (it.name) {
                "saltSize" -> result.keyData.saltSize = it.value.toInt()
                "blockSize" -> result.keyData.blockSize = it.value.toInt()
                "keyBits" -> result.keyData.keyBits = it.value.toInt()
                "hashSize" -> result.keyData.hashSize = it.value.toInt()
                "cipherAlgorithm" -> result.keyData.cipherAlgorithm = it.value
                "cipherChaining" -> result.keyData.cipherChaining = it.value
                "hashAlgorithm" -> result.keyData.hashAlgorithm = it.value
                "saltValue" -> result.keyData.saltValue =
                    Base64.decodeToBytes(it.value)
            }
        }
    }

    fun mapDataIntegrity(node: Node, result: CTEncryption) {
        node.attributes.values.forEach {
            when (it.name) {
                "encryptedHmacKey" -> result.dataIntegrity.encryptedHmacKey =
                    Base64.decodeToBytes(it.value)
                "encryptedHmacValue" -> result.dataIntegrity.encryptedHmacValue =
                    Base64.decodeToBytes(it.value)
            }
        }
    }

    private fun mapNode(nodePtr: CPointer<xmlNode>?): Node {
        nodePtr ?: return Node("", "", emptyMap(), emptyList())
        val pName = nodePtr.pointed.name
            ?: throw IllegalStateException("Parse nodePtr: name is null")
        val pContent = nodePtr.pointed.content
        val children = mutableListOf<Node>()
        var child = nodePtr.pointed.children
        while (child != null) {
            children.add(mapNode(child))
            child = child.pointed.next
        }
        return Node(
            map(pName),
            map(pContent),
            map(nodePtr.pointed.properties),
            children
        )
    }

    private fun map(ptr: CPointer<_xmlAttr>?): Map<String, Attribute> {
        ptr ?: return emptyMap()
        val result = mutableMapOf<String, Attribute>()
        var attr = ptr
        while (attr != null) {
            val name = mapUBytes(attr.pointed.name)
            result[name] =
                Attribute(
                    name,
                    map(attr.pointed.children?.pointed?.content)
                )
            attr = attr.pointed.next
        }
        return result
    }


    private fun map(ptr: CPointer<xmlCharVar>?): String {
        return ptr?.readBytes(xmlStrlen(ptr))?.toKString() ?: ""
    }

    private fun mapUBytes(ptr: CPointer<UByteVarOf<UByte>>?): String {
        return ptr?.readBytes(xmlStrlen(ptr))?.toKString() ?: ""
    }
}