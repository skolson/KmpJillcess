package com.oldguy.jillcess.cryptography

import com.oldguy.common.io.Buffer
import com.oldguy.common.io.Charset
import com.oldguy.common.io.Charsets
import com.oldguy.common.io.UByteBuffer
import com.oldguy.crypto.*
import com.oldguy.jillcess.implementations.CryptoAlgorithm
import com.oldguy.jillcess.implementations.DatabaseEncryptionStructure
import com.oldguy.jillcess.implementations.DatabaseEncryptionStructure.OfficeEncryption.EncryptionAlgorithm
import kotlin.math.min

/**
 * Since page numbers are used to manipulate keys for each page, these are cached for reuse.
 */
class KeyCache {
    private val cache = LinkedHashMap<Int, Pair<UByteArray, UByteArray>>(16, 0.75f)

    /**
     * Returns a copy of the given key with the bytes of the given pageNumber
     * applied at the given offset using XOR.
     */
    fun put(pageNumber: Int, keys: Pair<UByteArray, UByteArray>, offset: Int = 0) {
        if (keys.first.size < 4)
            throw IllegalArgumentException("Key must be at least 4 bytes")
        cache[pageNumber] = Pair(applyPage(keys.first, pageNumber, offset), UByteArray(0))
    }

    fun contains(pageNumber: Int): Boolean {
        return cache.contains(pageNumber)
    }

    operator fun get(pageNumber: Int): Pair<UByteArray, UByteArray> {
        return cache[pageNumber] ?: throw IllegalArgumentException("No key for page: $pageNumber")
    }

    companion object {
        fun applyPage(keyBytes: UByteArray, pageNumber: Int, offset: Int = 0): UByteArray {
            UByteBuffer(keyBytes.copyOf()).apply {
                position = offset
                int = pageNumber
                position = offset
                for (i in offset until offset + 4) {
                    byte = get(i) xor keyBytes[i]
                }
                return contentBytes
            }
        }
    }
}

/**
 * Implementations of this interface use the Encryption metadata parsed from the database page 0 and
 * encryption providers.
 */
abstract class Codec {
    val keyCache = KeyCache()
    /**
     * Must set the [cipher] variable with a configured Cipher instance for use on pages.
     */
    lateinit var cipher: Cipher

    /**
     * Returns `true` if this handler can encode partial pages,
     * `false` otherwise.  If this method returns `false`, the
     * [.encodePage] method will never be called with a non-zero
     * pageOffset.
     */
    abstract val canEncodePartialPage: Boolean

    /**
     * Decodes the given page buffer.
     *
     * @param inPage the page to be decoded
     * @param pageNumber the page number of the given page
     * @return the decoded page.  if [.canDecodeInline] is `true`, this will be the same buffer as inPage.
     *
     */
    abstract fun decodePage(inPage: UByteBuffer, pageNumber: Int): UByteBuffer

    /**
     * Encodes the given page buffer into a new page buffer and returns it.  The
     * returned page buffer will be used immediately and discarded so that it
     * may be re-used for subsequent page encodings.
     *
     * @param page the page to be encoded, should not be modified
     * @param pageNumber the page number of the given page
     * @param pageOffset offset within the page at which to start writing the
     * page data
     *
     * @return the properly encoded page buffer for the given page buffer
     */
    abstract fun encodePage(
        page: UByteBuffer,
        pageNumber: Int,
        pageOffset: Int = 0
    ): UByteBuffer

    fun iterateHash(digest: Digest, baseHash: UByteArray, iterations: Int): UByteArray {
        if (iterations == 0) {
            return baseHash
        }
        var iterHash = baseHash
        val b = UByteBuffer(4, Buffer.ByteOrder.LittleEndian)
        for (i in 0 until iterations) {
            b.rewind()
            b.int = i
            iterHash = digest.hash(b.contentBytes, iterHash)
        }
        return iterHash
    }

    companion object {
        const val passwordErrorText = "Incorrect password provided"
    }
}

/**
 * CodecHandler implementation which does nothing, useful for databases with
 * no extra encoding.
 */
class NoopCodec : Codec() {
    override val canEncodePartialPage = true

    override fun decodePage(
        inPage: UByteBuffer,
        pageNumber: Int
    ): UByteBuffer {
        return inPage
    }

    override fun encodePage(
        page: UByteBuffer,
        pageNumber: Int,
        pageOffset: Int
    ): UByteBuffer {
        return page
    }
}

/**
 * This scheme uses either a key from metadata, or a salted hash that has to be verified to
 * determine the key to use.  Uses the RC4 encryption engine (not very secure)
 */
