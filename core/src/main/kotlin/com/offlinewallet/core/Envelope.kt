package com.offlinewallet.core

import java.util.Base64

enum class Chain { BITCOIN, EVM }

enum class NetworkId(
    val wire: String,
    val chain: Chain,
    val title: String,
    val unit: String,
) {
    BITCOIN_MAINNET("bitcoin-mainnet", Chain.BITCOIN, "Bitcoin 主网", "BTC"),
    BITCOIN_TESTNET3("bitcoin-testnet3", Chain.BITCOIN, "Bitcoin Testnet3", "BTC"),
    ETHEREUM_MAINNET("ethereum-mainnet", Chain.EVM, "Ethereum 主网", "ETH"),
    ETHEREUM_SEPOLIA("ethereum-sepolia", Chain.EVM, "Ethereum Sepolia", "ETH"),
    ;

    val testNet: Boolean
        get() = this == BITCOIN_TESTNET3 || this == ETHEREUM_SEPOLIA

    companion object {
        fun fromWire(value: String): NetworkId =
            entries.firstOrNull { it.wire == value } ?: throw WalletException("不认识的网络")
    }
}

enum class PayloadKind { ADDRESS, UNSIGNED, SIGNED }

data class ChainRef(
    val wire: String,
    val chain: Chain,
    val title: String,
    val unit: String,
    val chainId: Long = 0,
) {
    fun asNetworkId(): NetworkId = NetworkId.fromWire(wire)

    companion object {
        fun fromWire(value: String): ChainRef {
            NetworkId.entries.firstOrNull { it.wire == value }?.let { return it.asChain() }
            if (value.startsWith("evm-")) {
                val id = value.removePrefix("evm-").toLongOrNull()
                if (id != null && id > 0L) return ChainRef(value, Chain.EVM, "EVM $id", nativeUnit(id), id)
            }
            throw WalletException("不认识的网络")
        }
    }
}

fun NetworkId.asChain(): ChainRef = ChainRef(
    wire = wire,
    chain = chain,
    title = title,
    unit = unit,
    chainId = if (chain == Chain.EVM) ethChainId() else 0L,
)

fun nativeUnit(chainId: Long): String = when (chainId) {
    56L, 97L -> "BNB"
    137L -> "POL"
    43114L -> "AVAX"
    else -> "ETH"
}

data class Envelope(
    val version: Int,
    val kind: PayloadKind,
    val chain: Chain,
    val network: ChainRef,
    val body: String,
) {
    fun encode(): String {
        if (version != 1) throw WalletException("报文版本不受支持")
        if (chain != network.chain) throw WalletException("链和网络不匹配")
        val json = buildString {
            append("{\"v\":1,\"kind\":\"")
            append(kind.name.lowercase())
            append("\",\"chain\":\"")
            append(if (chain == Chain.BITCOIN) "bitcoin" else "evm")
            append("\",\"network\":\"")
            append(network.wire)
            append("\",\"body\":\"")
            append(jsonEscape(body))
            append("\"}")
        }
        return "owtx1:$json"
    }

    companion object {
        fun decode(raw: String): Envelope {
            val text = raw.trim().removePrefix("\uFEFF")
            if (!text.startsWith("owtx1:")) throw WalletException("这不是本应用的二维码")
            val fields = parseFlatJson(text.removePrefix("owtx1:"))
            val version = fields["v"]?.toIntOrNull() ?: throw WalletException("报文缺少版本")
            if (version != 1) throw WalletException("报文版本不受支持，请升级应用")
            val kind = when (fields["kind"]) {
                "address" -> PayloadKind.ADDRESS
                "unsigned" -> PayloadKind.UNSIGNED
                "signed" -> PayloadKind.SIGNED
                else -> throw WalletException("报文类型不受支持")
            }
            val chain = when (fields["chain"]) {
                "bitcoin" -> Chain.BITCOIN
                "evm" -> Chain.EVM
                else -> throw WalletException("报文里的链不受支持")
            }
            val network = ChainRef.fromWire(fields["network"] ?: throw WalletException("报文缺少网络"))
            if (network.chain != chain) throw WalletException("链和网络不匹配")
            val body = fields["body"] ?: throw WalletException("报文缺少正文")
            if (body.isEmpty()) throw WalletException("报文正文是空的")
            return Envelope(version, kind, chain, network, body)
        }
    }
}

fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)

fun String.base64ToBytes(): ByteArray = try {
    Base64.getMimeDecoder().decode(trim())
} catch (_: IllegalArgumentException) {
    throw WalletException("报文正文不是有效的 Base64")
}

private fun jsonEscape(value: String): String = buildString {
    for (ch in value) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(ch)
        }
    }
}

private fun parseFlatJson(json: String): Map<String, String> {
    var i = 0
    fun skip() {
        while (i < json.length && json[i].isWhitespace()) i++
    }
    fun fail(): Nothing = throw WalletException("报文格式无法识别")
    fun readString(): String {
        if (i >= json.length || json[i] != '"') fail()
        i++
        val out = StringBuilder()
        while (i < json.length) {
            val ch = json[i++]
            when (ch) {
                '"' -> return out.toString()
                '\\' -> {
                    if (i >= json.length) fail()
                    when (val escaped = json[i++]) {
                        '\\', '"' -> out.append(escaped)
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        else -> fail()
                    }
                }
                else -> out.append(ch)
            }
        }
        fail()
    }
    skip()
    if (i >= json.length || json[i] != '{') fail()
    i++
    val fields = linkedMapOf<String, String>()
    skip()
    if (i < json.length && json[i] == '}') return fields
    while (i < json.length) {
        skip()
        val key = readString()
        skip()
        if (i >= json.length || json[i] != ':') fail()
        i++
        skip()
        val value = if (i < json.length && json[i] == '"') {
            readString()
        } else {
            val start = i
            while (i < json.length && json[i] != ',' && json[i] != '}') i++
            json.substring(start, i).trim()
        }
        fields[key] = value
        skip()
        if (i < json.length && json[i] == ',') {
            i++
            continue
        }
        if (i < json.length && json[i] == '}') return fields
        fail()
    }
    fail()
}
