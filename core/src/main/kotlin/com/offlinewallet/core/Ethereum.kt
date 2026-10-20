package com.offlinewallet.core

import java.math.BigInteger

data class EthUnsigned(
    val chainId: Long,
    val nonce: Long,
    val maxPriorityFeePerGas: BigInteger,
    val maxFeePerGas: BigInteger,
    val gasLimit: Long,
    val to: ByteArray,
    val value: BigInteger,
    val data: ByteArray = ByteArray(0),
)

data class EthReview(
    val chainId: Long,
    val nonce: Long,
    val maxPriorityFeePerGas: BigInteger,
    val maxFeePerGas: BigInteger,
    val gasLimit: Long,
    val to: String,
    val value: BigInteger,
    val maxFee: BigInteger,
    val tokenRecipient: String? = null,
    val tokenAmount: BigInteger? = null,
)

const val NATIVE_TRANSFER_GAS = 21_000L
const val ERC20_TRANSFER_GAS = 100_000L

data class EvmGasTier(val label: String, val maxFee: BigInteger, val tip: BigInteger)

fun evmGasTiers(quoted: BigInteger, base: BigInteger, highTip: BigInteger = BigInteger.ZERO): List<EvmGasTier> {
    val quotedFee = quoted.coerceAtLeast(BigInteger.ZERO)
    val baseFee = base.coerceAtLeast(BigInteger.ZERO)
    val standard = when {
        quotedFee.signum() > 0 && quotedFee >= baseFee -> quotedFee
        baseFee.signum() > 0 -> baseFee
        else -> BigInteger.ONE
    }
    val standardTip = if (baseFee.signum() == 0 || standard <= baseFee) standard else standard.subtract(baseFee)
    val economyTarget = standard.multiply(BigInteger.valueOf(4)).divide(BigInteger.valueOf(5))
    val economy = when {
        economyTarget.signum() == 0 -> BigInteger.ONE.min(standard)
        baseFee.signum() > 0 && economyTarget < baseFee -> baseFee.min(standard)
        else -> economyTarget
    }.coerceAtMost(standard).coerceAtLeast(BigInteger.ONE)
    val economyTip = if (baseFee.signum() == 0 || economy <= baseFee) economy.min(standardTip) else economy.subtract(baseFee)
    val fastTarget = standard.multiply(BigInteger.valueOf(5)).divide(BigInteger.valueOf(4)).max(standard.add(BigInteger.ONE))
    val raised = baseFee.add(highTip.coerceAtLeast(BigInteger.ZERO))
    val fast = if (raised > fastTarget) raised else fastTarget
    val fastTip = if (baseFee.signum() == 0 || fast <= baseFee) fast else fast.subtract(baseFee)
    return listOf(
        EvmGasTier("经济", economy, economyTip.min(economy)),
        EvmGasTier("标准", standard, standardTip.min(standard)),
        EvmGasTier("优先", fast, fastTip.min(fast)),
    )
}

fun NetworkId.ethChainId(): Long = when (this) {
    NetworkId.ETHEREUM_MAINNET -> 1L
    NetworkId.ETHEREUM_SEPOLIA -> 11155111L
    else -> throw WalletException("不是以太坊网络")
}

fun importEthPrivateKey(text: String): ImportedKey {
    val trimmed = text.trim()
    if (trimmed.any { it.isWhitespace() }) throw WalletException("请粘贴十六进制私钥，不是助记词")
    val hex = trimmed.removePrefix("0x").removePrefix("0X")
    if (hex.length != 64) throw WalletException("EVM 私钥必须是 64 位十六进制")
    val priv = hex.hexToBytes()
    Secp256k1.checkPriv(priv)
    return ImportedKey(priv, Secp256k1.uncompressedPub(priv), ethAddress(priv))
}

fun ethAddress(priv: ByteArray): String {
    val uncompressed = Secp256k1.uncompressedPub(priv)
    val hash = keccak256(uncompressed.copyOfRange(1, 65))
    return toEip55(hash.copyOfRange(12, 32))
}