class MsiasmCodec constructor(
    password: String,
    private val encryptionMeta: DatabaseEncryptionStructure.MsiasmEncryption
) : Codec() {
    private val passwordDigestLength: Int
    private val maxEncodedPage = 0xE
    private val _baseHash: UByteArray
    private val saltLength = 4

    init {
        cipher = Cipher.build { engine { rc4() } }
        _baseHash = if (encryptionMeta.newEncryption || password.isNotEmpty()) {
            val salt = encryptionMeta.passwordSalt

            // create decryption key parts
            val pwdDigest = encryptionMeta.createPasswordDigest(password)
            val baseSalt = salt.copyOf(saltLength)

            // check password hash using decryption of a known sequence
            verifyPassword(pwdDigest + salt, baseSalt)

            // create final key
            passwordDigestLength = 16
            pwdDigest + baseSalt
        } else {
            passwordDigestLength = 0
            encryptionMeta.encodingKey
        }
        cipher.key = _baseHash
    }

    /**
     *
     */
    private fun verifyPassword(
        testEncodingKey: UByteArray,
        testBytes: UByteArray
    ) {
        cipher.key = testEncodingKey
        val encrypted4BytesCheck = encryptionMeta.passwordTestBytes
        // if all zeros, then no password to check
        if (encrypted4BytesCheck.all { it.toUInt() == 0u }) return
        val result = cipher.processOne(false, UByteBuffer(encrypted4BytesCheck))
        if (!result.getBytes().contentEquals(testBytes))
            throw IllegalArgumentException(passwordErrorText)
    }

    override val canEncodePartialPage = true

    override fun decodePage(inPage: UByteBuffer, pageNumber: Int): UByteBuffer {
        if (pageNumber > maxEncodedPage) return inPage
        if (!keyCache.contains(pageNumber)) {
            keyCache.put(pageNumber, Pair(_baseHash, UByteArray(0)), passwordDigestLength)
        }
        cipher.key = keyCache[pageNumber].first
        return cipher.processOne(false, inPage)
    }

    override fun encodePage(page: UByteBuffer, pageNumber: Int, pageOffset: Int): UByteBuffer {
        throw UnsupportedOperationException("No write support")
    }
}

class JetCodec constructor(
    private val encryptionMeta: DatabaseEncryptionStructure.JetEncryption,
    password: String
) : Codec() {
    override val canEncodePartialPage = true

    init {
        cipher = Cipher.build { engine { rc4() } }
        // Jet MDB files can be password protected without being encoded. Honor it
        if (encryptionMeta.isBlankEncodingKey && !encryptionMeta.isBlankPasswordHash) {
            val filePwd = encryptionMeta.jet.charset.decode(encryptionMeta.dbPasswordUnmasked.toByteArray())
                .trimEnd(0x00.toChar())
            if (filePwd != password)
                throw IllegalArgumentException(passwordErrorText)
        }
    }

    override fun decodePage(inPage: UByteBuffer, pageNumber: Int): UByteBuffer {
        if (encryptionMeta.isBlankEncodingKey) return inPage
        if (!keyCache.contains(pageNumber)) {
            keyCache.put(pageNumber, Pair(encryptionMeta.encodingKey, UByteArray(0)))
        }
        cipher.key = keyCache[pageNumber].first
        return cipher.processOne(false, inPage)
    }

    override fun encodePage(page: UByteBuffer, pageNumber: Int, pageOffset: Int): UByteBuffer {
        throw UnsupportedOperationException("No write support")
    }
}

