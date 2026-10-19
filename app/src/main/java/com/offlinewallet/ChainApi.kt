package com.offlinewallet

import com.offlinewallet.core.Chain
import com.offlinewallet.core.NetworkId
import com.offlinewallet.core.Utxo
import com.offlinewallet.core.WalletException
import com.offlinewallet.core.ethChainId
import com.offlinewallet.core.formatBalance
import com.offlinewallet.core.formatBtc
import com.offlinewallet.core.formatGwei
import com.offlinewallet.core.formatSats
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
        Chain.EVM -> loadEthereum(address, network)
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

    private fun loadEthereum(address: String, network: NetworkId): ChainSnapshot {
        val chainId = hexQty(rpcString("eth_chainId", JSONArray())).toLong()
        if (chainId != network.ethChainId()) throw WalletException("节点 chainId 是 $chainId，和所选网络不一致")
        val wei = hexQty(rpcString("eth_getBalance", JSONArray().put(address).put("latest")))
        val nonce = hexQty(rpcString("eth_getTransactionCount", JSONArray().put(address).put("pending"))).toLong()
        val fees = ethFees()
        return ChainSnapshot(
            primary = "${formatBalance(wei, 18)} ETH",
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
        val (base, tip) = try {
            val params = JSONArray().put("0x5").put("latest").put(JSONArray().put(50))
            val result = rpc("eth_feeHistory", params).getJSONObject("result")
            val bases = result.getJSONArray("baseFeePerGas")
            val base = hexQty(bases.getString(bases.length() - 1))
            val rewards = result.optJSONArray("reward")
            val tip = if (rewards != null && rewards.length() > 0) {
                hexQty(rewards.getJSONArray(rewards.length() - 1).getString(0))
            } else {
                BigInteger.valueOf(1_000_000_000)
            }
            base to tip.coerceAtLeast(BigInteger.valueOf(1_000_000_000))
        } catch (_: WalletException) {
            val gas = hexQty(rpcString("eth_gasPrice", JSONArray()))
            BigInteger.ZERO to gas.coerceAtLeast(BigInteger.valueOf(1_000_000_000))
        }
        fun choice(label: String, tipMul: Long, baseMul: Long): FeeChoice {
            val priority = tip.multiply(BigInteger.valueOf(tipMul))
            val max = base.multiply(BigInteger.valueOf(baseMul)).add(priority).max(priority)
            return FeeChoice(label, "最高 ${formatGwei(max)} Gwei", tip = priority, maxFee = max)
        }
        return listOf(choice("经济", 1, 1), choice("标准", 1, 2), choice("优先", 2, 2))
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
