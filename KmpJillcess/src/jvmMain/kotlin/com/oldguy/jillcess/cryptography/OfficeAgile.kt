package com.oldguy.jillcess.cryptography

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * JVM/Android platform-specific implementation uses XML pull parser and Base64 decode to parse the XML metadata used
 * by the Access Agile encryption algorithm.
 *
 * See schema info at:
 */
actual class OfficeAgile actual constructor() {
    actual fun parseXml(xml: String): CTEncryption {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val xpp = factory.newPullParser()
        xpp.setInput(StringReader(xml))
        var eventType = xpp.eventType
        val result = CTEncryption()
        val elementStack = ArrayDeque<String>(6)
        val rootName = "encryption"
        var encryptorListIndex = -1
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                when (xpp.name) {
                    rootName -> {
                        if (elementStack.isNotEmpty())
                            throw IllegalStateException("XML Agile metadata root name invalid: ${xpp.name}")
                    }
                    "keyData" -> {
                        if (elementStack.first() != rootName)
                            throw IllegalStateException("keyData in wrong parent: ${elementStack.first()}")
                        for (i in 0 until xpp.attributeCount) {
                            when (xpp.getAttributeName(i)) {
                                "saltSize" -> result.keyData.saltSize =
                                    xpp.getAttributeValue(i).toInt()
                                "blockSize" -> result.keyData.blockSize =
                                    xpp.getAttributeValue(i).toInt()
                                "keyBits" -> result.keyData.keyBits =
                                    xpp.getAttributeValue(i).toInt()
                                "hashSize" -> result.keyData.hashSize =
                                    xpp.getAttributeValue(i).toInt()
                                "cipherAlgorithm" -> result.keyData.cipherAlgorithm =
                                    xpp.getAttributeValue(i)
                                "cipherChaining" -> result.keyData.cipherChaining =
                                    xpp.getAttributeValue(i)
                                "hashAlgorithm" -> result.keyData.hashAlgorithm =
                                    xpp.getAttributeValue(i)
                                "saltValue" -> result.keyData.saltValue =
                                    Base64.decode(xpp.getAttributeValue(i), Base64.DEFAULT)
                            }
                        }
                    }
                    "dataIntegrity" -> {
                        if (elementStack.first() != rootName)
                            throw IllegalStateException("dataIntegrity tag should only be part of $rootName, not: ${elementStack.first()}")
                        for (i in 0 until xpp.attributeCount) {
                            when (xpp.getAttributeName(i)) {
                                "encryptedHmacKey" -> result.dataIntegrity.encryptedHmacKey =
                                    Base64.decode(xpp.getAttributeValue(i), Base64.DEFAULT)
                                "encryptedHmacValue" -> result.dataIntegrity.encryptedHmacValue =
                                    Base64.decode(xpp.getAttributeValue(i), Base64.DEFAULT)
                            }
                        }
                    }
                    "keyEncryptor" -> {
                        if (elementStack.first() != "keyEncryptors")
                            throw IllegalStateException("dataIntegrity tag should only be part of keyEncryptors, not: ${elementStack.first()}")
                        var uri = ""
                        for (i in 0 until xpp.attributeCount) {
                            when (xpp.getAttributeName(i)) {
                                "uri" -> uri = xpp.getAttributeValue(i)
                            }
                        }
                        result.keyEncryptors.encryptorList.add(CTKeyEncryptor(uri))
                        encryptorListIndex++
                    }
                    "p:encryptedKey" -> {
                        if (elementStack.first() != "keyEncryptor")
                            throw IllegalStateException("p:encryptedKey tag should only be part of keyEncryptor, not: ${elementStack.first()}")
                        val key =
                            result.keyEncryptors.encryptorList[encryptorListIndex].keyEncryptor
                        for (i in 0 until xpp.attributeCount) {
                            when (xpp.getAttributeName(i)) {
                                "saltSize" -> key.saltSize = xpp.getAttributeValue(i).toInt()
                                "blockSize" -> key.blockSize = xpp.getAttributeValue(i).toInt()
                                "keyBits" -> key.keyBits = xpp.getAttributeValue(i).toInt()
                                "hashSize" -> key.hashSize = xpp.getAttributeValue(i).toInt()
                                "cipherAlgorithm" -> key.cipherAlgorithm = xpp.getAttributeValue(i)
                                "cipherChaining" -> key.cipherChaining = xpp.getAttributeValue(i)
                                "hashAlgorithm" -> key.hashAlgorithm = xpp.getAttributeValue(i)
                                "saltValue" -> key.saltValue =
                                    Base64.decode(xpp.getAttributeValue(i), Base64.DEFAULT)
                                        .toUByteArray()
                                "spinCount" -> key.spinCount = xpp.getAttributeValue(i).toInt()
                                "encryptedVerifierHashInput" -> key.encryptedVerifierHashInput =
                                    Base64.decode(xpp.getAttributeValue(i), Base64.DEFAULT)
                                        .toUByteArray()
                                "encryptedVerifierHashValue" -> key.encryptedVerifierHashValue =
                                    Base64.decode(xpp.getAttributeValue(i), Base64.DEFAULT)
                                        .toUByteArray()
                                "encryptedKeyValue" -> key.encryptedKeyValue =
                                    Base64.decode(xpp.getAttributeValue(i), Base64.DEFAULT)
                                        .toUByteArray()
                            }
                        }
                    }
                }
                elementStack.addFirst(xpp.name)
            } else if (eventType == XmlPullParser.END_TAG) {
                elementStack.removeFirst()
            } else if (eventType == XmlPullParser.TEXT) {
                throw IllegalStateException("No elements have text. Value found: ${xpp.text}. Stack: $elementStack")
            }
            eventType = xpp.next()
        }
        return result
    }
}
