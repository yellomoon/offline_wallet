package com.offlinewallet

import com.offlinewallet.core.Chain
import com.offlinewallet.core.NetworkId
import com.offlinewallet.core.Utxo
import com.offlinewallet.core.WalletException
import com.offlinewallet.core.ethChainId
import com.offlinewallet.core.evmGasTiers
import com.offlinewallet.core.formatBalance
import com.offlinewallet.core.formatBtc
import com.offlinewallet.core.formatGwei
import com.offlinewallet.core.formatSats
import com.offlinewallet.core.hexToBytes
import com.offlinewallet.core.nativeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.util.Locale
import java.util.concurrent.TimeUnit

data class FeeChoice(
    val label: String,
    val detail: String,
    val btcRate: Double = 0.0,
    val tip: BigInteger = BigInteger.ZERO,
    val maxFee: BigInteger = BigInteger.ZERO,
)

data class Erc20Token(
    val contract: String,
    val name: String,
    val symbol: String,
    val decimals: Int,
)

data class ChainSnapshot(
    val primary: String,
    val secondary: String,
    val tiers: List<FeeChoice>,
    val utxos: List<Utxo>,
    val sats: Long,
    val wei: BigInteger,
    val nonce: Long,
)

class ChainApi(rawEndpoint: String) {
    private val endpoint = rawEndpoint.trim().trimEnd('/')
    private val http = OkHttpClient.Builder()
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    fun load(network: NetworkId, address: String): ChainSnapshot = when (network.chain) {
        Chain.BITCOIN -> loadBitcoin(address)
        Chain.EVM -> loadEthereum(address, network.ethChainId())
    }

    fun loadEvm(chainId: Long, address: String): ChainSnapshot = loadEthereum(address, chainId)

    fun evmChainId(): Long = hexQty(rpcString("eth_chainId", JSONArray())).toLong()

    fun tokenBalance(contract: String, holder: String): BigInteger {
        val who = com.offlinewallet.core.normalizeEthAddress(holder).removePrefix("0x").lowercase().padStart(64, '0')
        return com.offlinewallet.core.Erc20.decodeUint(ethCall(com.offlinewallet.core.normalizeEthAddress(contract), "70a08231$who"))
    }

    fun readErc20(contract: String): Erc20Token {
        val address = com.offlinewallet.core.normalizeEthAddress(contract)
        val name = com.offlinewallet.core.Erc20.decodeString(ethCall(address, com.offlinewallet.core.Erc20.NAME))
        val symbol = com.offlinewallet.core.Erc20.decodeString(ethCall(address, com.offlinewallet.core.Erc20.SYMBOL))
        val decimals = com.offlinewallet.core.Erc20.decodeUint(ethCall(address, com.offlinewallet.core.Erc20.DECIMALS)).toInt()
        if (decimals !in 0..36) throw WalletException("小数位数不合理")
        if (name.isBlank() || symbol.isBlank()) throw WalletException("合约没有返回名称或符号")
        return Erc20Token(address, name, symbol, decimals)
    }

    fun broadcastBitcoin(rawHex: String): String = postText("$endpoint/tx", rawHex)

    fun broadcastEthereum(rawHex: String): String {
        val hash = rpcString("eth_sendRawTransaction", JSONArray().put(if (rawHex.startsWith("0x")) rawHex else "0x$rawHex"))
        return hash.removePrefix("0x")
    }

    private fun loadBitcoin(address: String): ChainSnapshot {
        val utxoJson = JSONArray(get("$endpoint/address/$address/utxo"))
        val confirmed = ArrayList<Utxo>()
        var confirmedSum = 0L
        var pendingSum = 0L
        for (i in 0 until utxoJson.length()) {
            val item = utxoJson.getJSONObject(i)
            val value = item.getLong("value")
            val confirmedFlag = item.optJSONObject("status")?.optBoolean("confirmed") ?: true
            if (confirmedFlag) {
                confirmed += Utxo(item.getString("txid"), item.getInt("vout"), value)
                confirmedSum += value
            } else {
                pendingSum += value
            }
        }
        val rates = feeRates()
        val note = if (pendingSum != 0L) "另有未确认 ${formatBtc(pendingSum)} BTC，未计入可花费" else "可花费 ${formatSats(confirmedSum)} 聪"
        return ChainSnapshot(
            primary = "${formatBtc(confirmedSum)} BTC",
            secondary = note,
            tiers = listOf(
                FeeChoice("经济", rateLabel(rates.first), rates.first),
                FeeChoice("标准", rateLabel(rates.second), rates.second),
                FeeChoice("优先", rateLabel(rates.third), rates.third),
            ),
            utxos = confirmed,
            sats = confirmedSum,
            wei = BigInteger.ZERO,
            nonce = 0,
        )
    }

    private fun loadEthereum(address: String, expectedChainId: Long): ChainSnapshot {
        val chainId = evmChainId()
        if (chainId != expectedChainId) throw WalletException("节点 chainId 是 $chainId，和所选网络不一致")
        val wei = hexQty(rpcString("eth_getBalance", JSONArray().put(address).put("latest")))
        val nonce = hexQty(rpcString("eth_getTransactionCount", JSONArray().put(address).put("pending"))).toLong()
        val fees = ethFees()
        return ChainSnapshot(
            primary = "${formatBalance(wei, 18)} ${nativeUnit(expectedChainId)}",
            secondary = "下一笔 nonce $nonce",
            tiers = fees,
            utxos = emptyList(),
            sats = 0,
            wei = wei,
            nonce = nonce,
        )
    }

