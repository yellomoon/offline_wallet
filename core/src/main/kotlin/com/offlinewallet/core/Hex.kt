package com.offlinewallet.core

fun String.hexToBytes(): ByteArray {
    val s = trim().removePrefix("0x").removePrefix("0X")
    if (s.length % 2 != 0) throw WalletException("十六进制长度必须是偶数")
    if (s.isEmpty()) return ByteArray(0)
    if (!s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
        throw WalletException("包含非十六进制字符")
    }
    return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