class OfficeRC4Codec constructor(
    password: String,
    val officeEncryption: DatabaseEncryptionStructure.OfficeEncryption
): Codec()
{
    override val canEncodePartialPage = true
    private val passwordBytes = Charset(Charsets.Utf16le).encode(password)
    private val baseHash: UByteArray

    enum class Phase {
        Verify, Ready
    }

    private val noEncryption = officeEncryption.isBlankEncodingKey
            || officeEncryption.provider == EncryptionAlgorithm.None

    private val digest: Digest
        get() = when (officeEncryption.algorithm) {
            CryptoAlgorithm.External -> TODO()
            CryptoAlgorithm.RC4 -> SHA1Digest()
            CryptoAlgorithm.AES_128 -> TODO()
            CryptoAlgorithm.AES_192 -> TODO()
            CryptoAlgorithm.AES_256 -> TODO()
        }

    private var phase = Phase.Verify
        private set

    init {
        baseHash = digest.hash(officeEncryption.salt.toUByteArray(), passwordBytes.toUByteArray())

        /*
        Decrypt verifier bytes and verifier hash bytes, calculate hash on the verifier bytes and
        compare for equality.
         */
        if (!noEncryption) {
            cipher = Cipher.build {
                engine { rc4() }
                key {
                    key = computeKey(UByteArray(4), 0)
                }
            }
            val decrypted: UByteBuffer
            UByteBuffer(officeEncryption.verifier.size + officeEncryption.verifierHash.size).apply {
                put(officeEncryption.verifier)
                put(officeEncryption.verifierHash)
                flip()
                decrypted = cipher.processOne(false, this)
            }
            val decryptedVerifier = decrypted.getBytes(officeEncryption.verifier.size)
            val decryptedHash = decrypted.getBytes()
            val testHash = digest.hash(
                decryptedVerifier,
                UByteArray(0),
                officeEncryption.verifierHashSize
            )
            if (!decryptedHash.contentEquals(testHash))
                throw IllegalArgumentException(passwordErrorText)
        }
        digest.reset()
        phase = Phase.Ready
    }

    private fun computeKey(bytes: UByteArray, pageNumber: Int): UByteArray {
        val pageBytes = if (pageNumber > 0)
            KeyCache.applyPage(bytes, pageNumber)
        else
            bytes
        val key = digest.hash(baseHash, pageBytes.toUByteArray(), officeEncryption.keySizeBytes)
        return if (officeEncryption.keySizeBits == 40) {
            key.copyOf(16) // 128 bit key
        } else
            key
    }

    override fun decodePage(
        inPage: UByteBuffer,
        pageNumber: Int
    ): UByteBuffer {
        if (noEncryption) return inPage
        cipher.key = computeKey(officeEncryption.encodingKey, pageNumber)
        return cipher.processOne(false, inPage)
    }

    override fun encodePage(
        page: UByteBuffer,
        pageNumber: Int,
        pageOffset: Int
    ): UByteBuffer {
        page.position = pageOffset
        return cipher.processOne(true, page)
    }
}

/**
 * This codec is configured based on metadata from a small XML document in part of the DatabasePage.
 * Password verification metadata is available for checking the validity of a supplied password
 * for use in decrypting any of the data pages. Once the password is verified, key metadata is
 * used to determine the cipher configuration for decrypting DataPage buffers.
 *
 * majorVersion=4, minorVersion=4 of the Office flavors of Access encode encryption metadata in
 * a small XML document
 */