fun normalizeEthAddress(input: String): String {
    val trimmed = input.trim()
    val hex = trimmed.removePrefix("0x").removePrefix("0X")
    if (!hex.matches(Regex("[0-9a-fA-F]{40}"))) throw WalletException("以太坊地址无效")
    val checksummed = toEip55(hex.lowercase().hexToBytes())
    val mixed = hex.any { it.isLetter() && it.isLowerCase() } && hex.any { it.isLetter() && it.isUpperCase() }
    if (mixed && checksummed.removePrefix("0x") != hex) throw WalletException("地址校验和错误，请核对大小写")
    return checksummed
}

fun erc20TransferData(recipient: ByteArray, amount: BigInteger): ByteArray {
    if (recipient.size != 20) throw WalletException("收款地址无效")
    if (amount.signum() < 0) throw WalletException("金额无效")
    return "a9059cbb".hexToBytes() + ByteArray(12) + recipient + amount.toUnsigned32()
}

fun encodeUnsignedEth(tx: EthUnsigned): ByteArray {
    if (tx.to.size != 20) throw WalletException("收款地址无效")
    if (tx.data.isEmpty()) {
        if (tx.gasLimit != NATIVE_TRANSFER_GAS) throw WalletException("原生转账的 gasLimit 必须是 21000")
    } else {
        parseErc20Transfer(tx.data)
        if (tx.value.signum() != 0) throw WalletException("代币转账不能附带原生币")
        if (tx.gasLimit != ERC20_TRANSFER_GAS) throw WalletException("ERC-20 转账的 gasLimit 必须是 100000")
    }
    if (tx.maxFeePerGas < tx.maxPriorityFeePerGas) throw WalletException("maxFee 不能低于 priority fee")
    if (tx.maxFeePerGas.signum() <= 0) throw WalletException("手续费必须大于 0")
    return byteArrayOf(0x02) + Rlp.encode(unsignedList(tx))
}

fun reviewEthereum(unsigned: ByteArray, expectedChainId: Long): EthReview {
    if (unsigned.isEmpty() || (unsigned[0].toInt() and 0xff) != 0x02) throw WalletException("只接受 EIP-1559 交易")
    val list = Rlp.decode(unsigned.copyOfRange(1, unsigned.size)) as? Rlp.Item.List
        ?: throw WalletException("交易编码无法识别")
    if (list.items.size != 9) throw WalletException("这不是未签名交易")
    val chainId = Rlp.asUInt(list.items[0]).longValueExact()
    if (chainId != expectedChainId) throw WalletException("chainId 与当前网络不一致")
    val nonce = Rlp.asUInt(list.items[1]).longValueExact()
    val priority = Rlp.asUInt(list.items[2])
    val maxFee = Rlp.asUInt(list.items[3])
    val gasLimit = Rlp.asUInt(list.items[4]).longValueExact()
    val to = (list.items[5] as? Rlp.Item.Bytes)?.data ?: throw WalletException("收款地址无效")
    val value = Rlp.asUInt(list.items[6])
    val data = (list.items[7] as? Rlp.Item.Bytes)?.data ?: throw WalletException("data 字段无效")
    val access = list.items[8] as? Rlp.Item.List ?: throw WalletException("accessList 无效")
    if (to.size != 20) throw WalletException("拒绝创建合约")
    if (access.items.isNotEmpty()) throw WalletException("不接受 accessList")
    if (maxFee < priority || maxFee.signum() <= 0) throw WalletException("手续费无效")
    if (value.signum() < 0) throw WalletException("金额无效")
    val transfer = if (data.isEmpty()) {
        if (gasLimit != NATIVE_TRANSFER_GAS) throw WalletException("gasLimit 不是 21000，已拒绝")
        null
    } else {
        val parsed = parseErc20Transfer(data)
        if (value.signum() != 0) throw WalletException("代币转账不能附带原生币")
        if (gasLimit != ERC20_TRANSFER_GAS) throw WalletException("ERC-20 的 gasLimit 不是 100000，已拒绝")
        parsed
    }
    return EthReview(
        chainId = chainId,
        nonce = nonce,
        maxPriorityFeePerGas = priority,
        maxFeePerGas = maxFee,
        gasLimit = gasLimit,
        to = toEip55(to),
        value = value,
        maxFee = maxFee.multiply(BigInteger.valueOf(gasLimit)),
        tokenRecipient = transfer?.first,
        tokenAmount = transfer?.second,
    )
}

