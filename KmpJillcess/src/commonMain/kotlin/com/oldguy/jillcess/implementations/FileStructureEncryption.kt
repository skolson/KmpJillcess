package com.oldguy.jillcess.implementations

import com.oldguy.crypto.Digest
import com.oldguy.crypto.MD5Digest
import com.oldguy.crypto.SHA1Digest
import com.oldguy.common.io.UByteBuffer
import kotlin.math.min

/**
 * These classes parse metadata from portions of the DatabasePage related to encryption
 */
enum class CryptoAlgorithm(
    val verifierHashLength: Int,
    val keySizeMin: Int,
    val keySizeMax: Int
) {
    // the CryptoAPI gives a valid range of 40-128 bits.  the CNG spec
    External(0, 0, 0),

    // (http://msdn.microsoft.com/en-us/library/windows/desktop/bb931354%28v=vs.85%29.aspx)
    // gives a range from 8-512 bits.  bouncycastle supports 40-2048 bits.
    RC4(20, 0x28, 0x200),
    AES_128(32, 0x80, 0x80),
    AES_192(32, 0xC0, 0xC0),
    AES_256(32, 0x100, 0x100);

    fun validateKeySize(keySize: Int): Boolean {
        return keySize in keySizeMin..keySizeMax
    }
}

enum class HashAlgorithm {
    External,
    SHA1
}

sealed class DatabaseEncryptionStructure(val bytes: UByteBuffer, val jet: Jet) {
    open val passwordLength = if (jet.isVersion4) 40 else 20
    val dbPasswordHash: UByteArray by lazy { UByteArray(passwordLength) }
    val passwordSalt = UByteArray(8)
    val saltOffset = 114
    val encodingKey = UByteArray(4)
    val isBlankEncodingKey get() = encodingKey.none { it != 0.toUByte() }
    val isBlankPasswordHash get() = dbPasswordHash.none { it != 0.toUByte() }
    private val dbPasswordMask = UByteArray(4)
    val dbPasswordUnmasked: UByteArray by lazy { UByteArray(passwordLength) }

    abstract fun parse()

    open fun parseBuffer() {
        bytes.position = DatabasePageStructure.offsetCreationDate
        bytes.get(passwordSalt)
        passwordSalt.copyInto(encodingKey, 0, 0, encodingKey.size)

        bytes.position = passwordHashOffset
        bytes.get(dbPasswordHash)
        if (!isBlankPasswordHash) {
            bytes.position = saltOffset
            val wrk = UByteBuffer(dbPasswordMask)
            wrk.int = bytes.double.toInt()
            for (i in dbPasswordHash.indices) {
                dbPasswordUnmasked[i] =
                    dbPasswordHash[i] xor dbPasswordMask[i % dbPasswordMask.size]
            }
        }
        parse()
    }

    companion object {
        const val passwordHashOffset = DatabasePageStructure.rc4Offset + 42
    }

    class NoEncryption(dbPage: UByteBuffer, jet: Jet) : DatabaseEncryptionStructure(dbPage, jet) {
        override fun parseBuffer() {
        }

        override fun parse() {
        }
    }

    abstract class JetBaseEncryption(dbPage: UByteBuffer, jet: Jet, val jetHeader: UByteArray) :
        DatabaseEncryptionStructure(dbPage, jet) {
        var newEncryption = false
            private set
        var useSHA1 = false
            private set

        override fun parse() {
            bytes.position = offsetEncryptionFlags
            val encryptionFlags = bytes.byte
            newEncryption = (encryptionFlags.toInt() and 0x6) > 0
            useSHA1 = (encryptionFlags.toInt() and useSHA1Flag) > 0
        }

        fun hashSalt(salt: UByteArray, hashData: UByteArray) {
            val bb = UByteBuffer(salt)
            var hash = bb.int
            for (pos in hashData.indices) {
                var tmp: Int = hashData[pos].toInt() and 0xFF
                tmp = tmp shl pos % 0x18
                hash = hash xor tmp
            }
            bb.rewind()
            bb.int = hash
        }

        fun createPasswordDigest(password: String): UByteArray {
            val passwordDigestLength = 0x10
            val digest: Digest = if (useSHA1) SHA1Digest() else MD5Digest()
            val passwordBytes = ByteArray(passwordLength) { 0 }
            if (password.isNotEmpty()) {
                val pBytes = jet.charset.encode(password.uppercase())
                pBytes.copyInto(passwordBytes, 0, 0, min(passwordLength, pBytes.size))
            }

            // Get digest value
            return digest.hash(
                passwordBytes.toUByteArray(),
                UByteArray(0),
                passwordDigestLength
            )
        }

        companion object {
            private const val offsetEncryptionFlags = 0x298
            private const val useSHA1Flag = 0x20
        }
    }

