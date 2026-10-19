package com.offlinewallet.core

import kotlin.math.roundToLong

const val BITCOIN_DUST_SATS = 294L
private const val SIGHASH_ALL = 1

data class Utxo(val txid: String, val vout: Int, val value: Long)

data class BtcOut(val value: Long, val script: ByteArray, val address: String)

data class BtcIn(
    val txidInternal: ByteArray,
    val vout: Long,
    val sequence: Long,
    val value: Long,
    val scriptPubKey: ByteArray,
    val partialPub: ByteArray? = null,
    val partialSig: ByteArray? = null,
)

data class Psbt(
    val version: Long,
    val locktime: Long,
    val inputs: List<BtcIn>,
    val outputs: List<BtcOut>,
)

data class PaymentPlan(
    val inputs: List<Utxo>,
    val payValue: Long,
    val changeValue: Long,
    val fee: Long,
    val vsize: Int,
)

data class BtcReview(
    val outputs: List<BtcOut>,
    val inputCount: Int,
    val inputSum: Long,
    val fee: Long,
    val feeRate: String,
    val foreignCount: Int,
)

data class ImportedKey(val priv: ByteArray, val compressedPub: ByteArray, val address: String)

enum class BtcAddressType(val wire: String, val title: String) {
    P2WPKH("p2wpkh", "Native SegWit"),
    P2SH_P2WPKH("p2sh-p2wpkh", "Nested SegWit"),
    P2PKH("p2pkh", "Legacy"),
    P2TR("p2tr", "Taproot"),
    ;

    companion object {
        fun fromWire(value: String?): BtcAddressType? = entries.firstOrNull { it.wire == value }
    }
}

fun NetworkId.btcHrp(): String = when (this) {
    NetworkId.BITCOIN_MAINNET -> "bc"
    NetworkId.BITCOIN_TESTNET3 -> "tb"
    else -> throw WalletException("不是比特币网络")
}

fun NetworkId.wifVersion(): Int = when (this) {
    NetworkId.BITCOIN_MAINNET -> 0x80
    NetworkId.BITCOIN_TESTNET3 -> 0xef
    else -> throw WalletException("不是比特币网络")
}

private fun NetworkId.p2pkhVersion(): Int = when (this) {
    NetworkId.BITCOIN_MAINNET -> 0x00
    NetworkId.BITCOIN_TESTNET3 -> 0x6f
    else -> throw WalletException("不是比特币网络")
}

private fun NetworkId.p2shVersion(): Int = when (this) {
    NetworkId.BITCOIN_MAINNET -> 0x05
    NetworkId.BITCOIN_TESTNET3 -> 0xc4
    else -> throw WalletException("不是比特币网络")
}

fun importWif(wif: String, network: NetworkId): ImportedKey {
    val payload = Base58.decodeCheck(wif.trim())
    if (payload.size != 34 || (payload.last().toInt() and 0xff) != 0x01) {
        throw WalletException("只接受压缩公钥的 WIF 私钥")
    }
    if ((payload[0].toInt() and 0xff) != network.wifVersion()) {
        throw WalletException("私钥网络与当前选择不一致")
    }
    val priv = payload.copyOfRange(1, 33)
    Secp256k1.checkPriv(priv)
    val pub = Secp256k1.compressedPub(priv)
    val address = Bech32.encodeSegwit(network.btcHrp(), 0, hash160(pub))
    return ImportedKey(priv, pub, address)
}

fun bitcoinAddress(priv: ByteArray, network: NetworkId, type: BtcAddressType): String {
    Secp256k1.checkPriv(priv)
    val pub = Secp256k1.compressedPub(priv)
    val hash = hash160(pub)
    return when (type) {
        BtcAddressType.P2WPKH -> Bech32.encodeSegwit(network.btcHrp(), 0, hash)
        BtcAddressType.P2PKH -> Base58.encodeCheck(byteArrayOf(network.p2pkhVersion().toByte()) + hash)
        BtcAddressType.P2SH_P2WPKH -> {
            val redeem = bytes(0x00, 0x14) + hash
            Base58.encodeCheck(byteArrayOf(network.p2shVersion().toByte()) + hash160(redeem))
        }
        BtcAddressType.P2TR -> Bech32.encodeSegwit(network.btcHrp(), 1, Secp256k1.taprootTweakedKey(priv).second)
    }
}

