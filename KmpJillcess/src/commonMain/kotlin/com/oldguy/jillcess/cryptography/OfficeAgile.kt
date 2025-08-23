package com.oldguy.jillcess.cryptography

import com.oldguy.common.io.Base64
import com.oldguy.common.io.TextBuffer
import com.oldguy.common.io.charsets.Utf8
import com.oldguy.markup.XmlParser
import com.oldguy.markup.model.Node
import kotlinx.coroutines.runBlocking

/**
 * These classes are derived from schemas published by Microsoft for Office 2006
 * https://docs.microsoft.com/en-us/openspecs/office_file_formats/ms-offcrypto/87020a34-e73f-4139-99bc-bbdf6cf6fa55
 */

class CTKeyData() {
    var saltSize = 0
    var blockSize = 0
    var keyBits = 0
    var hashSize = 0
    var cipherAlgorithm = ""
    var cipherChaining = ""
    var hashAlgorithm = ""
    var saltValue = ByteArray(0)
}

class CTDataIntegrity() {
    // base 64 encoding on both attributes
    var encryptedHmacKey = ByteArray(0)
    var encryptedHmacValue = ByteArray(0)
}

class CTPasswordKeyEncryptor() {
    var saltSize = 0
    var blockSize = 0
    var keyBits = 0
    val keyBytesSize get() = keyBits / 8
    var hashSize = 0
    var cipherAlgorithm = ""
    var cipherChaining = ""
    var hashAlgorithm = ""
    var saltValue = UByteArray(0)
    var spinCount = 0
    var encryptedVerifierHashInput = UByteArray(0)
    var encryptedVerifierHashValue = UByteArray(0)
    var encryptedKeyValue = UByteArray(0)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as CTPasswordKeyEncryptor

        if (saltSize != other.saltSize) return false
        if (blockSize != other.blockSize) return false
        if (keyBits != other.keyBits) return false
        if (hashSize != other.hashSize) return false
        if (cipherAlgorithm != other.cipherAlgorithm) return false
        if (cipherChaining != other.cipherChaining) return false
        if (hashAlgorithm != other.hashAlgorithm) return false
        if (!saltValue.contentEquals(other.saltValue)) return false
        if (spinCount != other.spinCount) return false
        if (!encryptedVerifierHashInput.contentEquals(other.encryptedVerifierHashInput)) return false
        if (!encryptedVerifierHashValue.contentEquals(other.encryptedVerifierHashValue)) return false
        if (!encryptedKeyValue.contentEquals(other.encryptedKeyValue)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = saltSize.hashCode()
        result = 31 * result + blockSize.hashCode()
        result = 31 * result + keyBits.hashCode()
        result = 31 * result + hashSize.hashCode()
        result = 31 * result + cipherAlgorithm.hashCode()
        result = 31 * result + cipherChaining.hashCode()
        result = 31 * result + hashAlgorithm.hashCode()
        result = 31 * result + saltValue.contentHashCode()
        result = 31 * result + spinCount.hashCode()
        result = 31 * result + encryptedVerifierHashInput.contentHashCode()
        result = 31 * result + encryptedVerifierHashValue.contentHashCode()
        result = 31 * result + encryptedKeyValue.contentHashCode()
        return result
    }
}

class CTKeyEncryptor(val uri: String) {
    var keyEncryptor = CTPasswordKeyEncryptor()
}

class CTKeyEncryptors {
    var encryptorList = mutableListOf<CTKeyEncryptor>()
}

/**
 * Matches the schema at [schema] - see the link below
 *
 * <a href="http://schemas.microsoft.com/office/2006/keyEncryptor/password">CT_Encryption type schema</a>
 *
 * A sample document from one a selected access database looks like this, as an example of what is
 * parsed to determine encryption methods used.
 *
 *
 * <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<encryption xmlns="http://schemas.microsoft.com/office/2006/encryption"
    xmlns:p="http://schemas.microsoft.com/office/2006/keyEncryptor/password">
<keyData saltSize="16" blockSize="16" keyBits="128" hashSize="20" cipherAlgorithm="AES"
cipherChaining="ChainingModeCBC" hashAlgorithm="SHA1" saltValue="DpYj4WNbM6JWXkuaGykRtA=="/>
<keyEncryptors>
    <keyEncryptor uri="http://schemas.microsoft.com/office/2006/keyEncryptor/password">
        <p:encryptedKey spinCount="100000" saltSize="16" blockSize="16" keyBits="128"
            hashSize="20" cipherAlgorithm="AES" cipherChaining="ChainingModeCBC"
            hashAlgorithm="SHA1" saltValue="x8auqBWNRtGDHQwze+aBVg=="
            encryptedVerifierHashInput="VPXJzdncGO4XhFrLmdoILA=="
            encryptedVerifierHashValue="4SEcXpKVsp0g39RV9on+EcJ9/J7+9cpByDyoj7sd9VQ="
            encryptedKeyValue="o0cj94OxoyHGQ6CDwqTdBg=="/>
    </keyEncryptor>
</keyEncryptors>
</encryption>
 */
