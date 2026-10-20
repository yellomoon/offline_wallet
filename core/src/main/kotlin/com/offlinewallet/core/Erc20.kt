package com.offlinewallet.core

import java.math.BigInteger

object Erc20 {
    const val NAME = "06fdde03"
    const val SYMBOL = "95d89b41"
    const val DECIMALS = "313ce567"

    fun decodeString(raw: ByteArray): String {
        if (raw.isEmpty() || raw.all { it == 0.toByte() }) throw WalletException("合约没有返回文本")
        val text = if (raw.size == 32) bytes32(raw) else abiString(raw)
        if (text.isBlank()) throw WalletException("合约没有返回文本")
        return text
    }

    fun decodeUint(raw: ByteArray): BigInteger {
        if (raw.isEmpty() || raw.size > 32 && raw.size % 32 != 0) throw WalletException("合约没有返回小数位数")
        val word = if (raw.size >= 32) raw.copyOfRange(raw.size - 32, raw.size) else raw
        return BigInteger(1, word)
    }

    private fun bytes32(raw: ByteArray): String {
        val end = raw.indexOf(0).let { if (it < 0) raw.size else it }
        return raw.copyOf(end).toString(Charsets.UTF_8).trim()
    }

    private fun abiString(raw: ByteArray): String {
        if (raw.size < 64) throw WalletException("合约返回了无法识别的文本")
        val offset = BigInteger(1, raw.copyOfRange(0, 32)).toInt()
        if (offset < 0 || offset + 32 > raw.size) throw WalletException("合约返回了无法识别的文本")
        val length = BigInteger(1, raw.copyOfRange(offset, offset + 32)).toInt()
        val start = offset + 32
        if (length < 0 || start + length > raw.size) throw WalletException("合约返回了无法识别的文本")
        return raw.copyOfRange(start, start + length).toString(Charsets.UTF_8)
    }
}