fun btcScriptType(script: ByteArray): BtcAddressType = when {
    script.size == 22 && script[0] == 0.toByte() && script[1] == 0x14.toByte() -> BtcAddressType.P2WPKH
    script.size == 25 && script[0] == 0x76.toByte() && script[23] == 0x88.toByte() && script[24] == 0xac.toByte() -> BtcAddressType.P2PKH
    script.size == 23 && script[0] == 0xa9.toByte() && script[22] == 0x87.toByte() -> BtcAddressType.P2SH_P2WPKH
    script.size == 34 && (script[0].toInt() and 0xff) == 0x51 && script[1] == 0x20.toByte() -> BtcAddressType.P2TR
    else -> throw WalletException("只能签名 P2WPKH、Nested SegWit、Legacy 或 Taproot")
}

fun exportWif(priv: ByteArray, network: NetworkId): String {
    Secp256k1.checkPriv(priv)
    return Base58.encodeCheck(byteArrayOf(network.wifVersion().toByte()) + priv + byteArrayOf(1))
}

fun addressToScript(address: String, network: NetworkId): ByteArray {
    val trimmed = address.trim()
    if (trimmed.startsWith("bc1", true) || trimmed.startsWith("tb1", true)) {
        val segwit = Bech32.decodeSegwit(trimmed)
        if (!segwit.hrp.equals(network.btcHrp(), true)) throw WalletException("地址属于别的比特币网络")
        return when {
            segwit.version == 0 && segwit.program.size == 20 -> bytes(0x00, 0x14) + segwit.program
            segwit.version == 0 && segwit.program.size == 32 -> bytes(0x00, 0x20) + segwit.program
            segwit.version == 1 && segwit.program.size == 32 -> bytes(0x51, 0x20) + segwit.program
            else -> throw WalletException("暂不支持这种隔离见证地址")
        }
    }
    val payload = try {
        Base58.decodeCheck(trimmed)
    } catch (e: WalletException) {
        throw WalletException("比特币地址无效")
    }
    if (payload.size != 21) throw WalletException("比特币地址无效")
    val version = payload[0].toInt() and 0xff
    val hash = payload.copyOfRange(1, 21)
    return when (version) {
        network.p2pkhVersion() -> bytes(0x76, 0xa9, 0x14) + hash + bytes(0x88, 0xac)
        network.p2shVersion() -> bytes(0xa9, 0x14) + hash + bytes(0x87)
        else -> throw WalletException("地址和当前网络对不上")
    }
}

fun scriptToAddress(script: ByteArray, network: NetworkId): String = when {
    script.size == 22 && script[0] == 0.toByte() && script[1] == 0x14.toByte() ->
        Bech32.encodeSegwit(network.btcHrp(), 0, script.copyOfRange(2, 22))
    script.size == 34 && script[0] == 0.toByte() && script[1] == 0x20.toByte() ->
        Bech32.encodeSegwit(network.btcHrp(), 0, script.copyOfRange(2, 34))
    script.size == 34 && (script[0].toInt() and 0xff) == 0x51 && script[1] == 0x20.toByte() ->
        Bech32.encodeSegwit(network.btcHrp(), 1, script.copyOfRange(2, 34))
    script.size == 25 && script[0] == 0x76.toByte() && script[1] == 0xa9.toByte() && script[2] == 0x14.toByte() &&
        script[23] == 0x88.toByte() && script[24] == 0xac.toByte() ->
        Base58.encodeCheck(byteArrayOf(network.p2pkhVersion().toByte()) + script.copyOfRange(3, 23))
    script.size == 23 && script[0] == 0xa9.toByte() && script[1] == 0x14.toByte() && script[22] == 0x87.toByte() ->
        Base58.encodeCheck(byteArrayOf(network.p2shVersion().toByte()) + script.copyOfRange(2, 22))
    else -> throw WalletException("有一笔输出不是标准转账，已拒绝签名")
}

