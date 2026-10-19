package com.offlinewallet.core

object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val BECH32 = 1
    private const val BECH32M = 0x2bc830a3

    data class Segwit(val hrp: String, val version: Int, val program: ByteArray)

    fun encodeSegwit(hrp: String, version: Int, program: ByteArray): String {
        require(version in 0..16)
        val spec = if (version == 0) BECH32 else BECH32M
        val converted = convertBits(program.map { it.toInt() and 0xff }.toIntArray(), 8, 5, true)
        val words = IntArray(1 + converted.size)
        words[0] = version
        converted.copyInto(words, 1)
        val encoded = encode(hrp.lowercase(), words, spec)
        val back = decodeSegwit(encoded)
        if (!back.hrp.equals(hrp, true) || back.version != version || !back.program.contentEquals(program)) {
            throw IllegalStateException("bech32 自检失败")
        }
        return encoded
    }

    fun decodeSegwit(address: String): Segwit {
        if (address.length < 8 || address.length > 90) throw WalletException("地址长度不正确")
        if (address != address.lowercase() && address != address.uppercase()) {
            throw WalletException("地址大小写不能混用")
        }
        val lower = address.lowercase()
        val sep = lower.lastIndexOf('1')
        if (sep < 1 || sep + 7 > lower.length) throw WalletException("地址无效")
        val hrp = lower.substring(0, sep)
        val data = IntArray(lower.length - sep - 1)
        for (i in data.indices) {
            val index = CHARSET.indexOf(lower[sep + 1 + i])
            if (index < 0) throw WalletException("地址包含无效字符")
            data[i] = index
        }
        val spec = polymod(hrpExpand(hrp) + data)
        if (spec != BECH32 && spec != BECH32M) throw WalletException("地址校验失败")
        if (data.size < 7) throw WalletException("地址无效")
        val words = data.copyOf(data.size - 6)
        val version = words[0]
        if (version !in 0..16) throw WalletException("隔离见证版本不受支持")
        if (version == 0 && spec != BECH32) throw WalletException("隔离见证 v0 地址校验失败")
        if (version != 0 && spec != BECH32M) throw WalletException("隔离见证地址校验失败")
        val program = convertBits(words.copyOfRange(1, words.size), 5, 8, false).let { ints ->
            ByteArray(ints.size) { ints[it].toByte() }
        }
        if (program.size !in 2..40) throw WalletException("见证程序长度不正确")
        if (version == 0 && program.size != 20 && program.size != 32) {
            throw WalletException("见证程序长度不正确")
        }
        return Segwit(hrp, version, program)
    }

    private fun encode(hrp: String, data: IntArray, spec: Int): String {
        val checksum = createChecksum(hrp, data, spec)
        val combined = data + checksum
        return hrp + "1" + combined.joinToString("") { CHARSET[it].toString() }
    }

    private fun createChecksum(hrp: String, data: IntArray, spec: Int): IntArray {
        val values = hrpExpand(hrp) + data + IntArray(6)
        val mod = polymod(values) xor spec
        return IntArray(6) { (mod ushr (5 * (5 - it))) and 31 }
    }

    private fun polymod(values: IntArray): Int {
        val generators = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (value in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor value
            for (i in 0..4) {
                if (((top ushr i) and 1) != 0) chk = chk xor generators[i]
            }
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray {
        val out = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) out[i] = hrp[i].code ushr 5
        for (i in hrp.indices) out[hrp.length + 1 + i] = hrp[i].code and 31
        return out
    }

    private fun convertBits(data: IntArray, fromBits: Int, toBits: Int, pad: Boolean): IntArray {
        var acc = 0
        var bits = 0
        val maxv = (1 shl toBits) - 1
        val result = ArrayList<Int>(data.size * fromBits / toBits + 1)
        for (value in data) {
            if (value < 0 || (value ushr fromBits) != 0) throw WalletException("地址编码无效")
            acc = (acc shl fromBits) or value
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                result.add((acc ushr bits) and maxv)
            }
        }
        if (pad) {
            if (bits > 0) result.add((acc shl (toBits - bits)) and maxv)
        } else if (bits >= fromBits || ((acc shl (toBits - bits)) and maxv) != 0) {
            throw WalletException("地址编码填充不正确")
        }
        return result.toIntArray()
    }
}