    private fun feeRates(): Triple<Double, Double, Double> {
        val json = JSONObject(get("$endpoint/fee-estimates"))
        val map = mutableMapOf<Int, Double>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            key.toIntOrNull()?.let { map[it] = json.getDouble(key) }
        }
        return Triple(pickRate(map, 25), pickRate(map, 6), pickRate(map, 2))
    }

    private fun ethFees(): List<FeeChoice> {
        val quoted = try {
            hexQty(rpcString("eth_gasPrice", JSONArray()))
        } catch (_: Exception) {
            BigInteger.ZERO
        }
        val sample = try {
            readFeeSample()
        } catch (_: Exception) {
            null
        }
        if (quoted.signum() == 0 && (sample == null || sample.first.signum() == 0)) {
            throw WalletException("节点没有返回 gas 价格")
        }
        return evmGasTiers(quoted, sample?.first ?: BigInteger.ZERO, sample?.second ?: BigInteger.ZERO).map { tier ->
            val text = "${formatGwei(tier.maxFee)} Gwei"
            val detail = if (tier.label == "标准") "当前网络 $text" else text
            FeeChoice(tier.label, detail, tip = tier.tip, maxFee = tier.maxFee)
        }
    }

    private fun readFeeSample(): Pair<BigInteger, BigInteger> {
        val percentiles = JSONArray().put(90)
        val result = try {
            rpc("eth_feeHistory", JSONArray().put("0x14").put("pending").put(percentiles)).getJSONObject("result")
        } catch (_: WalletException) {
            rpc("eth_feeHistory", JSONArray().put("0x14").put("latest").put(percentiles)).getJSONObject("result")
        }
        val bases = result.getJSONArray("baseFeePerGas")
        val base = if (bases.length() == 0) BigInteger.ZERO else hexQty(bases.getString(bases.length() - 1))
        val rewards = result.optJSONArray("reward")
        val tips = ArrayList<BigInteger>()
        if (rewards != null) {
            for (i in 0 until rewards.length()) {
                val row = rewards.optJSONArray(i) ?: continue
                if (row.length() > 0) tips += hexQty(row.getString(0))
            }
        }
        return base to medianWei(tips)
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "offline-wallet/1.0").build()
        return execute(request)
    }

    private fun postText(url: String, body: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "offline-wallet/1.0")
            .post(body.toRequestBody("text/plain".toMediaType()))
            .build()
        return execute(request).trim()
    }

    private fun ethCall(contract: String, selector: String): ByteArray {
        val call = JSONObject().put("to", contract).put("data", "0x$selector")
        val result = rpcString("eth_call", JSONArray().put(call).put("latest"))
        val hex = result.removePrefix("0x").removePrefix("0X")
        if (hex.isEmpty()) throw WalletException("合约没有返回数据")
        return hex.hexToBytes()
    }

    private fun rpcString(method: String, params: JSONArray): String = rpc(method, params).get("result").asRpcString()

    private fun rpc(method: String, params: JSONArray): JSONObject {
        val payload = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params)
        val request = Request.Builder()
            .url(endpoint)
            .header("User-Agent", "offline-wallet/1.0")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return JSONObject(execute(request))
    }

    private fun execute(request: Request): String {
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw WalletException(text.trim().take(280).ifBlank { "节点返回 ${response.code}" })
                }
                if (text.trimStart().startsWith("{") && text.contains("\"error\"")) {
                    val json = JSONObject(text)
                    if (json.has("error") && !json.isNull("error")) {
                        val message = json.optJSONObject("error")?.optString("message").orEmpty()
                        throw WalletException(message.ifBlank { "节点拒绝了请求" }.take(280))
                    }
                }
                return text
            }
        } catch (e: WalletException) {
            throw e
        } catch (_: IOException) {
            throw WalletException("无法连接节点")
        } catch (_: Exception) {
            throw WalletException("节点返回了无法识别的内容")
        }
    }
}

private fun medianWei(values: List<BigInteger>): BigInteger {
    if (values.isEmpty()) return BigInteger.ZERO
    val sorted = values.sorted()
    return sorted[sorted.size / 2]
}

private fun pickRate(map: Map<Int, Double>, target: Int): Double {
    map[target]?.let { return it.coerceAtLeast(1.0) }
    val above = map.keys.filter { it >= target }.minOrNull()
    val below = map.keys.filter { it <= target }.maxOrNull()
    return (map[above ?: below] ?: 5.0).coerceAtLeast(1.0)
}

private fun rateLabel(rate: Double): String = "约 ${"%.1f".format(Locale.US, rate)} sat/vB"

private fun hexQty(hex: String): BigInteger {
    val body = hex.removePrefix("0x").removePrefix("0X")
    if (body.isEmpty() || body.all { it == '0' }) return BigInteger.ZERO
    return BigInteger(body, 16)
}

private fun Any?.asRpcString(): String = when (this) {
    is String -> this
    null -> throw WalletException("节点没有返回结果")
    else -> toString()
}