fun selectCoins(
    utxos: List<Utxo>,
    payValue: Long,
    payScriptLen: Int,
    changeScriptLen: Int,
    feeRate: Double,
    spend: BtcAddressType = BtcAddressType.P2WPKH,
): PaymentPlan {
    if (payValue <= 0) throw WalletException("金额必须大于 0")
    if (feeRate <= 0.0) throw WalletException("手续费率必须大于 0")
    val sorted = utxos.filter { it.value > 0 }.sortedBy { it.value }
    if (sorted.isEmpty()) throw WalletException("没有可花费的已确认 UTXO")
    val chosen = ArrayList<Utxo>()
    var sum = 0L
    for (utxo in sorted) {
        if (chosen.size == 10) break
        if (sum > Long.MAX_VALUE - utxo.value) throw WalletException("金额超出范围")
        chosen += utxo
        sum += utxo.value
        val v2 = estimateVsize(chosen.size, listOf(payScriptLen, changeScriptLen), spend = spend)
        val fee2 = feeForRate(feeRate, v2)
        val change = sum - payValue - fee2
        if (sum >= payValue + fee2 && change >= BITCOIN_DUST_SATS) {
            return PaymentPlan(chosen.toList(), payValue, change, fee2, v2)
        }
        val v1 = estimateVsize(chosen.size, listOf(payScriptLen), spend = spend)
        val fee1 = feeForRate(feeRate, v1)
        if (sum >= payValue + fee1 && sum - payValue - fee2 < BITCOIN_DUST_SATS) {
            return PaymentPlan(chosen.toList(), payValue, 0, sum - payValue, v1)
        }
    }
    throw WalletException("余额不足，或需要的输入超过 10 笔")
}

fun feeForRate(feeRate: Double, vsize: Int): Long {
    val milli = (feeRate * 1000.0).roundToLong().coerceAtLeast(1_000)
    return (milli * vsize + 999) / 1_000
}

fun estimateVsize(
    inputCount: Int,
    outputScriptLengths: List<Int>,
    sigBytes: Int = 73,
    spend: BtcAddressType = BtcAddressType.P2WPKH,
): Int {
    var nonWitness = 8
    nonWitness += compactSizeLen(inputCount)
    val scriptSigLen = when (spend) {
        BtcAddressType.P2SH_P2WPKH -> 23
        BtcAddressType.P2PKH -> 107
        else -> 0
    }
    nonWitness += inputCount * (40 + compactSizeLen(scriptSigLen) + scriptSigLen)
    nonWitness += compactSizeLen(outputScriptLengths.size)
    for (len in outputScriptLengths) nonWitness += 8 + compactSizeLen(len) + len
    if (spend == BtcAddressType.P2PKH) return nonWitness
    val witnessItem = when (spend) {
        BtcAddressType.P2TR -> 66
        else -> 36 + sigBytes
    }
    val witness = 2 + inputCount * witnessItem
    return (nonWitness * 4 + witness + 3) / 4
}

fun buildPsbt(plan: PaymentPlan, payScript: ByteArray, payAddress: String, changeScript: ByteArray, changeAddress: String): Psbt {
    val outputs = ArrayList<BtcOut>()
    outputs += BtcOut(plan.payValue, payScript, payAddress)
    if (plan.changeValue > 0) outputs += BtcOut(plan.changeValue, changeScript, changeAddress)
    val inputs = plan.inputs.map { utxo ->
        BtcIn(
            txidInternal = utxo.txid.hexToBytes().reversedArray(),
            vout = utxo.vout.toLong(),
            sequence = 0xffffffffL,
            value = utxo.value,
            scriptPubKey = changeScript,
        )
    }
    return Psbt(version = 2, locktime = 0, inputs = inputs, outputs = outputs)
}

