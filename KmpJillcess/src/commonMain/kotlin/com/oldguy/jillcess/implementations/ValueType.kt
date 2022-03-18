package com.oldguy.jillcess.implementations

/**
 * Column metadata has this type, and individual values in a RecordStructure have this type
 */
enum class ValueType(val typeCode: Byte) {
    BooleanType(1),
    ByteType(2),
    ShortType(3),
    IntegerType(4),
    Currency(5),
    FloatType(6),
    DoubleType(7),
    DateTimeType(8),
    SmallBinary(9),
    SmallText(10),
    OLE(11),
    Memo(12),
    Unknown13(13),
    GUID(15),
    FixedPoint(16),
    Unknown17(17),
    Complex(18),
    LongType(19);

    override fun toString(): String {
        return "${super.toString()}, code: $typeCode"
    }

    companion object {
        fun fromByte(typeCode: UByte): ValueType {
            return when (typeCode.toInt()) {
                1 -> BooleanType
                2 -> ByteType
                3 -> ShortType
                4 -> IntegerType
                5 -> Currency
                6 -> FloatType
                7 -> DoubleType
                8 -> DateTimeType
                9 -> SmallBinary
                10 -> SmallText
                11 -> OLE
                12 -> Memo
                13 -> Unknown13
                15 -> GUID
                16 -> FixedPoint
                17 -> Unknown17
                18 -> Complex
                19 -> LongType
                else -> throw IllegalArgumentException("Invalid value type code: $typeCode")
            }
        }
    }
}
