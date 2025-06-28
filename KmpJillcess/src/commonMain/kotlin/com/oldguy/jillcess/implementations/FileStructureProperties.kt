package com.oldguy.jillcess.implementations

import com.oldguy.common.io.UByteBuffer
import com.oldguy.common.io.charsets.Charset
import com.oldguy.jillcess.toHexString

class LvProperties(
    val isVersion4: Boolean,
    val charset: Charset
) : PagePortion<LvProperties>() {
    class Property(
        val type: ValueType,
        charset: Charset,
        value: UByteBuffer
    ) {
        private var boolValue = false
        private var intValue = 0
        private var bytesValue = UByteArray(0)
        private var stringValue = ""
        private var doubleValue: Double = 0.toDouble()

        init {
            when (type) {
                ValueType.BooleanType -> {
                    if (value.remaining == 4)
                        boolValue = value.int > 0
                    else if (value.remaining == 1)
                        boolValue = value.byte > 0u
                    else
                        throw FileStructureException("Boolean LvProperty should be 1 byte, content: $value")
                }
                ValueType.ByteType -> {
                    if (value.remaining != 1)
                        throw FileStructureException(
                            "Byte LvProperty should be 1 bytes, remaining: ${value.remaining}"
                        )
                    intValue = value.byte.toInt()
                }
                ValueType.ShortType -> {
                    if (value.remaining == 2)
                        intValue = value.short.toInt()
                    else if (value.remaining == 4)
                        intValue = value.int
                    else
                        throw FileStructureException(
                            "Short LvProperty should be 2 or 4 bytes, remaining: ${value.remaining}}"
                        )
                }
                ValueType.IntegerType -> {
                    if (value.remaining != 4) throw FileStructureException(
                        "Int LvProperty should be 4 byte, remaining: ${value.remaining}"
                    )
                    intValue = value.int
                }
                ValueType.SmallBinary -> {
                    bytesValue = value.contentBytes
                }
                ValueType.SmallText -> {
                    stringValue = charset.decode(value.contentBytes.toByteArray())
                }
                ValueType.OLE -> {
                    bytesValue = value.contentBytes
                }
                ValueType.FloatType -> doubleValue = value.float.toDouble()
                ValueType.DoubleType -> doubleValue = value.double
                ValueType.Memo -> {
                    val stringBytes = value.getBytes(value.remaining)
                    stringValue = CompressedString(charset).decode(stringBytes.toByteArray())
                    /*
                    val memo = MemoField().parse(value)
                    if (memo.inline) {
                        val stringBytes = ByteArray(value.remaining)
                        value.get(stringBytes)
                        stringValue = CompressedString(charset).decode(stringBytes)
                    } else
                        throw FileStructureException("LvProperty type Memo not inline is unsupported: single: ${memo.singleLval}, multiple: ${memo.multipleLval}")
                    */
                }
                else -> throw IllegalArgumentException("Unexpected LvProp type: $type")
            }
        }

        /**
         * Convenience properties, can also use type directly
         */
        val isBoolean = type == ValueType.BooleanType
        val isByte = type == ValueType.ByteType
        val isShort = type == ValueType.ShortType
        val isInt = type == ValueType.IntegerType
        val isBinary = type == ValueType.SmallBinary || type == ValueType.OLE
        val isString = type == ValueType.SmallText || type == ValueType.Memo
        val isFloat = type == ValueType.FloatType
        val isDouble = type == ValueType.DoubleType

        val isTrue get() = if (isBoolean) boolValue else throw IllegalStateException("Property is not Boolean, check type")
        val byte get() = if (isByte) intValue.toByte() else throw IllegalStateException("Property is not Byte, check type")
        val short get() = if (isShort) intValue.toShort() else throw IllegalStateException("Property is not Short, check type")
        val int get() = if (isInt) intValue else throw IllegalStateException("Property is not Int, check type")
        val string get() = if (isString) stringValue else throw IllegalStateException("Property is not String, check type")
        val bytes get() = if (isBinary) bytesValue else throw IllegalStateException("Property is not Binary, check type")
        val float get() = if (isFloat) doubleValue.toFloat() else throw IllegalStateException("Property is not Float, check type")
        val double get() = if (isFloat) doubleValue else throw IllegalStateException("Property is not Double, check type")
    }

    val header = UByteArray(4)
    val propertyNames = emptyList<String>().toMutableList()
    val tableProperties = emptyMap<String, Property>().toMutableMap()
    val columnProperties = emptyMap<String, MutableMap<String, Property>>().toMutableMap()

    /**
     * The ByteBuffer must be positioned at the start of the LvProperties record content, and have its limit set to
     * to position() + the record length of the LvProperties content
     *
     * LvProp column can contain just the LvProp structure, or it can be wrapped in a MemoField.  parse looks for
     * LvProp signature (4 bytes) at start, and if signature not found, parses as a MemoField and sees if inline Memo
     * Content starts with the correct signature
     */
    override fun parse(bytes: UByteBuffer): LvProperties {
        bytes.get(header)
        if (!checkSignature(header, isVersion4)) {
            bytes.position = (bytes.position - header.size)
            val memo = MemoField().parse(bytes)
            if (!memo.inline)
                throw FileStructureException("LvProp header signature missing and not an inline MemoField: ${header.toHexString()}")
            if (bytes.remaining != memo.length)
                throw FileStructureException("LvProp header inline MemoField length ${memo.length}, bytes remaining: ${bytes.remaining}")
            bytes.get(header)
            if (!checkSignature(header, isVersion4))
                throw FileStructureException("LvProp header signature missing in an inline MemoField: ${header.toHexString()}")
        }
        while (bytes.remaining > 6) {
            val blockLength = bytes.int
            val type = bytes.short
            var blockRemaining = blockLength - 6
            when (type.toInt()) {
                0 -> {
                    while (blockRemaining > 0) {
                        bytes.int
                        val nameLength = bytes.short.toInt()
                        if (nameLength != 0)
                            throw FileStructureException("Table property name expected to be zero length")
                        blockRemaining -= 6
                        while (blockRemaining > 0) {
                            val recLength = bytes.short
                            if (recLength > blockRemaining)
                                throw FileStructureException("Invalid table LvProp, remaining bytes: $blockRemaining, required: $recLength")
                            bytes.byte // unknown byte
                            val valueType = bytes.byte
                            val nameIndex = bytes.short.toInt()
                            val valueLength = bytes.short
                            val valueContent = bytes.getBytes(valueLength.toInt())
                            val propName = propertyNames[nameIndex]
                            tableProperties[propName] = Property(
                                ValueType.fromByte(valueType),
                                charset,
                                UByteBuffer(valueContent, bytes.order)
                            )
                            blockRemaining -= recLength
                        }
                    }
                }
                1 -> {
                    while (blockRemaining > 0) {
                        val headerLength = bytes.int
                        val nameLength = bytes.short.toInt()
                        if (nameLength == 0)
                            throw FileStructureException("Table Column property name expected to be non-zero length")
                        val columnName = charset.decode(bytes.getBytes(nameLength).toByteArray())
                        val columnPropertyMap = emptyMap<String, Property>().toMutableMap()
                        columnProperties[columnName] = columnPropertyMap
                        blockRemaining -= headerLength
                        while (blockRemaining > 0) {
                            val recLength = bytes.short
                            if (recLength > blockRemaining)
                                throw FileStructureException("Invalid column LvProp, remaining bytes: $blockRemaining, required: $recLength")
                            bytes.byte // unknown byte
                            val valueType = bytes.byte
                            val nameIndex = bytes.short.toInt()
                            val valueLength = bytes.short
                            val valueContent = bytes.getBytes(valueLength.toInt())
                            val propName = propertyNames[nameIndex]
                            columnPropertyMap[propName] = Property(
                                ValueType.fromByte(valueType),
                                charset,
                                UByteBuffer(valueContent, bytes.order)
                            )
                            blockRemaining -= recLength
                        }
                    }
                }
                0x80 -> {
                    while (blockRemaining > 0) {
                        val nameLength = bytes.short.toInt()
                        propertyNames.add(charset.decode(bytes.getBytes(nameLength).toByteArray()))
                        blockRemaining -= nameLength + 2
                    }
                }
                else ->
                    throw FileStructureException("Unexpected LvProp value type: $type")
            }
        }
        return this
    }

    override fun setBytes(bytes: UByteBuffer) {
    }

    companion object {
        val jet4Signature = ubyteArrayOf(77u, 82u, 50u, 0u)
        val jet3Signature = ubyteArrayOf(75u, 75u, 68u, 0u)

        private fun checkSignature(headerBytes: UByteArray, isVersion4: Boolean): Boolean {
            if (isVersion4 && headerBytes.contentEquals(jet4Signature))
                return true
            return !isVersion4 && headerBytes.contentEquals(jet3Signature)
        }
    }
}