fun encodePsbt(psbt: Psbt): ByteArray {
    val buf = Buf()
    buf.bytes(bytes(0x70, 0x73, 0x62, 0x74, 0xff))
    buf.entry(byteArrayOf(0x00), serializeLegacy(psbt))
    buf.u8(0xff)
    for (input in psbt.inputs) {
        buf.entry(byteArrayOf(0x01), witnessUtxo(input))
        if (input.partialPub != null && input.partialSig != null) {
            buf.entry(byteArrayOf(0x02) + input.partialPub, input.partialSig)
        }
        buf.u8(0xff)
    }
    repeat(psbt.outputs.size) { buf.u8(0xff) }
    return buf.toByteArray()
}

fun decodePsbt(data: ByteArray): Psbt {
    val reader = Rdr(data)
    val magic = reader.bytes(5)
    if (!magic.contentEquals(bytes(0x70, 0x73, 0x62, 0x74, 0xff))) throw WalletException("不是 PSBT 交易")
    var unsigned: ByteArray? = null
    while (true) {
        if (reader.peek() == 0xff) {
            reader.u8()
            break
        }
        val (key, value) = reader.entry()
        if (key.contentEquals(byteArrayOf(0x00))) unsigned = value
    }
    val raw = unsigned ?: throw WalletException("交易缺少未签名原文")
    val parsed = parseLegacy(raw)
    val inputs = parsed.inputs.map { skeleton ->
        var value = -1L
        var script = ByteArray(0)
        var pub: ByteArray? = null
        var sig: ByteArray? = null
        while (true) {
            if (reader.peek() == 0xff) {
                reader.u8()
                break
            }
            val (key, field) = reader.entry()
            when (key[0].toInt() and 0xff) {
                0x01 -> {
                    if (key.size != 1 || field.size < 9) throw WalletException("输入金额缺失")
                    value = u64le(field, 0)
                    val nested = Rdr(field.copyOfRange(8, field.size))
                    script = nested.script()
                }
                0x02 -> {
                    if (key.size < 2) throw WalletException("签名公钥缺失")
                    pub = key.copyOfRange(1, key.size)
                    sig = field
                }
            }
        }
        if (value < 0 || script.isEmpty()) throw WalletException("交易缺少输入金额，拒绝签名")
        skeleton.copy(value = value, scriptPubKey = script, partialPub = pub, partialSig = sig)
    }
    val outputs = parsed.outputs.map { output ->
        while (reader.peek() != 0xff) reader.entry()
        reader.u8()
        output
    }
    if (!reader.finished()) throw WalletException("PSBT 末尾有多余数据")
    return Psbt(parsed.version, parsed.locktime, inputs, outputs)
}

fun reviewBitcoin(psbt: Psbt, ourAddress: String, network: NetworkId): BtcReview {
    if (psbt.inputs.isEmpty() || psbt.inputs.size > 10) throw WalletException("输入笔数必须在 1 到 10 之间")
    if (psbt.outputs.isEmpty()) throw WalletException("交易没有输出")
    val ourScript = addressToScript(ourAddress, network)
    val spend = btcScriptType(ourScript)
    var inputSum = 0L
    for (input in psbt.inputs) {
        if (!input.scriptPubKey.contentEquals(ourScript)) throw WalletException("有一笔输入不属于本机地址，已拒绝")
        if (input.value <= 0) throw WalletException("输入金额无效")
        inputSum += input.value
    }
    val outputs = psbt.outputs.map { output ->
        val address = scriptToAddress(output.script, network)
        output.copy(address = address)
    }
    val outputSum = outputs.sumOf { it.value }
    if (outputSum >= inputSum) throw WalletException("手续费必须大于 0")
    val fee = inputSum - outputSum
    val foreign = outputs.count { !it.address.equals(ourAddress, true) }
    if (foreign > 1) throw WalletException("一期只允许一个收款地址")
    val vsize = estimateVsize(psbt.inputs.size, outputs.map { it.script.size }, spend = spend)
    val rate = fee.toDouble() / vsize
    return BtcReview(outputs, psbt.inputs.size, inputSum, fee, "%.1f".format(java.util.Locale.US, rate), foreign)
}

