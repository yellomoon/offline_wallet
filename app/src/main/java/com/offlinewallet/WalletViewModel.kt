package com.offlinewallet

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offlinewallet.core.Chain
import com.offlinewallet.core.Envelope
import com.offlinewallet.core.EthUnsigned
import com.offlinewallet.core.NetworkId
import com.offlinewallet.core.PayloadKind
import com.offlinewallet.core.SecretBox
import com.offlinewallet.core.Utxo
import com.offlinewallet.core.WalletException
import com.offlinewallet.core.BtcAddressType
import com.offlinewallet.core.addressToScript
import com.offlinewallet.core.bitcoinAddress
import com.offlinewallet.core.btcScriptType
import com.offlinewallet.core.base64ToBytes
import com.offlinewallet.core.bitcoinTxid
import com.offlinewallet.core.buildPsbt
import com.offlinewallet.core.decodePsbt
import com.offlinewallet.core.encodePsbt
import com.offlinewallet.core.encodeUnsignedEth
import com.offlinewallet.core.ethChainId
import com.offlinewallet.core.ethereumSender
import com.offlinewallet.core.extractRawBitcoin
import com.offlinewallet.core.hexToBytes
import com.offlinewallet.core.formatBtc
import com.offlinewallet.core.formatEth
import com.offlinewallet.core.formatGwei
import com.offlinewallet.core.formatSats
import com.offlinewallet.core.importEthPrivateKey
import com.offlinewallet.core.importWif
import com.offlinewallet.core.normalizeEthAddress
import com.offlinewallet.core.parseMinor
import com.offlinewallet.core.reviewBitcoin
import com.offlinewallet.core.reviewEthereum
import com.offlinewallet.core.sameUnsignedBitcoin
import com.offlinewallet.core.scriptToAddress
import com.offlinewallet.core.selectCoins
import com.offlinewallet.core.signBitcoin
import com.offlinewallet.core.signEthereum
import com.offlinewallet.core.toBase64
import com.offlinewallet.core.toHex
import com.offlinewallet.core.unsignedFromSignedEth
import com.offlinewallet.core.verifyBitcoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

data class StoredAccount(
    val address: String,
    val network: String? = null,
    val kind: String? = null,
    val note: String? = null,
    val salt: String? = null,
    val iv: String? = null,
    val ct: String? = null,
) {
    fun networkId(): NetworkId? = network?.let { runCatching { NetworkId.fromWire(it) }.getOrNull() }

    fun caption(): String {
        val net = networkId()?.title ?: "未标记网络"
        val type = BtcAddressType.fromWire(kind)?.title
            ?: if (networkId()?.chain == Chain.EVM) "以太坊地址" else null
        return if (type == null) net else "$net · $type"
    }
}

enum class Role { OFFLINE, ONLINE }

enum class ScanPurpose { ADDRESS, UNSIGNED, SIGNED }

sealed interface Screen {
    data object RolePick : Screen
    data object NetworkPick : Screen
    data object OfflineHome : Screen
    data object OnlineHome : Screen
    data object ImportKey : Screen
    data object ImportAddress : Screen
    data object Send : Screen
    data object Review : Screen
    data object ShowQr : Screen
    data class Scan(val purpose: ScanPurpose) : Screen
    data object Settings : Screen
    data object Result : Screen
}

class WalletViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("wallet", Context.MODE_PRIVATE)

    var screen by mutableStateOf<Screen>(Screen.RolePick)
    var role by mutableStateOf<Role?>(null)
    var network by mutableStateOf<NetworkId?>(null)
    var accounts by mutableStateOf<List<StoredAccount>>(emptyList())
    var selectedAddress by mutableStateOf<String?>(null)
    val address: String? get() = selectedAddress
    var endpoint by mutableStateOf("")
    var balance by mutableStateOf("—")
    var balanceNote by mutableStateOf("")
    var tiers by mutableStateOf<List<FeeChoice>>(emptyList())
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var info by mutableStateOf<String?>(null)
    var qrTitle by mutableStateOf("")
    var qrNote by mutableStateOf("")
    var qrPayload by mutableStateOf("")
    var reviewLines by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var needsFeeWarning by mutableStateOf(false)
    var resultTitle by mutableStateOf("")
    var resultBody by mutableStateOf("")
    var resultLink by mutableStateOf<String?>(null)
    var draftRole by mutableStateOf(Role.OFFLINE)
    var spaceLabel by mutableStateOf("主应用")
    var pendingImportNetwork by mutableStateOf(NetworkId.BITCOIN_TESTNET3)
    var pendingImportNote by mutableStateOf("")
    var hasAppPassword by mutableStateOf(false)

    private var reviewPayload = ""
    private var pendingUnsigned: String? = null
    private var scanReturn: Screen = Screen.OfflineHome
    private var refreshGen = 0
    private val endpoints = linkedMapOf<String, String>()
    private var btcUtxos: List<Utxo> = emptyList()
    private var btcSpendable = 0L
    private var ethBalance: BigInteger = BigInteger.ZERO
    private var ethNonce = 0L

    init {
        role = prefs.getString("role", null)?.let { runCatching { Role.valueOf(it) }.getOrNull() }
        loadEndpoints()
        val loaded = loadAccounts()
        val named = withDefaultNotes(loaded)
        accounts = named
        val saved = prefs.getString("selected", null)
        val restored = accounts.find { selectionToken(it) == saved } ?: accounts.firstOrNull()
        if (restored != null) applyAccount(restored, resetBalance = false) else {
            selectedAddress = null
            endpoint = ""
        }
        if (named != loaded) persistAccounts()
        spaceLabel = spaceName(app)
        hasAppPassword = AppPassword.exists(prefs)
        if (role == Role.OFFLINE && !hasAppPassword) draftRole = Role.OFFLINE
        screen = when {
            role == null -> Screen.RolePick
            role == Role.OFFLINE && !hasAppPassword -> Screen.NetworkPick
            else -> home()
        }
    }

    fun pickRole(value: Role) {
        draftRole = value
        error = null
        if (value == Role.OFFLINE) screen = Screen.NetworkPick else enterRole()
    }

    fun enterRole() {
        role = draftRole
        pendingUnsigned = null
        persist()
        screen = home()
        error = null
    }

    fun enterOffline(password: CharArray?, confirm: CharArray?) {
        val pass = password?.copyOf()
        val again = confirm?.copyOf()
        password?.fill('\u0000')
        confirm?.fill('\u0000')
        try {
            if (!hasAppPassword) {
                if (pass == null || again == null) throw WalletException("请设置密码")
                if (pass.size < 8) throw WalletException("密码至少 8 位")
                if (!pass.contentEquals(again)) throw WalletException("两次密码不一致")
                AppPassword.save(prefs, pass)
                hasAppPassword = true
            }
            draftRole = Role.OFFLINE
            role = Role.OFFLINE
            pendingUnsigned = null
            persist()
            screen = home()
            error = null
        } catch (e: WalletException) {
            error = e.message
        } finally {
            pass?.fill('\u0000')
            again?.fill('\u0000')
        }
    }

    fun previewAccounts(raw: String, chosen: NetworkId, kinds: Set<BtcAddressType>): List<Pair<String, String>> {
        if (chosen.chain == Chain.EVM) {
            val key = importEthPrivateKey(raw)
            key.priv.fill(0)
            return listOf("以太坊地址" to key.address)
        }
        if (kinds.isEmpty()) throw WalletException("至少选一种地址类型")
        val key = importWif(raw, chosen)
        val rows = kinds.map { type -> type.title to bitcoinAddress(key.priv, chosen, type) }
        key.priv.fill(0)
        return rows
    }

    fun saveKey(raw: String, chosen: NetworkId, kinds: Set<BtcAddressType>, note: String) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            error = null
            try {
                if (!hasAppPassword) throw WalletException("请先在断开网络时设置本机密码")
                val saved = withContext(Dispatchers.Default) {
                    val pass = AppPassword.load(prefs)
                    val label = note.trim().ifBlank { nextAccountLabel() }
                    try {
                    val created = when (chosen.chain) {
                        Chain.BITCOIN -> {
                            if (kinds.isEmpty()) throw WalletException("至少选一种地址类型")
                            val key = importWif(raw, chosen)
                            kinds.map { type -> type.title to bitcoinAddress(key.priv, chosen, type) to key }
                        }
                        Chain.EVM -> {
                            val key = importEthPrivateKey(raw)
                            listOf(("以太坊地址" to key.address) to key)
                        }
                    }
                    val key = created.first().second
                    val rows = created.map { it.first }
                    if (rows.any { row -> accounts.any { it.address.equals(row.second, true) && it.network == chosen.wire } }) {
                        key.priv.fill(0)
                        throw WalletException("选中的地址里有已经添加过的")
                    }
                    val blob = SecretBox.encrypt(key.priv, pass)
                    key.priv.fill(0)
                    rows.map { row ->
                        val kind = if (chosen.chain == Chain.BITCOIN) {
                            BtcAddressType.entries.first { it.title == row.first }.wire
                        } else {
                            null
                        }
                            StoredAccount(row.second, chosen.wire, kind, label, blob.salt.toBase64(), blob.iv.toBase64(), blob.ciphertext.toBase64())
                    }
                    } finally {
                        pass.fill('\u0000')
                    }
                }
                accounts = accounts + saved
                applyAccount(saved.first())
                persistAccounts()
                info = null
                screen = Screen.OfflineHome
            } catch (e: WalletException) {
                error = e.message
            } catch (e: Exception) {
                error = e.message ?: "保存失败"
            } finally {
                busy = false
            }
        }
    }

    fun importAddress(raw: String, chosen: NetworkId? = pendingImportNetwork, note: String? = pendingImportNote) {
        try {
            val text = raw.trim()
            if (looksLikePrivateKey(text)) throw WalletException("在线端不能导入私钥")
            val net: NetworkId
            val body: String
            if (text.startsWith("owtx1:")) {
                val env = Envelope.decode(text)
                if (env.kind != PayloadKind.ADDRESS) throw WalletException("这是交易报文，不是地址")
                net = env.network
                body = env.body.trim()
            } else {
                net = chosen ?: throw WalletException("请先选择网络")
                body = text
            }
            val canonical = canonicalAddress(body, net)
            if (accounts.any { it.address.equals(canonical, true) && it.network == net.wire }) {
                throw WalletException("这个地址已经在观察列表里")
            }
            val kind = if (net.chain == Chain.BITCOIN) btcScriptType(addressToScript(canonical, net)).wire else null
            val label = note?.trim().orEmpty().ifBlank { nextAccountLabel() }
            val account = StoredAccount(canonical, net.wire, kind, label)
            accounts = accounts + account
            applyAccount(account)
            persistAccounts()
            error = null
            info = null
            screen = Screen.OnlineHome
        } catch (e: WalletException) {
            error = e.message
        }
    }

    fun showAddressQr() {
        val current = address ?: return
        val net = network ?: return
        showQr("观察地址", "给另一台手机扫描。二维码里只有地址。", Envelope(1, PayloadKind.ADDRESS, net.chain, net, current).encode())
    }

    fun openScan(purpose: ScanPurpose) {
        scanReturn = screen
        error = null
        screen = Screen.Scan(purpose)
    }

    fun onScanned(purpose: ScanPurpose, text: String) {
        when (purpose) {
            ScanPurpose.ADDRESS -> importAddress(text)
            ScanPurpose.UNSIGNED -> openUnsigned(text)
            ScanPurpose.SIGNED -> acceptSigned(text)
        }
    }

    fun select(address: String, networkWire: String?) {
        val account = accounts.find { it.address == address && it.network == networkWire } ?: return
        if (selectedAddress == account.address && network?.wire == account.network) return
        applyAccount(account)
        prefs.edit().putString("selected", selectionToken(account)).commit()
        error = null
    }

    fun removeAccount(address: String, networkWire: String?) {
        val removingCurrent = selectedAddress == address && network?.wire == networkWire
        accounts = accounts.filterNot { it.address.equals(address, true) && it.network == networkWire }
        if (removingCurrent) {
            val next = accounts.firstOrNull()
            if (next == null) {
                selectedAddress = null
                network = null
                endpoint = ""
                clearChainState()
            } else {
                applyAccount(next)
            }
        }
        persistAccounts()
        info = "已移除"
        error = null
    }

    fun openUnsigned(raw: String) {
        try {
            if (accounts.isEmpty()) throw WalletException("请先导入私钥")
            val env = Envelope.decode(raw.trim())
            if (env.kind != PayloadKind.UNSIGNED) throw WalletException("请扫描未签名交易")
            if (env.chain == Chain.BITCOIN) {
                matchBitcoinAccount(decodePsbt(env.body.base64ToBytes()), env.network)
            } else {
                val matches = accounts.filter { it.network == env.network.wire }
                if (matches.isEmpty()) throw WalletException("这台手机没有这个网络的私钥")
                val chosen = matches.find { it.address == selectedAddress && network?.wire == env.network.wire } ?: matches.first()
                if (chosen.address != selectedAddress || network?.wire != chosen.network) {
                    applyAccount(chosen)
                    info = "已切换到这个网络的地址"
                }
            }
            reviewLines = when (env.chain) {
                Chain.BITCOIN -> bitcoinLines(env)
                Chain.EVM -> ethereumLines(env)
            }
            reviewPayload = env.encode()
            error = null
            screen = Screen.Review
        } catch (e: WalletException) {
            error = e.message
        }
    }

    fun sign(password: CharArray) {
        if (busy) {
            password.fill('\u0000')
            return
        }
        viewModelScope.launch {
            busy = true
            error = null
            try {
                val signed = withContext(Dispatchers.Default) {
                    val priv = SecretBox.decrypt(loadBlob(), password)
                    try {
                        val env = Envelope.decode(reviewPayload)
                        val body = when (env.chain) {
                            Chain.BITCOIN -> {
                                val psbt = decodePsbt(env.body.base64ToBytes())
                                reviewBitcoin(psbt, address!!, env.network)
                                encodePsbt(signBitcoin(psbt, priv)).toBase64()
                            }
                            Chain.EVM -> {
                                reviewEthereum(env.body.base64ToBytes(), env.network.ethChainId())
                                signEthereum(env.body.base64ToBytes(), priv).toBase64()
                            }
                        }
                        Envelope(1, PayloadKind.SIGNED, env.chain, env.network, body).encode()
                    } finally {
                        priv.fill(0)
                    }
                }
                showQr("已签名", "回到在线手机扫描，或把报文复制过去。私钥没有包含在里面。", signed)
            } catch (e: WalletException) {
                error = e.message
            } catch (e: Exception) {
                error = e.message ?: "签名失败"
            } finally {
                password.fill('\u0000')
                busy = false
            }
        }
    }

    fun refresh() {
        val net = network ?: return
        val addr = address ?: return
        if (role != Role.ONLINE) return
        val gen = ++refreshGen
        viewModelScope.launch {
            busy = true
            error = null
            try {
                val snapshot = withContext(Dispatchers.IO) { ChainApi(endpoint).load(net, addr) }
                if (gen != refreshGen || address != addr) return@launch
                balance = snapshot.primary
                balanceNote = snapshot.secondary
                tiers = snapshot.tiers
                btcUtxos = snapshot.utxos
                btcSpendable = snapshot.sats
                ethBalance = snapshot.wei
                ethNonce = snapshot.nonce
            } catch (e: WalletException) {
                if (gen == refreshGen) error = e.message
            } catch (e: Exception) {
                if (gen == refreshGen) error = e.message ?: "出错了"
            } finally {
                if (gen == refreshGen) busy = false
            }
        }
    }

    fun buildSend(toRaw: String, amountRaw: String, tierIndex: Int) {
        val tier = tiers.getOrNull(tierIndex)
        if (tier == null) {
            error = "请先刷新余额和手续费"
            return
        }
        work {
            val payload = withContext(Dispatchers.Default) {
                when (network!!.chain) {
                    Chain.BITCOIN -> buildBitcoin(toRaw, amountRaw, tier)
                    Chain.EVM -> buildEthereum(toRaw, amountRaw, tier)
                }
            }
            pendingUnsigned = payload
            showQr("未签名交易", "拿到离线手机上核对后再签名。在线端屏幕上的摘要不能代替离线确认。", payload)
        }
    }

    fun acceptSigned(raw: String) {
        work {
            val tx = withContext(Dispatchers.Default) { checkSigned(raw.trim()) }
            val txid = withContext(Dispatchers.IO) {
                val api = ChainApi(endpoint)
                when (network!!.chain) {
                    Chain.BITCOIN -> api.broadcastBitcoin(tx.raw)
                    Chain.EVM -> api.broadcastEthereum(tx.raw)
                }
            }
            pendingUnsigned = null
            resultTitle = "已广播"
            resultBody = txid.ifBlank { tx.txid }
            resultLink = explorerTx(network!!, resultBody)
            screen = Screen.Result
        }
    }

    fun endpointOf(network: NetworkId): String = endpointFor(network)

    fun updateEndpoint(network: NetworkId, raw: String) {
        val value = raw.trim().trimEnd('/')
        if (!value.startsWith("https://") && !value.startsWith("http://")) {
            error = "节点地址需要以 http:// 或 https:// 开头"
            return
        }
        endpoints[network.wire] = value
        if (this.network == network) endpoint = value
        prefs.edit().putString("endpoints", JSONObject(endpoints as Map<*, *>).toString()).commit()
        error = null
    }

    fun resetAll() {
        prefs.edit().clear().commit()
        role = null
        network = null
        accounts = emptyList()
        selectedAddress = null
        endpoint = ""
        pendingUnsigned = null
        balance = "—"
        balanceNote = ""
        tiers = emptyList()
        info = null
        error = null
        hasAppPassword = false
        screen = Screen.RolePick
    }

    fun back() {
        error = null
        screen = when (val current = screen) {
            is Screen.Scan -> scanReturn
            Screen.NetworkPick -> Screen.RolePick
            Screen.ImportKey -> Screen.OfflineHome
            Screen.ImportAddress, Screen.Send, Screen.Result -> Screen.OnlineHome
            Screen.Review -> Screen.OfflineHome
            Screen.ShowQr, Screen.Settings -> home()
            else -> current
        }
    }

    fun open(screen: Screen) {
        error = null
        this.screen = screen
    }

    fun clearMessage() {
        error = null
        info = null
    }

    private fun buildBitcoin(toRaw: String, amountRaw: String, tier: FeeChoice): String {
        val net = network!!
        val own = address!!
        val toScript = addressToScript(toRaw, net)
        val toAddress = scriptToAddress(toScript, net)
        val ownScript = addressToScript(own, net)
        val amount = minorSats(amountRaw)
        val spend = BtcAddressType.fromWire(currentAccount()?.kind) ?: BtcAddressType.P2WPKH
        val plan = selectCoins(btcUtxos, amount, toScript.size, ownScript.size, tier.btcRate, spend)
        if (plan.inputs.sumOf { it.value } > btcSpendable && btcSpendable > 0) {
            throw WalletException("可花费余额不足")
        }
        val psbt = buildPsbt(plan, toScript, toAddress, ownScript, own)
        reviewBitcoin(psbt, own, net)
        return Envelope(1, PayloadKind.UNSIGNED, Chain.BITCOIN, net, encodePsbt(psbt).toBase64()).encode()
    }

    private fun buildEthereum(toRaw: String, amountRaw: String, tier: FeeChoice): String {
        val net = network!!
        val to = normalizeEthAddress(toRaw)
        val value = parseMinor(amountRaw, 18, "ETH")
        val cost = value.add(tier.maxFee.multiply(BigInteger.valueOf(21_000)))
        if (ethBalance < cost) throw WalletException("余额不足以支付金额和最高手续费")
        val unsigned = encodeUnsignedEth(
            EthUnsigned(
                chainId = net.ethChainId(),
                nonce = ethNonce,
                maxPriorityFeePerGas = tier.tip,
                maxFeePerGas = tier.maxFee,
                gasLimit = 21_000,
                to = to.removePrefix("0x").hexToBytes(),
                value = value,
            ),
        )
        reviewEthereum(unsigned, net.ethChainId())
        return Envelope(1, PayloadKind.UNSIGNED, Chain.EVM, net, unsigned.toBase64()).encode()
    }

    private fun checkSigned(raw: String): SignedTx {
        val env = Envelope.decode(raw)
        if (env.kind != PayloadKind.SIGNED) throw WalletException("请扫描已签名交易")
        val pending = pendingUnsigned ?: throw WalletException("请先在这台手机上生成未签名交易")
        val original = Envelope.decode(pending)
        if (env.network != original.network) throw WalletException("网络不一致")
        val watch = accounts.find { it.network == env.network.wire } ?: throw WalletException("这台手机没有这个网络的观察地址")
        if (network?.wire != watch.network) applyAccount(watch)
        return when (env.chain) {
            Chain.BITCOIN -> {
                val signed = decodePsbt(env.body.base64ToBytes())
                val unsigned = decodePsbt(original.body.base64ToBytes())
                if (!sameUnsignedBitcoin(unsigned, signed)) throw WalletException("已签名交易和刚才生成的不一致")
                verifyBitcoin(signed)
                SignedTx(extractRawBitcoin(signed).toHex(), bitcoinTxid(signed))
            }
            Chain.EVM -> {
                val signedBytes = env.body.base64ToBytes()
                if (!unsignedFromSignedEth(signedBytes).contentEquals(original.body.base64ToBytes())) {
                    throw WalletException("已签名交易和刚才生成的不一致")
                }
                val sender = ethereumSender(signedBytes)
                if (accounts.none { it.network == env.network.wire && it.address.equals(sender, true) }) {
                    throw WalletException("签名者与观察地址不一致，已拒绝广播")
                }
                SignedTx(signedBytes.toHex(), sender)
            }
        }
    }

    private fun bitcoinLines(env: Envelope): List<Pair<String, String>> {
        val review = reviewBitcoin(decodePsbt(env.body.base64ToBytes()), address!!, env.network)
        needsFeeWarning = review.feeRate.toDouble() > 200.0
        val lines = mutableListOf(
            "网络" to env.network.title,
            "付款地址" to address!!,
            "输入" to "${review.inputCount} 笔 · ${formatSats(review.inputSum)} 聪",
        )
        review.outputs.forEach { output ->
            val mine = output.address.equals(address, true)
            val label = when {
                !mine -> "收款地址"
                review.foreignCount == 0 -> "转给自己"
                else -> "找零地址"
            }
            lines += label to output.address
            lines += "金额" to "${formatBtc(output.value)} ${env.network.unit} · ${formatSats(output.value)} 聪"
        }
        lines += "手续费" to "${formatSats(review.fee)} 聪"
        lines += "预估费率" to "${review.feeRate} sat/vB"
        return lines
    }

    private fun ethereumLines(env: Envelope): List<Pair<String, String>> {
        val bytes = env.body.base64ToBytes()
        val review = reviewEthereum(bytes, env.network.ethChainId())
        needsFeeWarning = review.value.signum() > 0 && review.maxFee > review.value
        return listOf(
            "网络" to "${env.network.title} · ${review.chainId}",
            "签名者" to address!!,
            "收款地址" to review.to,
            "金额" to "${formatEth(review.value)} ETH",
            "nonce" to review.nonce.toString(),
            "gasLimit" to review.gasLimit.toString(),
            "maxFeePerGas" to "${formatGwei(review.maxFeePerGas)} Gwei",
            "priority fee" to "${formatGwei(review.maxPriorityFeePerGas)} Gwei",
            "最高手续费" to "${formatEth(review.maxFee)} ETH",
            "data" to "空",
        )
    }

    private fun canonicalAddress(raw: String, net: NetworkId): String = when (net.chain) {
        Chain.BITCOIN -> scriptToAddress(addressToScript(raw, net), net)
        Chain.EVM -> normalizeEthAddress(raw)
    }

    private fun matchBitcoinAccount(psbt: com.offlinewallet.core.Psbt, network: NetworkId) {
        val match = accounts.firstOrNull { account ->
            account.network == network.wire && runCatching { reviewBitcoin(psbt, account.address, network) }.isSuccess
        } ?: throw WalletException("这笔交易不属于本机任何地址")
        if (match.address != selectedAddress || this.network?.wire != match.network) {
            applyAccount(match)
            prefs.edit().putString("selected", selectionToken(match)).commit()
            info = "已切换到这笔交易对应的地址"
        }
    }

    private fun currentAccount(): StoredAccount? =
        accounts.find { it.address == selectedAddress && it.network == network?.wire }

    private fun applyAccount(account: StoredAccount, resetBalance: Boolean = true) {
        selectedAddress = account.address
        network = account.networkId()
        endpoint = endpointFor(network)
        if (resetBalance) clearChainState()
    }

    private fun clearChainState() {
        refreshGen++
        balance = "—"
        balanceNote = ""
        tiers = emptyList()
        btcUtxos = emptyList()
        btcSpendable = 0L
        ethBalance = BigInteger.ZERO
        ethNonce = 0L
    }

    private fun endpointFor(net: NetworkId?): String {
        if (net == null) return ""
        return endpoints[net.wire]?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: defaultEndpoint(net)
    }

    private fun loadEndpoints() {
        val legacyNetwork = prefs.getString("network", null)
        val legacyEndpoint = prefs.getString("endpoint", null)
        val raw = prefs.getString("endpoints", null)
        if (raw != null) {
            val obj = JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                endpoints[key] = obj.getString(key)
            }
        } else if (legacyNetwork != null && !legacyEndpoint.isNullOrBlank()) {
            endpoints[legacyNetwork] = legacyEndpoint
        }
    }

    private fun showQr(title: String, note: String, payload: String) {
        qrTitle = title
        qrNote = note
        qrPayload = payload
        screen = Screen.ShowQr
    }

    private fun home(): Screen = if (role == Role.OFFLINE) Screen.OfflineHome else Screen.OnlineHome

    private fun persist() {
        prefs.edit()
            .putString("role", role?.name)
            .putString("network", network?.wire)
            .putString("endpoint", endpoint)
            .putString("accounts", encodeAccounts(accounts))
            .putString("selected", currentAccount()?.let(::selectionToken) ?: selectedAddress)
            .putString("endpoints", JSONObject(endpoints as Map<*, *>).toString())
            .commit()
    }

    private fun persistAccounts() {
        prefs.edit()
            .putString("accounts", encodeAccounts(accounts))
            .putString("selected", currentAccount()?.let(::selectionToken) ?: selectedAddress)
            .putString("endpoints", JSONObject(endpoints as Map<*, *>).toString())
            .remove("address")
            .remove("salt")
            .remove("iv")
            .remove("ct")
            .commit()
    }

    private fun loadAccounts(): List<StoredAccount> {
        val raw = prefs.getString("accounts", null)
        if (raw != null) {
            val legacyNetwork = prefs.getString("network", null)
            return decodeAccounts(raw).map { account ->
                if (account.network != null) account
                else account.copy(
                    network = legacyNetwork,
                    kind = account.kind ?: if (legacyNetwork?.startsWith("bitcoin") == true) BtcAddressType.P2WPKH.wire else null,
                )
            }
        }
        val legacy = prefs.getString("address", null) ?: return emptyList()
        val legacyNetwork = prefs.getString("network", null)
        val migrated = listOf(
            StoredAccount(
                address = legacy,
                network = legacyNetwork,
                kind = if (legacyNetwork?.startsWith("bitcoin") == true) BtcAddressType.P2WPKH.wire else null,
                salt = prefs.getString("salt", null),
                iv = prefs.getString("iv", null),
                ct = prefs.getString("ct", null),
            ),
        )
        prefs.edit()
            .putString("accounts", encodeAccounts(migrated))
            .putString("selected", legacy)
            .remove("address")
            .remove("salt")
            .remove("iv")
            .remove("ct")
            .commit()
        return migrated
    }

    private fun loadBlob(): SecretBox.Blob {
        val account = currentAccount()
            ?: throw WalletException("请先选择要签名的地址")
        val salt = account.salt ?: throw WalletException("这把私钥不在这台手机上")
        val iv = account.iv ?: throw WalletException("这把私钥不在这台手机上")
        val ct = account.ct ?: throw WalletException("这把私钥不在这台手机上")
        return SecretBox.Blob(salt.base64ToBytes(), iv.base64ToBytes(), ct.base64ToBytes())
    }

    private fun work(block: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            error = null
            try {
                block()
            } catch (e: WalletException) {
                error = e.message
            } catch (e: Exception) {
                error = e.message ?: "出错了"
            } finally {
                busy = false
            }
        }
    }

    private data class SignedTx(val raw: String, val txid: String)
}

