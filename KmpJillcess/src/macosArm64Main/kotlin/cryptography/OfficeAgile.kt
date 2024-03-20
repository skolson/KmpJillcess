package com.oldguy.jillcess.cryptography

import com.oldguy.jillcess.OfficeAgileAppleParser

actual class OfficeAgile actual constructor() {
    actual fun parseXml(xml: String): CTEncryption {
        return OfficeAgileAppleParser()
            .parseXml(xml)
    }
}
