package com.offlinewallet.core

import org.bouncycastle.crypto.digests.KeccakDigest
import org.bouncycastle.crypto.digests.RIPEMD160Digest
import java.security.MessageDigest

fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

fun hash256(data: ByteArray): ByteArray = sha256(sha256(data))

fun ripemd160(data: ByteArray): ByteArray {
    val digest = RIPEMD160Digest()
    digest.update(data, 0, data.size)
    val out = ByteArray(20)
    digest.doFinal(out, 0)
    return out
}

fun hash160(data: ByteArray): ByteArray = ripemd160(sha256(data))

fun keccak256(data: ByteArray): ByteArray {
    val digest = KeccakDigest(256)
    digest.update(data, 0, data.size)
    val out = ByteArray(32)
    digest.doFinal(out, 0)
    return out
}