fun signBitcoin(psbt: Psbt, priv: ByteArray): Psbt {
    val pub = Secp256k1.compressedPub(priv)
    val signedInputs = psbt.inputs.mapIndexed { index, input ->
        when (btcScriptType(input.scriptPubKey)) {
            BtcAddressType.P2WPKH -> {
                if (!hash160(pub).contentEquals(input.scriptPubKey.copyOfRange(2, 22))) {
                    throw WalletException("私钥和交易输入对不上")
                }
                ecdsaInput(psbt, index, input, pub, priv, p2wpkhScriptCode(input.scriptPubKey), bip143 = true)
            }
            BtcAddressType.P2SH_P2WPKH -> {
                val redeem = bytes(0x00, 0x14) + hash160(pub)
                if (!hash160(redeem).contentEquals(input.scriptPubKey.copyOfRange(2, 22))) {
                    throw WalletException("私钥和交易输入对不上")
                }
                ecdsaInput(psbt, index, input, pub, priv, bytes(0x76, 0xa9, 0x14) + hash160(pub) + bytes(0x88, 0xac), bip143 = true)
            }
            BtcAddressType.P2PKH -> {
                if (!hash160(pub).contentEquals(input.scriptPubKey.copyOfRange(3, 23))) {
                    throw WalletException("私钥和交易输入对不上")
                }
                ecdsaInput(psbt, index, input, pub, priv, input.scriptPubKey, bip143 = false)
            }
            BtcAddressType.P2TR -> {
                val (tweaked, output) = Secp256k1.taprootTweakedKey(priv)
                if (!output.contentEquals(input.scriptPubKey.copyOfRange(2, 34))) {
                    throw WalletException("私钥和交易输入对不上")
                }
                val aux = sha256(priv + "taproot-aux".toByteArray() + input.txidInternal)
                val sig = Secp256k1.schnorrSign(taprootSighash(psbt, index), tweaked, aux)
                input.copy(partialPub = output, partialSig = sig)
            }
        }
    }
    return psbt.copy(inputs = signedInputs)
}

private fun ecdsaInput(
    psbt: Psbt,
    index: Int,
    input: BtcIn,
    pub: ByteArray,
    priv: ByteArray,
    scriptCode: ByteArray,
    bip143: Boolean,
): BtcIn {
    val preimage = if (bip143) bip143Preimage(psbt, index, scriptCode) else legacyPreimage(psbt, index, scriptCode)
    val sig = Secp256k1.sign(hash256(preimage), priv)
    val der = Secp256k1.derEncode(sig.r, sig.s) + byteArrayOf(SIGHASH_ALL.toByte())
    return input.copy(partialPub = pub, partialSig = der)
}

fun verifyBitcoin(psbt: Psbt) {
    if (psbt.inputs.any { it.partialPub == null || it.partialSig == null }) {
        throw WalletException("签名不完整")
    }
    for ((index, input) in psbt.inputs.withIndex()) {
        when (btcScriptType(input.scriptPubKey)) {
            BtcAddressType.P2TR -> {
                val sig = input.partialSig!!
                if (sig.size != 64) throw WalletException("Taproot 签名长度不对")
                if (!input.partialPub!!.contentEquals(input.scriptPubKey.copyOfRange(2, 34))) {
                    throw WalletException("签名公钥和输入对不上")
                }
                if (!Secp256k1.schnorrVerify(taprootSighash(psbt, index), sig, input.partialPub)) {
                    throw WalletException("签名校验失败")
                }
            }
            BtcAddressType.P2PKH -> verifyEcdsa(psbt, index, input, input.scriptPubKey, bip143 = false)
            BtcAddressType.P2WPKH -> {
                if (!hash160(input.partialPub!!).contentEquals(input.scriptPubKey.copyOfRange(2, 22))) {
                    throw WalletException("签名公钥和输入对不上")
                }
                verifyEcdsa(psbt, index, input, p2wpkhScriptCode(input.scriptPubKey), bip143 = true)
            }
            BtcAddressType.P2SH_P2WPKH -> {
                val redeem = bytes(0x00, 0x14) + hash160(input.partialPub!!)
                if (!hash160(redeem).contentEquals(input.scriptPubKey.copyOfRange(2, 22))) {
                    throw WalletException("签名公钥和输入对不上")
                }
                val scriptCode = bytes(0x76, 0xa9, 0x14) + hash160(input.partialPub) + bytes(0x88, 0xac)
                verifyEcdsa(psbt, index, input, scriptCode, bip143 = true)
            }
        }
    }
}

