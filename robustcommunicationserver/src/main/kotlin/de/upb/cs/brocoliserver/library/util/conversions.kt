package de.upb.cs.brocoliserver.library.util

import org.apache.commons.codec.binary.Base64

fun ByteArray.toBase64String(): String {
    val result = Base64.encodeBase64String(this)
    check(result != null, { "result is not allowed to be null" })
    return result
}

fun String.fromBase64ToByteArray(): ByteArray =
        Base64.decodeBase64(this)