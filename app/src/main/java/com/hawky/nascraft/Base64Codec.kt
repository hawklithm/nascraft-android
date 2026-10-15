package com.hawky.nascraft

/** Small RFC 4648 encoder/decoder that works on the app's full minSdk range. */
object Base64Codec {
    private const val STANDARD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val URL_SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encodeStandard(bytes: ByteArray): String = encode(bytes, STANDARD, padded = true)

    fun encodeUrlNoPadding(bytes: ByteArray): String = encode(bytes, URL_SAFE, padded = false)

    fun decodeUrlNoPadding(value: String): ByteArray {
        require(value.matches(Regex("[A-Za-z0-9_-]*")) && value.length % 4 != 1) {
            "Invalid base64url value"
        }
        val result = ByteArray(value.length * 3 / 4)
        var accumulator = 0
        var bits = 0
        var index = 0
        for (character in value) {
            val digit = URL_SAFE.indexOf(character)
            require(digit >= 0) { "Invalid base64url value" }
            accumulator = (accumulator shl 6) or digit
            bits += 6
            if (bits >= 8) {
                bits -= 8
                result[index++] = (accumulator shr bits).toByte()
                accumulator = accumulator and ((1 shl bits) - 1)
            }
        }
        require(bits == 0 || accumulator == 0) { "Non-canonical base64url value" }
        return result.copyOf(index)
    }

    private fun encode(bytes: ByteArray, alphabet: String, padded: Boolean): String = buildString {
        var index = 0
        while (index + 2 < bytes.size) {
            val value = ((bytes[index].toInt() and 0xff) shl 16) or
                ((bytes[index + 1].toInt() and 0xff) shl 8) or
                (bytes[index + 2].toInt() and 0xff)
            append(alphabet[(value ushr 18) and 0x3f])
            append(alphabet[(value ushr 12) and 0x3f])
            append(alphabet[(value ushr 6) and 0x3f])
            append(alphabet[value and 0x3f])
            index += 3
        }
        val remaining = bytes.size - index
        if (remaining == 1) {
            val value = (bytes[index].toInt() and 0xff) shl 16
            append(alphabet[(value ushr 18) and 0x3f])
            append(alphabet[(value ushr 12) and 0x3f])
            if (padded) append("==")
        } else if (remaining == 2) {
            val value = ((bytes[index].toInt() and 0xff) shl 16) or
                ((bytes[index + 1].toInt() and 0xff) shl 8)
            append(alphabet[(value ushr 18) and 0x3f])
            append(alphabet[(value ushr 12) and 0x3f])
            append(alphabet[(value ushr 6) and 0x3f])
            if (padded) append('=')
        }
    }
}