private fun verifyEcdsa(psbt: Psbt, index: Int, input: BtcIn, scriptCode: ByteArray, bip143: Boolean) {
    val sighashByte = input.partialSig!!.last().toInt() and 0xff
    if (sighashByte != SIGHASH_ALL) throw WalletException("只接受 SIGHASH_ALL")
    val (r, s) = Secp256k1.derDecode(input.partialSig.copyOf(input.partialSig.size - 1))
    val preimage = if (bip143) bip143Preimage(psbt, index, scriptCode) else legacyPreimage(psbt, index, scriptCode)
    if (!Secp256k1.verify(hash256(preimage), r, s, input.partialPub!!)) throw WalletException("签名校验失败")
}

fun sameUnsignedBitcoin(left: Psbt, right: Psbt): Boolean {
    if (left.version != right.version || left.locktime != right.locktime) return false
    if (left.inputs.size != right.inputs.size || left.outputs.size != right.outputs.size) return false
    for (i in left.inputs.indices) {
        val a = left.inputs[i]
        val b = right.inputs[i]
        if (!a.txidInternal.contentEquals(b.txidInternal) || a.vout != b.vout || a.sequence != b.sequence) return false
        if (a.value != b.value || !a.scriptPubKey.contentEquals(b.scriptPubKey)) return false
    }
    for (i in left.outputs.indices) {
        if (left.outputs[i].value != right.outputs[i].value || !left.outputs[i].script.contentEquals(right.outputs[i].script)) {
            return false
        }
    }
    return true
}

fun bitcoinTxid(psbt: Psbt): String = hash256(serializeLegacy(psbt)).reversedArray().toHex()

fun extractRawBitcoin(psbt: Psbt): ByteArray {
    verifyBitcoin(psbt)
    val spend = btcScriptType(psbt.inputs.first().scriptPubKey)
    if (psbt.inputs.any { btcScriptType(it.scriptPubKey) != spend }) throw WalletException("一笔交易里混用了不同地址类型")
    val buf = Buf()
    buf.u32(psbt.version)
    val witness = spend != BtcAddressType.P2PKH
    if (witness) {
        buf.u8(0x00)
        buf.u8(0x01)
    }
    buf.compact(psbt.inputs.size)
    for (input in psbt.inputs) {
        buf.bytes(input.txidInternal)
        buf.u32(input.vout)
        when (spend) {
            BtcAddressType.P2PKH -> buf.script(legacyScriptSig(input.partialSig!!, input.partialPub!!))
            BtcAddressType.P2SH_P2WPKH -> buf.script(bytes(0x00, 0x14) + hash160(input.partialPub!!))
            else -> buf.u8(0x00)
        }
        buf.u32(input.sequence)
    }
    buf.compact(psbt.outputs.size)
    for (output in psbt.outputs) {
        buf.u64(output.value)
        buf.script(output.script)
    }
    if (witness) {
        for (input in psbt.inputs) {
            if (spend == BtcAddressType.P2TR) {
                buf.compact(1)
                buf.script(input.partialSig!!)
            } else {
                buf.compact(2)
                buf.script(input.partialSig!!)
                buf.script(input.partialPub!!)
            }
        }
    }
    buf.u32(psbt.locktime)
    return buf.toByteArray()
}

private fun legacyScriptSig(signature: ByteArray, pub: ByteArray): ByteArray {
    val buf = Buf()
    buf.script(signature)
    buf.script(pub)
    return buf.toByteArray()
}