fun signEthereum(unsigned: ByteArray, priv: ByteArray): ByteArray {
    reviewEthereum(unsigned, ethChainIdOf(unsigned))
    val hash = keccak256(unsigned)
    val sig = Secp256k1.sign(hash, priv)
    val fields = (Rlp.decode(unsigned.copyOfRange(1, unsigned.size)) as Rlp.Item.List).items
    val signed = Rlp.encode(
        Rlp.Item.List(
            fields + listOf(
                Rlp.uint(sig.recId.toLong()),
                Rlp.uint(sig.r),
                Rlp.uint(sig.s),
            ),
        ),
    )
    return byteArrayOf(0x02) + signed
}

fun ethereumSender(signed: ByteArray): String {
    if (signed.isEmpty() || (signed[0].toInt() and 0xff) != 0x02) throw WalletException("只接受 EIP-1559 交易")
    val list = Rlp.decode(signed.copyOfRange(1, signed.size)) as? Rlp.Item.List
        ?: throw WalletException("交易编码无法识别")
    if (list.items.size != 12) throw WalletException("这不是已签名交易")
    val unsigned = byteArrayOf(0x02) + Rlp.encode(Rlp.Item.List(list.items.take(9)))
    reviewEthereum(unsigned, ethChainIdOf(unsigned))
    val recId = Rlp.asUInt(list.items[9]).toInt()
    val r = Rlp.asUInt(list.items[10])
    val s = Rlp.asUInt(list.items[11])
    val point = Secp256k1.recoverPoint(recId, r, s, keccak256(unsigned)) ?: throw WalletException("签名无法恢复出地址")
    val encoded = point.getEncoded(false)
    return toEip55(keccak256(encoded.copyOfRange(1, 65)).copyOfRange(12, 32))
}

fun unsignedFromSignedEth(signed: ByteArray): ByteArray {
    val list = Rlp.decode(signed.copyOfRange(1, signed.size)) as? Rlp.Item.List
        ?: throw WalletException("交易编码无法识别")
    if (list.items.size != 12) throw WalletException("这不是已签名交易")
    return byteArrayOf(0x02) + Rlp.encode(Rlp.Item.List(list.items.take(9)))
}

private fun unsignedList(tx: EthUnsigned): Rlp.Item.List = Rlp.Item.List(
    listOf(
        Rlp.uint(tx.chainId),
        Rlp.uint(tx.nonce),
        Rlp.uint(tx.maxPriorityFeePerGas),
        Rlp.uint(tx.maxFeePerGas),
        Rlp.uint(tx.gasLimit),
        Rlp.bytes(tx.to),
        Rlp.uint(tx.value),
        Rlp.bytes(tx.data),
        Rlp.Item.List(emptyList()),
    ),
)

private fun parseErc20Transfer(data: ByteArray): Pair<String, BigInteger> {
    if (data.size != 68 || !data.copyOfRange(0, 4).contentEquals("a9059cbb".hexToBytes())) {
        throw WalletException("只接受原生币转账或 ERC-20 transfer，其他合约调用已拒绝")
    }
    if (data.copyOfRange(4, 16).any { it != 0.toByte() }) throw WalletException("ERC-20 收款地址编码无效")
    val recipient = toEip55(data.copyOfRange(16, 36))
    val amount = BigInteger(1, data.copyOfRange(36, 68))
    if (amount.signum() <= 0) throw WalletException("代币数量必须大于 0")
    return recipient to amount
}

private fun BigInteger.toUnsigned32(): ByteArray {
    val raw = toByteArray()
    val body = if (raw.size == 33 && raw[0] == 0.toByte()) raw.copyOfRange(1, 33) else raw
    if (body.size > 32) throw WalletException("金额超出范围")
    val out = ByteArray(32)
    body.copyInto(out, 32 - body.size)
    return out
}

private fun ethChainIdOf(unsigned: ByteArray): Long {
    val list = Rlp.decode(unsigned.copyOfRange(1, unsigned.size)) as Rlp.Item.List
    return Rlp.asUInt(list.items[0]).longValueExact()
}

private fun toEip55(address20: ByteArray): String {
    val hex = address20.toHex()
    val hash = keccak256(hex.toByteArray(Charsets.US_ASCII)).toHex()
    val body = buildString {
        for (i in hex.indices) {
            val ch = hex[i]
            append(if (ch.isDigit() || hash[i].digitToInt(16) < 8) ch else ch.uppercaseChar())
        }
    }
    return "0x$body"
}