    class MsiasmEncryption(dbPage: UByteBuffer, jet: Jet, jetHeader: UByteArray) :
        JetBaseEncryption(dbPage, jet, jetHeader) {
        private var oldMsisamPasswordHash = UByteArray(passwordLength * 2)
        val passwordTestBytes = UByteArray(4)
        private val passwordCheckStart = 745
        private val oldPasswordMask = UByteArray(4)

        override fun parse() {
            super.parse()
            if (newEncryption) {
                val checkOffset = bytes.getElementAt(saltOffset).toInt()
                bytes.position = passwordCheckStart + checkOffset
                bytes.get(passwordTestBytes)
            } else {
                bytes.position = passwordHashOffset
                bytes.get(oldMsisamPasswordHash)
                bytes.position = DatabasePageStructure.offsetCreationDate
                val dbl = bytes.double
                val tempBuf = UByteBuffer(oldPasswordMask)
                tempBuf.int = dbl.toInt()
                bytes.position = DatabasePageStructure.offsetCreationDate
                val cryptCheckOffset = bytes.byte.toInt()
                bytes.position = cryptCheckStart + cryptCheckOffset
                bytes.get(passwordTestBytes)
                val key = getOldDecryptionKey()
                key.copyInto(encodingKey)
            }
        }

        private fun getOldDecryptionKey(): UByteArray {

            // apply additional mask to header data
            val fullHashData = oldMsisamPasswordHash.copyOf()
            if (jet.isVersion4) {
                for (i in dbPasswordHash.indices) {
                    fullHashData[i] = fullHashData[i] xor oldPasswordMask[i % oldPasswordMask.size]
                }
                val trailingOffset = fullHashData.size - trailingPasswordLength
                for (i in 0 until trailingPasswordLength) {
                    fullHashData[trailingOffset + i] =
                        fullHashData[trailingOffset + i] xor oldPasswordMask[i % oldPasswordMask.size]
                }
            }
            val hashData = UByteArray(passwordLength)
            for (pos in 0 until passwordLength) {
                hashData[pos] = fullHashData[pos * 2]
            }
            hashSalt(encodingKey, hashData)
            hashSalt(encodingKey, jetHeader)
            return encodingKey
        }

        companion object {
            private const val cryptCheckStart = 0x2e9
            private const val trailingPasswordLength = 20
        }
    }

    class JetEncryption(dbPage: UByteBuffer, jet: Jet, jetHeader: UByteArray) :
        JetBaseEncryption(dbPage, jet, jetHeader) {
        override val passwordLength = if (jet.isVersion4) 40 else 20

        override fun parse() {
            super.parse()
            bytes.position = 62
            bytes.get(encodingKey)
        }
    }

    class OfficeEncryption(bytes: UByteBuffer, jet: Jet) : DatabaseEncryptionStructure(bytes, jet) {

        /**
         * Note on Providers required. For RC4Crypto, if it is unsupported, try NonStandard
         */
        enum class EncryptionAlgorithm {
            None, Agile, OfficeBinaryDocRC4, ECMAStandard, RC4CryptoAPI, NonStandard
        }

        var majorVersion = 0
            private set
        var minorVersion = 0
            private set
        var provider = EncryptionAlgorithm.None
            private set
        var afterFlagsPosition: Int = 0
            private set

        private var flags = 0
        private var sizeExtra = 0
        private var algorithmId = 0
        private var algorithmIdHash = 0

        var keySizeBits = 0
            private set
        val keySizeBytes get() = keySizeBits / 8

        private var providerType = 0
        lateinit var algorithm: CryptoAlgorithm
            private set
        lateinit var algorithmHash: HashAlgorithm
            private set
        lateinit var cspName: String
            private set
        val salt = UByteArray(16)
        val verifier = UByteArray(16)
        var verifierHashSize: Int = 0
        var verifierHash = UByteArray(0)
        lateinit var agileXmlBytes: ByteArray
        lateinit var agileXml: String

