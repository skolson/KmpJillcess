package com.oldguy.jillcess

import com.oldguy.common.io.Base64
import com.oldguy.common.io.Charset
import com.oldguy.common.io.Charsets
import com.oldguy.jillcess.cryptography.CTEncryption
import com.oldguy.jillcess.cryptography.CTKeyEncryptor
import kotlinx.cinterop.memScoped
import platform.Foundation.*
import platform.darwin.NSObject

class OfficeAgileAppleParser {

    fun parseXml(xml: String): CTEncryption {
        var result = CTEncryption()
        memScoped {
            NSXMLParser((xml as NSString).dataUsingEncoding(NSUTF8StringEncoding)!!).apply {
                delegate = CtEncryptParser {
                    result = it
                }
            }.parse()
        }
        return result
    }

    /**
     * Using the delegate protocol, construct an instance of CTEncryption
     */
    @Suppress("UNCHECKED_CAST")
    private class CtEncryptParser(
        private val onEnd: (CTEncryption) -> Unit
    ) : NSObject(), NSXMLParserDelegateProtocol {
        val result = CTEncryption()
        val elementStack = ArrayDeque<String>(6)
        var elementName = ""
        val rootName = "encryption"
        var encryptorListIndex = -1
        val charset = Charset(Charsets.Utf8)

        override fun parser(
            parser: NSXMLParser,
            didStartElement: String,
            namespaceURI: String?,
            qualifiedName: String?,
            attributes: Map<Any?, *>
        ) {
            val attrs: Map<String, String> = attributes as Map<String, String>
            elementName = didStartElement
            when (elementName) {
                rootName -> {
                    if (elementStack.isNotEmpty())
                        throw IllegalStateException("XML Agile metadata root name invalid: $elementName")
                }
                "keyData" -> {
                    if (elementStack.first() != rootName)
                        throw IllegalStateException("keyData in wrong parent: ${elementStack.first()}")
                    attrs.keys.forEach {
                        val value = attrs[it] ?: throw IllegalStateException("Bug: no key $it found in attributes map: $attrs")
                        when (it) {
                            "saltSize" -> result.keyData.saltSize = value.toInt()
                            "blockSize" -> result.keyData.blockSize = value.toInt()
                            "keyBits" -> result.keyData.keyBits = value.toInt()
                            "hashSize" -> result.keyData.hashSize = value.toInt()
                            "cipherAlgorithm" -> result.keyData.cipherAlgorithm = value
                            "cipherChaining" -> result.keyData.cipherChaining = value
                            "hashAlgorithm" -> result.keyData.hashAlgorithm = value
                            "saltValue" -> result.keyData.saltValue = Base64.decodeToBytes(charset.encode(value))
                        }
                    }
                }
                "dataIntegrity" -> {
                    if (elementStack.first() != rootName)
                        throw IllegalStateException("dataIntegrity tag should only be part of $rootName, not: ${elementStack.first()}")
                    attrs.keys.forEach {
                        val value = attrs[it]
                            ?: throw IllegalStateException("Bug: no key $it found in attributes map: $attrs")
                        when (it) {
                            "encryptedHmacKey" -> result.dataIntegrity.encryptedHmacKey =
                                Base64.decodeToBytes(charset.encode(value))
                            "encryptedHmacValue" -> result.dataIntegrity.encryptedHmacValue =
                                Base64.decodeToBytes(charset.encode(value))
                        }
                    }
                }
                "keyEncryptor" -> {
                    if (elementStack.first() != "keyEncryptors")
                        throw IllegalStateException("dataIntegrity tag should only be part of keyEncryptors, not: ${elementStack.first()}")
                    val uri = attrs["uri"] ?: ""
                    result.keyEncryptors.encryptorList.add(CTKeyEncryptor(uri))
                }
                "p:encryptedKey" -> {
                    if (elementStack.first() != "keyEncryptor")
                        throw IllegalStateException("p:encryptedKey tag should only be part of keyEncryptor, not: ${elementStack.first()}")
                    val key =
                        result.keyEncryptors.encryptorList[encryptorListIndex].keyEncryptor
                    attrs.keys.forEach {
                        val value = attrs[it]
                            ?: throw IllegalStateException("Bug: no key $it found in attributes map: $attrs")
                        when (it) {
                            "saltSize" -> key.saltSize = value.toInt()
                            "blockSize" -> key.blockSize = value.toInt()
                            "keyBits" -> key.keyBits = value.toInt()
                            "hashSize" -> key.hashSize = value.toInt()
                            "cipherAlgorithm" -> key.cipherAlgorithm = value
                            "cipherChaining" -> key.cipherChaining = value
                            "hashAlgorithm" -> key.hashAlgorithm = value
                            "saltValue" -> key.saltValue =
                                Base64.decodeToBytes(charset.encode(value)).toUByteArray()
                            "spinCount" -> key.spinCount = value.toInt()
                            "encryptedVerifierHashInput" -> key.encryptedVerifierHashInput =
                                Base64.decodeToBytes(charset.encode(value)).toUByteArray()
                            "encryptedVerifierHashValue" -> key.encryptedVerifierHashValue =
                                Base64.decodeToBytes(charset.encode(value)).toUByteArray()
                            "encryptedKeyValue" -> key.encryptedKeyValue =
                                Base64.decodeToBytes(charset.encode(value)).toUByteArray()
                        }
                    }
                }
                else -> {
                    throw IllegalStateException("Unexpected elementName: $elementName")
                }
            }
            elementStack.addFirst(elementName)
        }

        override fun parser(parser: NSXMLParser, foundCharacters: String) {
            throw IllegalStateException("No elements have text. Value found: $foundCharacters. Stack: $elementStack")
        }

        /**
         * End tag processing
         */
        override fun parser(
            parser: NSXMLParser,
            didEndElement: String,
            namespaceURI: String?,
            qualifiedName: String?
        ) {
            elementStack.removeFirst()
        }

        override fun parserDidEndDocument(parser: NSXMLParser) {
            onEnd(result)
        }
    }
}