private fun legacyPreimage(psbt: Psbt, index: Int, scriptCode: ByteArray): ByteArray {
    val buf = Buf()
    buf.u32(psbt.version)
    buf.compact(psbt.inputs.size)
    for ((inputIndex, item) in psbt.inputs.withIndex()) {
        buf.bytes(item.txidInternal)
        buf.u32(item.vout)
        if (inputIndex == index) buf.script(scriptCode) else buf.u8(0x00)
        buf.u32(item.sequence)
    }
    buf.compact(psbt.outputs.size)
    for (output in psbt.outputs) {
        buf.u64(output.value)
        buf.script(output.script)
    }
    buf.u32(psbt.locktime)
    buf.u32(SIGHASH_ALL.toLong())
    return buf.toByteArray()
}

private fun taprootSighash(psbt: Psbt, index: Int): ByteArray {
    val buf = Buf()
    buf.u8(0x00)
    buf.u8(0x00)
    buf.u32(psbt.version)
    buf.u32(psbt.locktime)
    buf.bytes(sha256(run {
        val prev = Buf()
        for (item in psbt.inputs) {
            prev.bytes(item.txidInternal)
            prev.u32(item.vout)
        }
        prev.toByteArray()
    }))
    buf.bytes(sha256(run {
        val amounts = Buf()
        for (item in psbt.inputs) amounts.u64(item.value)
        amounts.toByteArray()
    }))
    buf.bytes(sha256(run {
        val scripts = Buf()
        for (item in psbt.inputs) scripts.script(item.scriptPubKey)
        scripts.toByteArray()
    }))
    buf.bytes(sha256(run {
        val seq = Buf()
        for (item in psbt.inputs) seq.u32(item.sequence)
        seq.toByteArray()
    }))
    buf.bytes(sha256(run {
        val outs = Buf()
        for (output in psbt.outputs) {
            outs.u64(output.value)
            outs.script(output.script)
        }
        outs.toByteArray()
    }))
    buf.u8(0x00)
    buf.u32(index.toLong())
    return Secp256k1.taggedHash("TapSighash", buf.toByteArray())
}

internal fun bip143Preimage(psbt: Psbt, index: Int, scriptCode: ByteArray): ByteArray {
    val input = psbt.inputs[index]
    val buf = Buf()
    buf.u32(psbt.version)
    buf.bytes(hash256(run {
        val prev = Buf()
        for (item in psbt.inputs) {
            prev.bytes(item.txidInternal)
            prev.u32(item.vout)
        }
        prev.toByteArray()
    }))
    buf.bytes(hash256(run {
        val seq = Buf()
        for (item in psbt.inputs) seq.u32(item.sequence)
        seq.toByteArray()
    }))
    buf.bytes(input.txidInternal)
    buf.u32(input.vout)
    buf.script(scriptCode)
    buf.u64(input.value)
    buf.u32(input.sequence)
    buf.bytes(hash256(run {
        val outs = Buf()
        for (output in psbt.outputs) {
            outs.u64(output.value)
            outs.script(output.script)
        }
        outs.toByteArray()
    }))
    buf.u32(psbt.locktime)
    buf.u32(SIGHASH_ALL.toLong())
    return buf.toByteArray()
}

private fun p2wpkhScriptCode(scriptPubKey: ByteArray): ByteArray {
    if (scriptPubKey.size != 22 || scriptPubKey[0] != 0.toByte() || scriptPubKey[1] != 0x14.toByte()) {
        throw WalletException("只能签名本机的 P2WPKH 输入")
    }
    return bytes(0x76, 0xa9, 0x14) + scriptPubKey.copyOfRange(2, 22) + bytes(0x88, 0xac)
}

private data class Legacy(val version: Long, val inputs: List<BtcIn>, val outputs: List<BtcOut>, val locktime: Long)