        override fun parse() {
            bytes.position = 62
            bytes.get(encodingKey)
            bytes.position = offsetStructure
            val metadataLength = bytes.short.toInt()
            val providerMetadata = bytes.slice(metadataLength)
            // read encoding provider version
            // uint (2.1.4 Version)
            majorVersion = providerMetadata.short.toInt()
            minorVersion = providerMetadata.short.toInt()
            if (majorVersion == 4 && minorVersion == 4) {
                // OC: 2.3.4.10 - Agile Encryption: 4,4
                provider = EncryptionAlgorithm.Agile
                parseAgile(providerMetadata)
            } else if (majorVersion == 1 && minorVersion == 1) {
                // OC: 2.3.6.1 - RC4 Encryption: 1,1
                provider = EncryptionAlgorithm.OfficeBinaryDocRC4
            } else if ((majorVersion == 3 || majorVersion == 4) && minorVersion == 3) {
                // OC: 2.3.4.6 - Extensible Encryption: (3,4),3
                // since this utilizes arbitrary external providers, we can't really
                // do anything with it
                throw IllegalStateException(
                    "Extensible encryption provider is not supported for major:$majorVersion, minor:$minorVersion"
                )
            } else if ((majorVersion == 2 || majorVersion == 3 || majorVersion == 4) && minorVersion == 2) {
                // read flags (copy of the flags in EncryptionHeader)
                val flags = providerMetadata.int
                afterFlagsPosition = providerMetadata.position
                if (flags and maskCryptoApi > 0) {
                    provider = if (flags and maskFaes > 0) {
                        // OC: 2.3.4.5 - Standard Encryption: (3,4),2
                        EncryptionAlgorithm.ECMAStandard
                    } else {
                        // val initPos = providerMetadata.position
                        // OC: 2.3.5.1 - RC4 CryptoAPI Encryption: (2,3,4),2
                        val p = EncryptionAlgorithm.RC4CryptoAPI
                        parseRC4Structure(providerMetadata)
                        p
                    }
                }
            }
        }

        /**
         * Metadata in this version is in XML encoded with UTF-8, will be parsed by AgileCodec
         */
        private fun parseAgile(providerMetadata: UByteBuffer) {
            val reserved = providerMetadata.int
            if (reserved != agileReserved)
                throw IllegalStateException("Unexpected Agile Encryption reserved value: $reserved, should be 64")
            agileXmlBytes = providerMetadata.getBytes().toByteArray()
            agileXml = Jet.utf8Charset.decode(agileXmlBytes)
        }

        private fun parseRC4Structure(providerMetadata: UByteBuffer) {
            val headerLength = providerMetadata.int
            val headerPos = providerMetadata.position
            flags = providerMetadata.int
            sizeExtra = providerMetadata.int
            algorithmId = providerMetadata.int
            algorithmIdHash = providerMetadata.int
            val keySizeTemp = providerMetadata.int
            providerType = providerMetadata.int

            algorithm = when (algorithmId) {
                0 -> if (flags and 0x10 > 0)
                    CryptoAlgorithm.External
                else if (flags and 4 > 0) {
                    if (flags and 0x20 > 0)
                        CryptoAlgorithm.AES_128
                    else
                        CryptoAlgorithm.RC4
                } else
                    throw algorithmError()
                26625 -> CryptoAlgorithm.RC4
                26126 -> CryptoAlgorithm.AES_128
                26127 -> CryptoAlgorithm.AES_192
                26128 -> CryptoAlgorithm.AES_256
                else -> {
                    throw algorithmError()
                }
            }

            algorithmHash = when (algorithmIdHash) {
                0 -> {
                    if (flags and 0x10 > 0)
                        HashAlgorithm.External
                    else
                        HashAlgorithm.SHA1
                }
                32772 -> HashAlgorithm.SHA1
                else -> throw IllegalStateException("Unsupported Hash ID: $algorithmIdHash, flags: $flags")
            }
            providerMetadata.int
            providerMetadata.int

            var l = min(
                headerLength - (providerMetadata.position - headerPos),
                providerMetadata.remaining
            )
            if (l % 2 > 0) l -= 1
            cspName = jet.charset.decode(providerMetadata.getBytes(l).toByteArray()).trimEnd(0.toChar())

            keySizeBits = if (keySizeTemp > 0)
                keySizeTemp
            else if (algorithm == CryptoAlgorithm.RC4) {
                if (cspName.isEmpty() || cspName.contains(" base", true))
                    40
                else
                    80
            } else {
                algorithm.keySizeMin
            }

            if (!algorithm.validateKeySize(keySizeBits))
                throw IllegalStateException("Key size(bits): $keySizeBits must be between ${algorithm.keySizeMin} and ${algorithm.keySizeMax}, algorithm: $algorithm")
            if (keySizeBits % 8 != 0)
                throw IllegalStateException("Key size(bits): $keySizeBits must be multiple of 8")

            val saltSize = providerMetadata.int
            if (saltSize != 16)
                throw IllegalStateException("Salt size: $saltSize, only 16 supported")

            providerMetadata.apply {
                getBytes(salt)
                getBytes(verifier)
                verifierHashSize = int
                verifierHash = getBytes(algorithm.verifierHashLength)
            }
        }

        private fun algorithmError(): IllegalStateException {
            return IllegalStateException(
                "Unsupported encryption algorithmID: $algorithmId, flags: ${
                    flags.toString(
                        16
                    )
                }"
            )
        }

        companion object {
            private const val offsetStructure = 0x299

            private const val maskCryptoApi = 0x04
            //private const val maskFdocProps = 0x08
            //private const val maskFExternal = 0x10
            private const val maskFaes = 0x20
            private const val agileReserved = 64
        }
    }
}