class CTEncryption {
    var keyData = CTKeyData()
    var dataIntegrity = CTDataIntegrity()
    var keyEncryptors = CTKeyEncryptors()

    fun validate(): CTPasswordKeyEncryptor {
        if (keyEncryptors.encryptorList.isEmpty() ||
            keyEncryptors.encryptorList.size != 1 ||
            keyEncryptors.encryptorList[0].uri.compareTo(schema) != 0
        )
            throw IllegalStateException(
                "Unsupported Agile encryption schema. List size: ${keyEncryptors.encryptorList.size}, " +
                        "uri: ${
                            if (keyEncryptors.encryptorList.isNotEmpty())
                                keyEncryptors.encryptorList[0].uri
                            else ""
                        }"
            )
        return keyEncryptors.encryptorList[0].keyEncryptor
    }

    companion object {
        const val schema = "http://schemas.microsoft.com/office/2006/keyEncryptor/password"
    }
}

class OfficeAgile() {
    fun parseXml(xml: ByteArray): CTEncryption {
        var c = 0
        XmlParser(
            TextBuffer(Utf8(), 1024) { bytes, count ->
                if (c++ == 0) {
                    xml.copyInto(bytes, 0, 0, count)
                    xml.size.toUInt()
                } else 0u
            }
        ).apply {
            domParser = true
            pullParser = false
            runBlocking {
                parse { _, _ -> true }
            }
            return document.root?.let {
                transform(it)
            } ?: throw IllegalStateException("No encryption root node found by parser")
        }
    }

    fun transform(root: Node): CTEncryption {
        val ct = CTEncryption()
        root.traverse { parent, node ->
            when (node.name) {
                "keyData" -> {
                    CTKeyData().apply {
                        ct.keyData = this
                        saltSize = node.attribute("saltSize")?.value?.toInt() ?: 0
                        blockSize = node.attribute("blockSize")?.value?.toInt() ?: 0
                        keyBits = node.attribute("keyBits")?.value?.toInt() ?: 0
                        hashSize = node.attribute("hashSize")?.value?.toInt() ?: 0
                        cipherAlgorithm = node.attribute("cipherAlgorithm")?.value ?: ""
                        cipherChaining = node.attribute("cipherChaining")?.value ?: ""
                        hashAlgorithm = node.attribute("hashAlgorithm")?.value ?: ""
                        saltValue = Base64.decodeToBytes(node.attribute("saltValue")?.value ?: "")
                    }
                }
                "keyEncryptors" -> {
                    ct.keyEncryptors = CTKeyEncryptors()
                }
                "keyEncryptor" -> {
                    ct.keyEncryptors.encryptorList.add(
                        CTKeyEncryptor(node.attribute("uri")?.value ?: "")
                    )
                }
                "p:encryptedKey" -> {
                    CTPasswordKeyEncryptor().apply {
                        ct.keyEncryptors.encryptorList[0].keyEncryptor = this
                        spinCount = node.attribute("spinCount")?.value?.toInt() ?: 0
                        saltSize = node.attribute("saltSize")?.value?.toInt() ?: 0
                        blockSize = node.attribute("blockSize")?.value?.toInt() ?: 0
                        keyBits = node.attribute("keyBits")?.value?.toInt() ?: 0
                        hashSize = node.attribute("hashSize")?.value?.toInt() ?: 0
                        cipherAlgorithm = node.attribute("cipherAlgorithm")?.value ?: ""
                        cipherChaining = node.attribute("cipherChaining")?.value ?: ""
                        hashAlgorithm = node.attribute("hashAlgorithm")?.value ?: ""
                        saltValue = Base64.decodeToBytes(
                            node.attribute("saltValue")?.value ?: ""
                        ).toUByteArray()
                        encryptedVerifierHashInput = Base64.decodeToBytes(
                            node.attribute("encryptedVerifierHashInput")?.value ?: ""
                        ).toUByteArray()
                        encryptedVerifierHashValue = Base64.decodeToBytes(
                            node.attribute("encryptedVerifierHashValue")?.value ?: ""
                        ).toUByteArray()
                        encryptedKeyValue = Base64.decodeToBytes(
                            node.attribute("encryptedKeyValue")?.value ?: ""
                        ).toUByteArray()
                    }
                }
                else -> {
                    throw IllegalStateException("Unexpected node: ${node.name}")
                }
            }
        }
        return ct
    }
}