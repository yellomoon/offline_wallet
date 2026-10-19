package com.offlinewallet.core

import java.math.BigInteger

object Rlp {
    sealed class Item {
        data class Bytes(val data: ByteArray) : Item()
        data class List(val items: kotlin.collections.List<Item>) : Item()
    }

    fun bytes(data: ByteArray): Item = Item.Bytes(data)

    fun uint(value: BigInteger): Item {
        if (value.signum() < 0) throw WalletException("RLP 不能编码负数")
        if (value.signum() == 0) return Item.Bytes(ByteArray(0))
        var raw = value.toByteArray()
        if (raw[0] == 0.toByte()) raw = raw.copyOfRange(1, raw.size)
        return Item.Bytes(raw)
    }

    fun uint(value: Long): Item {
        if (value < 0) throw WalletException("RLP 不能编码负数")
        return uint(BigInteger.valueOf(value))
    }

    fun encode(item: Item): ByteArray = when (item) {
        is Item.Bytes -> encodeBytes(item.data)
        is Item.List -> {
            val payload = item.items.fold(ByteArray(0)) { acc, child -> acc + encode(child) }
            if (payload.size <= 55) byteArrayOf((0xc0 + payload.size).toByte()) + payload
            else encodeLength(payload.size, 0xf7) + payload
        }
    }

    fun decode(data: ByteArray): Item {
        val (item, next) = read(data, 0)
        if (next != data.size) throw WalletException("RLP 末尾有多余字节")
        return item
    }

    fun asUInt(item: Item): BigInteger {
        val bytes = (item as? Item.Bytes)?.data ?: throw WalletException("RLP 字段不是整数")
        if (bytes.isEmpty()) return BigInteger.ZERO
        if (bytes.size > 1 && bytes[0] == 0.toByte()) throw WalletException("RLP 整数不是最短编码")
        return BigInteger(1, bytes)
    }

    private fun read(data: ByteArray, offset: Int): Pair<Item, Int> {
        if (offset >= data.size) throw WalletException("RLP 不完整")
        val prefix = data[offset].toInt() and 0xff
        return when {
            prefix <= 0x7f -> Item.Bytes(byteArrayOf(prefix.toByte())) to offset + 1
            prefix <= 0xb7 -> {
                val len = prefix - 0x80
                val start = offset + 1
                val end = start + len
                if (end > data.size) throw WalletException("RLP 不完整")
                if (len == 1 && (data[start].toInt() and 0xff) < 0x80) throw WalletException("RLP 不是最短编码")
                Item.Bytes(data.copyOfRange(start, end)) to end
            }
            prefix <= 0xbf -> {
                val lenOfLen = prefix - 0xb7
                val (len, start) = readLen(data, offset + 1, lenOfLen)
                val end = start + len
                if (end > data.size) throw WalletException("RLP 不完整")
                Item.Bytes(data.copyOfRange(start, end)) to end
            }
            prefix <= 0xf7 -> {
                val len = prefix - 0xc0
                val start = offset + 1
                val end = start + len
                if (end > data.size) throw WalletException("RLP 不完整")
                readList(data, start, end) to end
            }
            else -> {
                val lenOfLen = prefix - 0xf7
                val (len, start) = readLen(data, offset + 1, lenOfLen)
                val end = start + len
                if (end > data.size) throw WalletException("RLP 不完整")
                readList(data, start, end) to end
            }
        }
    }

    private fun readList(data: ByteArray, start: Int, end: Int): Item.List {
        val items = ArrayList<Item>()
        var cursor = start
        while (cursor < end) {
            val (item, next) = read(data, cursor)
            items.add(item)
            cursor = next
        }
        if (cursor != end) throw WalletException("RLP 列表长度不正确")
        return Item.List(items)
    }

    private fun readLen(data: ByteArray, offset: Int, lenOfLen: Int): Pair<Int, Int> {
        if (lenOfLen > 4 || offset + lenOfLen > data.size) throw WalletException("RLP 长度非法")
        var len = 0
        for (i in 0 until lenOfLen) {
            len = (len shl 8) or (data[offset + i].toInt() and 0xff)
        }
        if (len < 56) throw WalletException("RLP 不是最短编码")
        return len to offset + lenOfLen
    }

    private fun encodeBytes(data: ByteArray): ByteArray = when {
        data.size == 1 && (data[0].toInt() and 0xff) < 0x80 -> data
        data.size <= 55 -> byteArrayOf((0x80 + data.size).toByte()) + data
        else -> encodeLength(data.size, 0xb7) + data
    }

    private fun encodeLength(length: Int, offset: Int): ByteArray {
        val lenBytes = ArrayList<Byte>()
        var rest = length
        while (rest > 0) {
            lenBytes.add(0, (rest and 0xff).toByte())
            rest = rest ushr 8
        }
        return byteArrayOf((offset + lenBytes.size).toByte()) + lenBytes.toByteArray()
    }
}
