package com.offlinewallet.core

import java.math.BigInteger

object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        var zeros = 0
        while (zeros < input.size && input[zeros] == 0.toByte()) zeros++
        var number = BigInteger(1, input)
        val encoded = StringBuilder()
        val base = BigInteger.valueOf(58)
        while (number > BigInteger.ZERO) {
            val div = number.divideAndRemainder(base)
            encoded.append(ALPHABET[div[1].toInt()])
            number = div[0]
        }
        repeat(zeros) { encoded.append('1') }
        return encoded.reverse().toString()
    }

    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        var zeros = 0
        while (zeros < input.length && input[zeros] == '1') zeros++
        var number = BigInteger.ZERO
        val base = BigInteger.valueOf(58)
        for (ch in input) {
            val index = ALPHABET.indexOf(ch)
            if (index < 0) throw WalletException("Base58 字符无效")
            number = number.multiply(base).add(BigInteger.valueOf(index.toLong()))
        }
        val body = if (number == BigInteger.ZERO) ByteArray(0) else number.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(zeros) + body
    }

    fun encodeCheck(payload: ByteArray): String {
        val checksum = hash256(payload).copyOf(4)
        return encode(payload + checksum)
    }

    fun decodeCheck(input: String): ByteArray {
        val full = decode(input.trim())
        if (full.size < 5) throw WalletException("内容太短")
        val payload = full.copyOf(full.size - 4)
        val checksum = full.copyOfRange(full.size - 4, full.size)
        val expected = hash256(payload).copyOf(4)
        if (!checksum.contentEquals(expected)) throw WalletException("校验和错误")
        return payload
    }
}
