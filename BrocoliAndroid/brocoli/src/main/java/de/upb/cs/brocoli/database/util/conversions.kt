package de.upb.cs.brocoli.database.util

import android.util.Base64


fun ByteArray.toBase64String(): String {
    val result = Base64.encodeToString(this, Base64.URL_SAFE.or(Base64.NO_WRAP))
    check(result != null, { "result is not allowed to be null" })
    return result
}

fun String.fromBase64ToByteArray(): ByteArray =
        Base64.decode(this, Base64.URL_SAFE)