class AgileCodec(
    password: String,
    val officeEncryption: DatabaseEncryptionStructure.OfficeEncryption
) : Codec() {
    private val agile = OfficeAgile()
    private var keyValue = UByteArray(0)
    private val encodingKey = officeEncryption.encodingKey
    private val ctEncryption = agile.parseXml(officeEncryption.agileXml)
    private val pageKeySaltValue = ctEncryption.keyData.saltValue.toUByteArray()
    private var saltValue = UByteArray(0)
    private lateinit var digest: ExtendedDigest
    private val keyEncryptionMetadata = ctEncryption.validate()
    private val pwdEncryptedKey = keyEncryptionMetadata.encryptedKeyValue

    /**
     * Parse the XML data. Use the password encryption info to decrypt the key value. Then verify
     * the passwordBytes supplied against the verifierHash in the password encryption info.  If the
     * password hash calculated doesn't match the verifierHash in the password encryption info, then
     * an exception is thrown.
     * If the password is correct, then use the key metadata to configure the cipher for decoding
     * data pages.
     */
    init {
        passwordPhaseConfigure()
        val passwordBytes = Charset(Charsets.Utf16le).encode(password).toUByteArray()
        cipher.key = buildHash(passwordBytes, keyBlock)
        keyValue = cipher.processOne(false, UByteBuffer(pwdEncryptedKey)).getBytes()
        verifyPassword(passwordBytes)
        configureDataPages()
    }

    private fun passwordPhaseConfigure() {
        createDigest(keyEncryptionMetadata.hashAlgorithm)
        saltValue = keyEncryptionMetadata.saltValue
        var cipherSpecification = keyEncryptionMetadata.cipherAlgorithm
        if (keyEncryptionMetadata.cipherChaining.isNotEmpty()) {
            val mode =
                keyEncryptionMetadata.cipherChaining.uppercase().removePrefix("CHAININGMODE")
            if (mode.length < 3)
                throw IllegalStateException("Invalid Agile mode id: ${keyEncryptionMetadata.cipherChaining}")
            cipherSpecification = "$cipherSpecification/$mode"
        }
        cipher = Cipher.build {
            parse(cipherSpecification)
            key {
                iv = saltValue
            }
        }
    }

    /**
     * Configures the current cipher for decrypting data pages
     */
    private fun configureDataPages() {
        ctEncryption.keyData.also {
            saltValue = it.saltValue.toUByteArray()
            createDigest(it.hashAlgorithm)
            var cipherSpecification = it.cipherAlgorithm
            if (it.cipherChaining.isNotEmpty()) {
                val mode = it.cipherChaining.uppercase().removePrefix("CHAININGMODE")
                if (mode.length < 3)
                    throw IllegalStateException("Invalid Agile mode id: ${it.cipherChaining}")
                cipherSpecification = "$cipherSpecification/$mode"
            }
            cipher = Cipher.build {
                parse(cipherSpecification)
                key {
                    key = keyValue
                }
            }
        }
    }


    private fun createDigest(hashId: String) {
        digest = when (hashId) {
            "SHA1" -> SHA1Digest()
            "SHA256" -> SHA256Digest()
            "SHA384" -> SHA384Digest()
            "SHA512" -> SHA512Digest()
            "MD5" -> MD5Digest()
            "MD4" -> MD4Digest()
            "MD2" -> MD2Digest()
            "RIPEMD128" -> RIPEMD128Digest()
            "RIPEMD160" -> RIPEMD160Digest()
            "WHIRLPOOL" -> WhirlpoolDigest()
            else -> throw IllegalStateException("Unrecognized Agile hashAlgorithm: $hashId")
        }
    }

    private fun buildHash(
        passwordBytes: UByteArray,
        hashBlock: UByteArray
    ): UByteArray {
        val baseHash = digest.hash(keyEncryptionMetadata.saltValue, passwordBytes)
        val iterHash = iterateHash(digest, baseHash, keyEncryptionMetadata.spinCount)
        val finalHash = digest.hash(iterHash, hashBlock)
        val hash = UByteArray(keyEncryptionMetadata.keyBytesSize) { 0x36u }
        finalHash.copyInto(hash, 0, 0, min(hash.size, finalHash.size))
        return hash
    }

    /**
     * Verify the submitted password. The saltValue from the metadata is the Initialization Vector (IV)
     * for the key.
     */
    private fun verifyPassword(passwordBytes: UByteArray) {
        cipher.key = buildHash(passwordBytes, verifierInputBlock)
        val verifier = cipher.processOne(false, UByteBuffer(keyEncryptionMetadata.encryptedVerifierHashInput))
        cipher.key = buildHash(passwordBytes, verifierValueBlock)
        val verifierHash = cipher.processOne(false, UByteBuffer(keyEncryptionMetadata.encryptedVerifierHashValue))
        var matchHash = digest.hash(verifier.getBytes())
        keyEncryptionMetadata.let {
            if (matchHash.size % it.blockSize > 0) {
                val temp =
                    UByteArray(((matchHash.size + it.blockSize - 1) / it.blockSize) * it.blockSize)
                matchHash.copyInto(temp)
                matchHash = temp
            }
        }
        if (!matchHash.contentEquals(verifierHash.getBytes()))
            throw IllegalArgumentException(passwordErrorText)
    }

    override val canEncodePartialPage = false

    override fun decodePage(inPage: UByteBuffer, pageNumber: Int): UByteBuffer {
        val blockBytes = KeyCache.applyPage(encodingKey, pageNumber).toUByteArray()
        cipher.iv = if (blockBytes.isNotEmpty()) {
            digest.hash(
                pageKeySaltValue,
                blockBytes,
                cipher.engine.blockSize
            )
        } else
            pageKeySaltValue
        return cipher.processOne(false, inPage)
    }

    override fun encodePage(page: UByteBuffer, pageNumber: Int, pageOffset: Int): UByteBuffer {
        throw UnsupportedOperationException("No write support")
    }

    companion object {
        private val keyBlock = ubyteArrayOf(
            0x14u, 0x6eu, 0x0bu, 0xe7u, 0xabu, 0xacu, 0xd0u, 0xd6u
        )
        private val verifierInputBlock = ubyteArrayOf(
            0xfeu, 0xa7u, 0xd2u, 0x76u, 0x3bu, 0x4bu, 0x9eu, 0x79u
        )
        private val verifierValueBlock = ubyteArrayOf(
            0xd7u, 0xaau, 0x0fu, 0x6du, 0x30u, 0x61u, 0x34u, 0x4eu
        )
    }
}
