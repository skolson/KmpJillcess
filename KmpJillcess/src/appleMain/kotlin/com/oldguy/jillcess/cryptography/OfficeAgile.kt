package com.oldguy.jillcess

import com.oldguy.jillcess.cryptography.CTEncryption

actual class OfficeAgile actual constructor() {
    actual fun parseXml(xml: String): CTEncryption {
        return OfficeAgileAppleParser()
            .parseXml(xml)
    }
}