private fun encodeAccounts(list: List<StoredAccount>): String {
    val array = JSONArray()
    list.forEach { account ->
        val obj = JSONObject()
        obj.put("address", account.address)
        account.network?.let { obj.put("network", it) }
        account.kind?.let { obj.put("kind", it) }
        account.note?.let { obj.put("note", it) }
        account.salt?.let { obj.put("salt", it) }
        account.iv?.let { obj.put("iv", it) }
        account.ct?.let { obj.put("ct", it) }
        array.put(obj)
    }
    return array.toString()
}

private fun decodeAccounts(raw: String): List<StoredAccount> {
    val array = JSONArray(raw)
    return List(array.length()) { index ->
        val obj = array.getJSONObject(index)
        StoredAccount(
            address = obj.getString("address"),
            network = obj.optString("network").ifBlank { null },
            kind = obj.optString("kind").ifBlank { null },
            note = obj.optString("note").ifBlank { null },
            salt = obj.optString("salt").ifBlank { null },
            iv = obj.optString("iv").ifBlank { null },
            ct = obj.optString("ct").ifBlank { null },
        )
    }
}

private fun WalletViewModel.nextAccountLabel(): String {
    val numbers = accounts.mapNotNull { account ->
        Regex("^账户(\\d+)$").matchEntire(account.note.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
    }
    return "账户${(numbers.maxOrNull() ?: 0) + 1}"
}

private fun withDefaultNotes(list: List<StoredAccount>): List<StoredAccount> {
    var next = 1
    val assigned = linkedMapOf<String, String>()
    return list.map { account ->
        val existing = account.note?.trim().orEmpty()
        if (existing.isNotEmpty()) {
            val number = Regex("^账户(\\d+)$").matchEntire(existing)?.groupValues?.get(1)?.toIntOrNull()
            if (number != null && number >= next) next = number + 1
            if (existing == account.note) account else account.copy(note = existing)
        } else {
            val group = account.salt ?: "${account.network.orEmpty()}|${account.address}"
            val note = assigned.getOrPut(group) { "账户${next++}" }
            account.copy(note = note)
        }
    }
}

private fun selectionToken(account: StoredAccount): String = "${account.network.orEmpty()}|${account.address}"

private fun spaceName(context: Context): String {
    if (android.os.Process.myUid() / 100000 != 0) return "分身"
    val dir = context.filesDir.absolutePath.replace('\\', '/')
    val pkg = context.packageName
    val main = dir.endsWith("/data/user/0/$pkg/files") || dir.endsWith("/data/data/$pkg/files")
    return if (main) "主应用" else "分身"
}

private fun looksLikePrivateKey(text: String): Boolean {
    if (text.startsWith("owtx1:")) return false
    val hex = text.removePrefix("0x").removePrefix("0X")
    if (hex.matches(Regex("[0-9a-fA-F]{64}"))) return true
    if (text.length in 50..52 && text.first() in "5KLc9") {
        return runCatching { com.offlinewallet.core.Base58.decodeCheck(text) }.isSuccess
    }
    return false
}

private fun minorSats(text: String): Long {
    val value = parseMinor(text, 8, "BTC")
    if (value.bitLength() > 62) throw WalletException("金额超出范围")
    return value.toLong()
}

fun defaultEndpoint(network: NetworkId): String = when (network) {
    NetworkId.BITCOIN_MAINNET -> "https://blockstream.info/api"
    NetworkId.BITCOIN_TESTNET3 -> "https://blockstream.info/testnet/api"
    NetworkId.ETHEREUM_MAINNET -> "https://ethereum.publicnode.com"
    NetworkId.ETHEREUM_SEPOLIA -> "https://ethereum-sepolia-rpc.publicnode.com"
}

fun explorerTx(network: NetworkId, txid: String): String = when (network) {
    NetworkId.BITCOIN_MAINNET -> "https://mempool.space/tx/$txid"
    NetworkId.BITCOIN_TESTNET3 -> "https://mempool.space/testnet/tx/$txid"
    NetworkId.ETHEREUM_MAINNET -> "https://etherscan.io/tx/$txid"
    NetworkId.ETHEREUM_SEPOLIA -> "https://sepolia.etherscan.io/tx/$txid"
}