private fun parseLegacy(raw: ByteArray): Legacy {
    val reader = Rdr(raw)
    val version = reader.u32()
    if (reader.peek() == 0x00) throw WalletException("PSBT 中的未签名交易不应带见证")
    val inputs = List(reader.compact()) {
        val txid = reader.bytes(32)
        val vout = reader.u32()
        val scriptSig = reader.script()
        val sequence = reader.u32()
        if (scriptSig.isNotEmpty()) throw WalletException("未签名交易里不该有 scriptSig")
        BtcIn(txid, vout, sequence, value = 0, scriptPubKey = ByteArray(0))
    }
    val outputs = List(reader.compact()) {
        val value = reader.u64()
        val script = reader.script()
        BtcOut(value, script, address = "")
    }
    val locktime = reader.u32()
    if (!reader.finished()) throw WalletException("未签名交易长度不对")
    return Legacy(version, inputs, outputs, locktime)
}

private fun serializeLegacy(psbt: Psbt): ByteArray {
    val buf = Buf()
    buf.u32(psbt.version)
    buf.compact(psbt.inputs.size)
    for (input in psbt.inputs) {
        buf.bytes(input.txidInternal)
        buf.u32(input.vout)
        buf.u8(0x00)
        buf.u32(input.sequence)
    }
    buf.compact(psbt.outputs.size)
    for (output in psbt.outputs) {
        buf.u64(output.value)
        buf.script(output.script)
    }
    buf.u32(psbt.locktime)
    return buf.toByteArray()
}

private fun witnessUtxo(input: BtcIn): ByteArray {
    val buf = Buf()
    buf.u64(input.value)
    buf.script(input.scriptPubKey)
    return buf.toByteArray()
}

private fun compactSizeLen(n: Int): Int = when {
    n < 0xfd -> 1
    n <= 0xffff -> 3
    else -> 5
}

private fun u64le(data: ByteArray, offset: Int): Long {
    var value = 0L
    for (i in 0 until 8) value = value or ((data[offset + i].toLong() and 0xff) shl (8 * i))
    return value
}

private class Buf {
    private val out = ArrayList<Byte>()
    fun u8(value: Int) { out += value.toByte() }
    fun u32(value: Long) {
        val v = value and 0xffffffffL
        repeat(4) { out += ((v ushr (8 * it)) and 0xff).toByte() }
    }
    fun u64(value: Long) {
        if (value < 0) throw WalletException("金额不能为负")
        repeat(8) { out += ((value ushr (8 * it)) and 0xff).toByte() }
    }
    fun bytes(value: ByteArray) { value.forEach { out += it } }
    fun compact(value: Int) {
        when {
            value < 0xfd -> u8(value)
            value <= 0xffff -> { u8(0xfd); u8(value); u8(value ushr 8) }
            else -> { u8(0xfe); u32(value.toLong()) }
        }
    }
    fun script(value: ByteArray) { compact(value.size); bytes(value) }
    fun entry(key: ByteArray, value: ByteArray) { script(key); script(value) }
    fun toByteArray(): ByteArray = out.toByteArray()
}

private class Rdr(private val data: ByteArray) {
    private var i = 0
    fun finished(): Boolean = i == data.size
    fun peek(): Int = if (i >= data.size) throw WalletException("报文不完整") else data[i].toInt() and 0xff
    fun u8(): Int = peek().also { i++ }
    fun u32(): Long {
        var value = 0L
        repeat(4) { value = value or ((u8().toLong()) shl (8 * it)) }
        return value
    }
    fun u64(): Long {
        var value = 0L
        repeat(8) { value = value or ((u8().toLong()) shl (8 * it)) }
        return value
    }
    fun bytes(n: Int): ByteArray {
        if (n < 0 || i + n > data.size) throw WalletException("报文不完整")
        return data.copyOfRange(i, i + n).also { i += n }
    }
    fun compact(): Int {
        val first = u8()
        return when (first) {
            0xfd -> u8() or (u8() shl 8)
            0xfe -> u32().also { if (it > Int.MAX_VALUE) throw WalletException("长度超出范围") }.toInt()
            else -> first
        }
    }
    fun script(): ByteArray = bytes(compact())
    fun entry(): Pair<ByteArray, ByteArray> = script() to script()
}
