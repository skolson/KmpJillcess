package com.oldguy.jillcess.cryptography

actual class OfficeAgile actual constructor() {
    actual fun parseXml(xml: String): CTEncryption {
        return OfficeAgileAppleParser()
            .parseXml(xml)
    }
}