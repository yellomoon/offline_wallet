package com.offlinewallet

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offlinewallet.core.Chain
import com.offlinewallet.core.Envelope
import com.offlinewallet.core.EthUnsigned
import com.offlinewallet.core.ChainRef
import com.offlinewallet.core.ERC20_TRANSFER_GAS
import com.offlinewallet.core.NATIVE_TRANSFER_GAS
import com.offlinewallet.core.NetworkId
import com.offlinewallet.core.asChain
import com.offlinewallet.core.erc20TransferData
import com.offlinewallet.core.formatBalance
import com.offlinewallet.core.nativeUnit
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

data class EvmChainConfig(
    val wire: String,
    val title: String,
    val chainId: Long,
    val builtin: Boolean,
)

data class SavedToken(
    val network: String,
    val contract: String,
    val name: String,
    val symbol: String,
    val decimals: Int,
)

data class ChainChoice(
    val wire: String,
    val title: String,
    val chain: Chain,
    val testNet: Boolean,
    val detail: String,
)

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
    data object Nodes : Screen
    data object Tokens : Screen
    data object EvmNetworks : Screen
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
    var broadcasting by mutableStateOf(false)
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
    var pendingImportWire by mutableStateOf(NetworkId.BITCOIN_MAINNET.wire)
    var pendingImportNote by mutableStateOf("")
    var evmChains by mutableStateOf<List<EvmChainConfig>>(emptyList())
    var savedTokens by mutableStateOf<List<SavedToken>>(emptyList())
    var chainChoices by mutableStateOf<List<ChainChoice>>(emptyList())
    var networkFormEpoch by mutableStateOf(0)
    var tokenFormEpoch by mutableStateOf(0)
    var hasAppPassword by mutableStateOf(false)
    var updateBusy by mutableStateOf(false)
    var updateNotice by mutableStateOf<UpdateNotice?>(null)
    var selectedWire by mutableStateOf<String?>(null)
    var assetBalance by mutableStateOf("")
    var assetBalanceNote by mutableStateOf("")

    private var reviewPayload = ""
    private var pendingUnsigned: String? = null
    private var scanReturn: Screen = Screen.OfflineHome
    private var refreshGen = 0
    private val endpoints = linkedMapOf<String, String>()
    private var assetGen = 0
    private val customEvm = mutableListOf<EvmChainConfig>()
    private val hiddenEvm = mutableSetOf<String>()
    private var customChainId: Long? = null
    private var btcUtxos: List<Utxo> = emptyList()
    private var btcSpendable = 0L
    private var ethBalance: BigInteger = BigInteger.ZERO
    private var ethNonce = 0L

    init {
        role = prefs.getString("role", null)?.let { runCatching { Role.valueOf(it) }.getOrNull() }
        loadEndpoints()
        loadChains()
        loadTokens()
        seedPresets()
        migrateBscEndpoint()
        rebuildChains()
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

    fun previewAccounts(raw: String, wire: String, kinds: Set<BtcAddressType>): List<Pair<String, String>> {
        val chosen = choice(wire)
        if (chosen.chain == Chain.EVM) {
            val key = importEthPrivateKey(raw)
            key.priv.fill(0)
            return listOf("${chosen.title} 地址" to key.address)
        }
        if (kinds.isEmpty()) throw WalletException("至少选一种地址类型")
        val net = NetworkId.fromWire(wire)
        val key = importWif(raw, net)
        val rows = kinds.map { type -> type.title to bitcoinAddress(key.priv, net, type) }
        key.priv.fill(0)
        return rows
    }

    fun saveKey(raw: String, wire: String, kinds: Set<BtcAddressType>, note: String) {
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
                    val chosen = choice(wire)
                    val created = when (chosen.chain) {
                        Chain.BITCOIN -> {
                            if (kinds.isEmpty()) throw WalletException("至少选一种地址类型")
                            val net = NetworkId.fromWire(wire)
                            val key = importWif(raw, net)
                            kinds.map { type -> type.title to bitcoinAddress(key.priv, net, type) to key }
                        }
                        Chain.EVM -> {
                            val key = importEthPrivateKey(raw)
                            listOf(("${chosen.title} 地址" to key.address) to key)
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

    fun importAddress(raw: String, chosenWire: String? = pendingImportWire, note: String? = pendingImportNote) {
        try {
            val text = raw.trim()
            if (looksLikePrivateKey(text)) throw WalletException("在线端不能导入私钥")
            val wire: String
            val chain: Chain
            val body: String
            if (text.startsWith("owtx1:")) {
                val env = Envelope.decode(text)
                if (env.kind != PayloadKind.ADDRESS) throw WalletException("这是交易报文，不是地址")
                wire = env.network.wire
                chain = env.chain
                body = env.body.trim()
                if (wire == NetworkId.BITCOIN_TESTNET3.wire || wire == NetworkId.ETHEREUM_SEPOLIA.wire) {
                    throw WalletException("测试网络已去掉")
                }
            } else {
                val chosen = choice(chosenWire ?: throw WalletException("请先选择网络"))
                wire = chosen.wire
                chain = chosen.chain
                body = text
            }
            val canonical = when (chain) {
                Chain.BITCOIN -> canonicalAddress(body, NetworkId.fromWire(wire))
                Chain.EVM -> normalizeEthAddress(body)
            }
            if (accounts.any { it.address.equals(canonical, true) && it.network == wire }) {
                throw WalletException("这个地址已经在观察列表里")
            }
            val kind = if (chain == Chain.BITCOIN) btcScriptType(addressToScript(canonical, NetworkId.fromWire(wire))).wire else null
            val label = note?.trim().orEmpty().ifBlank { nextAccountLabel() }
            val account = StoredAccount(canonical, wire, kind, label)
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
        val net = network ?: run {
            error = "自定义网络的地址先直接复制"
            return
        }
        showQr("观察地址", "给另一台手机扫描。二维码里只有地址。", Envelope(1, PayloadKind.ADDRESS, net.chain, net.asChain(), current).encode())
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
        if (selectedAddress == account.address && selectedWire == account.network) return
        applyAccount(account)
        prefs.edit().putString("selected", selectionToken(account)).commit()
        error = null
    }

    fun removeAccount(address: String, networkWire: String?) {
        val removingCurrent = selectedAddress == address && selectedWire == networkWire
        accounts = accounts.filterNot { it.address.equals(address, true) && it.network == networkWire }
        if (removingCurrent) {
            val next = accounts.firstOrNull()
            if (next == null) {
                selectedAddress = null
                network = null
                selectedWire = null
                customChainId = null
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
                matchBitcoinAccount(decodePsbt(env.body.base64ToBytes()), env.network.asNetworkId())
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
                                reviewBitcoin(psbt, address!!, env.network.asNetworkId())
                                encodePsbt(signBitcoin(psbt, priv)).toBase64()
                            }
                            Chain.EVM -> {
                                reviewEthereum(env.body.base64ToBytes(), env.network.chainId)
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
        val addr = address ?: return
        val builtin = network
        val chainId = customChainId
        if (builtin == null && chainId == null) return
        if (role != Role.ONLINE) return
        val gen = ++refreshGen
        viewModelScope.launch {
            busy = true
            error = null
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    if (builtin != null) ChainApi(endpoint).load(builtin, addr) else ChainApi(endpoint).loadEvm(chainId!!, addr)
                }
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

    fun currentTokens(): List<SavedToken> = savedTokens.filter { it.network == selectedWire }

    fun isEvmAccount(): Boolean = network?.chain == Chain.EVM || customChainId != null

    fun nativeCoinSymbol(): String {
        val id = if (network?.chain == Chain.EVM) network?.ethChainId() else customChainId
        return if (id != null) nativeUnit(id) else network?.unit ?: ""
    }

    fun refreshAsset(contract: String?) {
        val gen = ++assetGen
        if (!isEvmAccount() || contract == null) {
            assetBalance = balance
            assetBalanceNote = ""
            return
        }
        val token = currentTokens().find { it.contract.equals(contract, true) } ?: return
        val holder = address ?: return
        val node = endpoint
        viewModelScope.launch {
            assetBalance = "正在查询"
            assetBalanceNote = ""
            try {
                val raw = withContext(Dispatchers.IO) { ChainApi(node).tokenBalance(token.contract, holder) }
                if (gen != assetGen) return@launch
                assetBalance = "${formatBalance(raw, token.decimals)} ${token.symbol}"
                assetBalanceNote = "手续费从 ${nativeCoinSymbol()} 余额扣除，当前 $balance"
            } catch (e: Exception) {
                if (gen != assetGen) return@launch
                assetBalance = "余额读取失败"
                assetBalanceNote = e.message ?: ""
            }
        }
    }

    fun buildSend(toRaw: String, amountRaw: String, tierIndex: Int, tokenContract: String? = null) {
        if (selectedWire == null) {
            error = "请先选择账户"
            return
        }
        if (tokenContract != null && !isEvmAccount()) {
            error = "只有 EVM 网络可以发送代币"
            return
        }
        val tier = tiers.getOrNull(tierIndex)
        if (tier == null) {
            error = "请先刷新余额和手续费"
            return
        }
        val token = if (tokenContract == null) {
            null
        } else {
            currentTokens().find { it.contract.equals(tokenContract, true) } ?: run {
                error = "这个网络没有这个代币"
                return
            }
        }
        work {
            if (token != null) {
                val raw = parseMinor(amountRaw, token.decimals, token.symbol)
                val held = withContext(Dispatchers.IO) { ChainApi(endpoint).tokenBalance(token.contract, address!!) }
                if (held < raw) throw WalletException("代币余额不足")
                val fee = tier.maxFee.multiply(BigInteger.valueOf(ERC20_TRANSFER_GAS))
                if (ethBalance < fee) throw WalletException("${nativeCoinSymbol()} 不足以支付手续费")
            }
            val payload = withContext(Dispatchers.Default) {
                when {
                    network?.chain == Chain.BITCOIN -> buildBitcoin(toRaw, amountRaw, tier)
                    isEvmAccount() -> buildEthereum(toRaw, amountRaw, tier, token)
                    else -> throw WalletException("请先选择账户")
                }
            }
            pendingUnsigned = payload
            showQr("未签名交易", "拿到离线手机上核对后再签名。在线端屏幕上的摘要不能代替离线确认。", payload)
        }
    }

    fun acceptSigned(raw: String) {
        if (broadcasting) return
        broadcasting = true
        error = null
        viewModelScope.launch {
            try {
                val tx = withContext(Dispatchers.Default) { checkSigned(raw.trim()) }
                val chain = network?.chain ?: if (customChainId != null) Chain.EVM else throw WalletException("请先选择账户")
                val txid = withContext(Dispatchers.IO) {
                    val api = ChainApi(endpoint)
                    when (chain) {
                        Chain.BITCOIN -> api.broadcastBitcoin(tx.raw)
                        Chain.EVM -> api.broadcastEthereum(tx.raw)
                    }
                }
                pendingUnsigned = null
                resultTitle = "已广播"
                resultBody = txid.ifBlank { tx.txid }
                resultLink = network?.let { explorerTx(it, resultBody) }
                screen = Screen.Result
            } catch (e: WalletException) {
                error = e.message
            } catch (e: Exception) {
                error = e.message ?: "出错了"
            } finally {
                broadcasting = false
            }
        }
    }

    fun endpointOf(wire: String): String = endpointForWire(wire)

    fun checkUpdate() {
        if (updateBusy) return
        if (role != Role.ONLINE) {
            updateNotice = UpdateNotice.Failed("离线手机不访问网络。请在在线那台检查更新。")
            return
        }
        viewModelScope.launch {
            updateBusy = true
            updateNotice = null
            try {
                val release = withContext(Dispatchers.IO) { AppUpdate.latest() }
                updateNotice = when {
                    release == null || !AppUpdate.isNewer(release.version, BuildConfig.VERSION_NAME) -> UpdateNotice.Latest
                    release.apkUrl.isNullOrBlank() -> UpdateNotice.Failed("发现新版本 ${release.version}，但发布里没有安装包")
                    else -> UpdateNotice.Available(release)
                }
            } catch (e: WalletException) {
                updateNotice = UpdateNotice.Failed(e.message ?: "检查更新失败")
            } catch (e: Exception) {
                updateNotice = UpdateNotice.Failed(e.message ?: "无法连接 GitHub")
            } finally {
                updateBusy = false
            }
        }
    }

    fun installUpdate() {
        val available = updateNotice as? UpdateNotice.Available ?: return
        val url = available.release.apkUrl ?: return
        if (updateBusy) return
        val app = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.packageManager.canRequestPackageInstalls()) {
            updateNotice = UpdateNotice.Failed("请先允许安装未知应用，然后再点更新")
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            return
        }
        viewModelScope.launch {
            updateBusy = true
            try {
                val file = java.io.File(app.cacheDir, "updates/offline-wallet-update.apk")
                withContext(Dispatchers.IO) { AppUpdate.download(url, file) }
                AppUpdate.install(app, file)
                updateNotice = null
            } catch (e: WalletException) {
                updateNotice = UpdateNotice.Failed(e.message ?: "下载失败")
            } catch (e: Exception) {
                updateNotice = UpdateNotice.Failed(e.message ?: "下载失败")
            } finally {
                updateBusy = false
            }
        }
    }

    fun dismissUpdate() {
        if (!updateBusy) updateNotice = null
    }

    fun isCurrent(account: StoredAccount): Boolean = account.address == selectedAddress && account.network == selectedWire

    fun chainTitle(wire: String?): String {
        if (wire.isNullOrBlank()) return "未标记网络"
        return chainChoices.find { it.wire == wire }?.title
            ?: runCatching { NetworkId.fromWire(wire).title }.getOrDefault("未标记网络")
    }

    fun accountCaption(account: StoredAccount): String {
        val net = chainTitle(account.network)
        val type = BtcAddressType.fromWire(account.kind)?.title
        return if (type == null) net else "$net · $type"
    }

    fun updateEndpoint(wire: String, raw: String) {
        try {
            val value = normalizeEndpoint(raw)
            endpoints[wire] = value
            if (selectedWire == wire) endpoint = value
            persistEndpoints()
            error = null
        } catch (e: WalletException) {
            error = e.message
        }
    }

    private fun rememberEvmChain(name: String, chainId: Long): String {
        if (chainId == 11155111L) throw WalletException("以太坊测试网已去掉")
        val wire = "evm-$chainId"
        if (customEvm.any { it.chainId == chainId }) throw WalletException("这个 Chain ID 已经添加过")
        customEvm += EvmChainConfig(wire, name, chainId, builtin = false)
        return wire
    }

    fun addOfflineEvmChain(title: String, chainIdText: String) {
        try {
            val name = title.trim()
            val chainId = chainIdText.trim().toLongOrNull()
            if (name.isEmpty()) throw WalletException("请填写网络名称")
            if (chainId == null || chainId <= 0L) throw WalletException("Chain ID 必须是正整数")
            if (chainId == 1L) throw WalletException("以太坊主网已经在列表里，不能重复添加")
            rememberEvmChain(name, chainId)
            rebuildChains()
            persistChains()
            networkFormEpoch++
            error = null
        } catch (e: WalletException) {
            error = e.message
        }
    }

    fun addEvmChain(title: String, chainIdText: String, endpoint: String) {
        if (busy) return
        val name = title.trim()
        val chainId = chainIdText.trim().toLongOrNull()
        viewModelScope.launch {
            busy = true
            error = null
            try {
                if (name.isEmpty()) throw WalletException("请填写网络名称")
                if (chainId == null || chainId <= 0L) throw WalletException("Chain ID 必须是正整数")
                if (chainId == 1L) throw WalletException("以太坊主网已经在列表里，不能重复添加")
                val node = normalizeEndpoint(endpoint)
                val seen = withContext(Dispatchers.IO) { ChainApi(node).evmChainId() }
                if (seen != chainId) throw WalletException("节点 chainId 是 $seen，和填写的不一致")
                val wire = rememberEvmChain(name, chainId)
                endpoints[wire] = node
                rebuildChains()
                persistChains()
                networkFormEpoch++
            } catch (e: WalletException) {
                error = e.message
            } catch (e: Exception) {
                error = e.message ?: "添加网络失败"
            } finally {
                busy = false
            }
        }
    }

    fun renameEvmChain(wire: String, title: String): Boolean {
        return try {
            if (wire == NetworkId.ETHEREUM_MAINNET.wire) throw WalletException("以太坊主网不能修改")
            val name = title.trim()
            if (name.isEmpty()) throw WalletException("请填写网络名称")
            val index = customEvm.indexOfFirst { it.wire == wire }
            if (index < 0) throw WalletException("这个网络不能修改")
            customEvm[index] = customEvm[index].copy(title = name)
            rebuildChains()
            persistChains()
            error = null
            true
        } catch (e: WalletException) {
            error = e.message
            false
        }
    }

    fun deleteEvmChain(wire: String) {
        if (wire == NetworkId.ETHEREUM_MAINNET.wire) {
            error = "以太坊主网不能删除"
            return
        }
        if (accounts.any { it.network == wire }) {
            error = "这个网络上还有账户，先移除账户"
            return
        }
        val builtin = wire == NetworkId.ETHEREUM_SEPOLIA.wire
        if (builtin) hiddenEvm += wire else customEvm.removeAll { it.wire == wire }
        savedTokens = savedTokens.filterNot { it.network == wire }
        endpoints.remove(wire)
        rebuildChains()
        persistChains()
        error = null
    }

    fun addToken(wire: String, contract: String) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            error = null
            try {
                val chain = evmChains.find { it.wire == wire } ?: throw WalletException("请选择 EVM 网络")
                val node = endpointForWire(chain.wire).ifBlank { throw WalletException("请先保存这个网络的节点") }
                val meta = withContext(Dispatchers.IO) { ChainApi(node).readErc20(contract) }
                if (savedTokens.any { it.network == wire && it.contract.equals(meta.contract, true) }) {
                    throw WalletException("这个代币已经添加过")
                }
                savedTokens = savedTokens + SavedToken(wire, meta.contract, meta.name, meta.symbol, meta.decimals)
                persistChains()
                tokenFormEpoch++
            } catch (e: WalletException) {
                error = e.message
            } catch (e: Exception) {
                error = e.message ?: "读取代币失败"
            } finally {
                busy = false
            }
        }
    }

    fun removeToken(network: String, contract: String) {
        savedTokens = savedTokens.filterNot { it.network == network && it.contract.equals(contract, true) }
        persistChains()
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
        customEvm.clear()
        hiddenEvm.clear()
        savedTokens = emptyList()
        selectedWire = null
        customChainId = null
        endpoints.clear()
        seedPresets()
        rebuildChains()
        screen = Screen.RolePick
    }

    fun back() {
        if (broadcasting) return
        error = null
        screen = when (val current = screen) {
            is Screen.Scan -> scanReturn
            Screen.NetworkPick -> Screen.RolePick
            Screen.ImportKey -> Screen.OfflineHome
            Screen.ImportAddress, Screen.Send, Screen.Result -> Screen.OnlineHome
            Screen.Review -> Screen.OfflineHome
            Screen.ShowQr, Screen.Settings -> home()
            Screen.Nodes, Screen.Tokens, Screen.EvmNetworks -> Screen.Settings
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
        return Envelope(1, PayloadKind.UNSIGNED, Chain.BITCOIN, net.asChain(), encodePsbt(psbt).toBase64()).encode()
    }

    private fun buildEthereum(toRaw: String, amountRaw: String, tier: FeeChoice, token: SavedToken?): String {
        val chainId = if (network?.chain == Chain.EVM) network!!.ethChainId() else customChainId ?: throw WalletException("不是 EVM 网络")
        val ref = ChainRef.fromWire(selectedWire ?: throw WalletException("请先选择账户"))
        val recipient = normalizeEthAddress(toRaw)
        val unsigned = if (token == null) {
            val value = parseMinor(amountRaw, 18, nativeUnit(chainId))
            val cost = value.add(tier.maxFee.multiply(BigInteger.valueOf(NATIVE_TRANSFER_GAS)))
            if (ethBalance < cost) throw WalletException("余额不足以支付金额和最高手续费")
            encodeUnsignedEth(
                EthUnsigned(
                    chainId = chainId,
                    nonce = ethNonce,
                    maxPriorityFeePerGas = tier.tip,
                    maxFeePerGas = tier.maxFee,
                    gasLimit = NATIVE_TRANSFER_GAS,
                    to = recipient.removePrefix("0x").hexToBytes(),
                    value = value,
                ),
            )
        } else {
            val raw = parseMinor(amountRaw, token.decimals, token.symbol)
            encodeUnsignedEth(
                EthUnsigned(
                    chainId = chainId,
                    nonce = ethNonce,
                    maxPriorityFeePerGas = tier.tip,
                    maxFeePerGas = tier.maxFee,
                    gasLimit = ERC20_TRANSFER_GAS,
                    to = token.contract.removePrefix("0x").hexToBytes(),
                    value = BigInteger.ZERO,
                    data = erc20TransferData(recipient.removePrefix("0x").hexToBytes(), raw),
                ),
            )
        }
        reviewEthereum(unsigned, chainId)
        return Envelope(1, PayloadKind.UNSIGNED, Chain.EVM, ref, unsigned.toBase64()).encode()
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
        val review = reviewBitcoin(decodePsbt(env.body.base64ToBytes()), address!!, env.network.asNetworkId())
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
        val review = reviewEthereum(bytes, env.network.chainId)
        val unit = nativeUnit(review.chainId)
        val networkTitle = chainTitle(env.network.wire).let { if (it == "未标记网络") env.network.title else it }
        val tokenRecipient = review.tokenRecipient
        if (tokenRecipient == null) {
            needsFeeWarning = review.value.signum() > 0 && review.maxFee > review.value
            return listOf(
                "网络" to "$networkTitle · ${review.chainId}",
                "类型" to "原生币",
                "签名者" to address!!,
                "收款地址" to review.to,
                "金额" to "${formatEth(review.value)} $unit",
                "nonce" to review.nonce.toString(),
                "gasLimit" to review.gasLimit.toString(),
                "maxFeePerGas" to "${formatGwei(review.maxFeePerGas)} Gwei",
                "priority fee" to "${formatGwei(review.maxPriorityFeePerGas)} Gwei",
                "最高手续费" to "${formatEth(review.maxFee)} $unit",
                "data" to "空",
            )
        }
        needsFeeWarning = false
        val saved = savedTokens.find { it.network == env.network.wire && it.contract.equals(review.to, true) }
        val amount = review.tokenAmount ?: BigInteger.ZERO
        val shown = if (saved == null) {
            "$amount 最小单位"
        } else {
            "${formatBalance(amount, saved.decimals)} ${saved.symbol} · $amount 最小单位"
        }
        return listOf(
            "网络" to "$networkTitle · ${review.chainId}",
            "类型" to "ERC-20 transfer",
            "签名者" to address!!,
            "代币合约" to review.to,
            "收款地址" to tokenRecipient,
            "数量" to shown,
            "附带原生币" to "0",
            "nonce" to review.nonce.toString(),
            "gasLimit" to review.gasLimit.toString(),
            "maxFeePerGas" to "${formatGwei(review.maxFeePerGas)} Gwei",
            "priority fee" to "${formatGwei(review.maxPriorityFeePerGas)} Gwei",
            "最高手续费" to "${formatEth(review.maxFee)} $unit",
            "data" to "transfer(address,uint256)",
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
        accounts.find { it.address == selectedAddress && it.network == selectedWire }

    private fun applyAccount(account: StoredAccount, resetBalance: Boolean = true) {
        selectedAddress = account.address
        selectedWire = account.network
        network = account.networkId()
        customChainId = if (network == null) customEvm.find { it.wire == account.network }?.chainId else null
        endpoint = endpointForWire(account.network)
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

    private fun choice(wire: String): ChainChoice {
        if (wire == NetworkId.BITCOIN_TESTNET3.wire || wire == NetworkId.ETHEREUM_SEPOLIA.wire) {
            throw WalletException("测试网络已去掉")
        }
        return chainChoices.find { it.wire == wire } ?: throw WalletException("请先选择网络")
    }

    private fun endpointForWire(wire: String?): String {
        if (wire.isNullOrBlank()) return ""
        val saved = endpoints[wire]?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        if (saved != null) return saved
        presetEndpoint(wire)?.let { return it }
        val builtin = runCatching { NetworkId.fromWire(wire) }.getOrNull() ?: return ""
        return defaultEndpoint(builtin)
    }

    private fun rebuildChains() {
        val evm = mutableListOf<EvmChainConfig>()
        evm += EvmChainConfig(NetworkId.ETHEREUM_MAINNET.wire, NetworkId.ETHEREUM_MAINNET.title, 1L, builtin = true)
        evm += customEvm
        evmChains = evm
        val choices = mutableListOf<ChainChoice>()
        choices += ChainChoice(NetworkId.BITCOIN_MAINNET.wire, NetworkId.BITCOIN_MAINNET.title, Chain.BITCOIN, false, "主网")
        evm.forEach { chain ->
            val test = chain.chainId != 1L && chain.builtin
            choices += ChainChoice(chain.wire, chain.title, Chain.EVM, test, "链 ID ${chain.chainId}")
        }
        chainChoices = choices
    }

    private fun seedPresets() {
        if (prefs.getBoolean("presets_v1", false)) return
        for (preset in presetChains) {
            val wire = "evm-${preset.chainId}"
            if (customEvm.none { it.chainId == preset.chainId }) {
                customEvm += EvmChainConfig(wire, preset.title, preset.chainId, builtin = false)
            }
            if (endpoints[wire].isNullOrBlank()) endpoints[wire] = preset.endpoint
        }
        val existing = savedTokens.toMutableList()
        for (token in presetTokens()) {
            if (existing.none { it.network == token.network && it.contract.equals(token.contract, true) }) {
                existing += token
            }
        }
        savedTokens = existing
        persistChains()
        prefs.edit().putBoolean("presets_v1", true).commit()
    }

    private fun migrateBscEndpoint() {
        if (endpoints["evm-56"] == "http://192.168.3.3:8547") {
            endpoints["evm-56"] = "https://rpc-bsc.blockmachine.io"
            persistEndpoints()
        }
    }

    private fun loadChains() {
        customEvm.clear()
        hiddenEvm.clear()
        prefs.getString("hiddenEvm", null)?.let { raw ->
            val array = JSONArray(raw)
            for (index in 0 until array.length()) hiddenEvm += array.getString(index)
        }
        prefs.getString("customEvm", null)?.let { raw ->
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val obj = array.getJSONObject(index)
                val chainId = obj.getLong("chainId")
                customEvm += EvmChainConfig("evm-$chainId", obj.getString("title"), chainId, builtin = false)
            }
        }
    }

    private fun loadTokens() {
        val raw = prefs.getString("tokens", null) ?: return
        val array = JSONArray(raw)
        savedTokens = List(array.length()) { index ->
            val obj = array.getJSONObject(index)
            SavedToken(
                network = obj.getString("network"),
                contract = obj.getString("contract"),
                name = obj.getString("name"),
                symbol = obj.getString("symbol"),
                decimals = obj.getInt("decimals"),
            )
        }
    }

    private fun persistChains() {
        val chains = JSONArray()
        customEvm.forEach { chain ->
            chains.put(JSONObject().put("title", chain.title).put("chainId", chain.chainId))
        }
        val hidden = JSONArray()
        hiddenEvm.forEach { hidden.put(it) }
        val tokenArray = JSONArray()
        savedTokens.forEach { token ->
            tokenArray.put(
                JSONObject()
                    .put("network", token.network)
                    .put("contract", token.contract)
                    .put("name", token.name)
                    .put("symbol", token.symbol)
                    .put("decimals", token.decimals),
            )
        }
        prefs.edit()
            .putString("customEvm", chains.toString())
            .putString("hiddenEvm", hidden.toString())
            .putString("tokens", tokenArray.toString())
            .putString("endpoints", JSONObject(endpoints as Map<*, *>).toString())
            .commit()
    }

    private fun persistEndpoints() {
        prefs.edit().putString("endpoints", JSONObject(endpoints as Map<*, *>).toString()).commit()
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
            .putString("network", selectedWire)
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

private fun normalizeEndpoint(raw: String): String {
    val value = raw.trim().trimEnd('/')
    if (!value.startsWith("https://") && !value.startsWith("http://")) {
        throw WalletException("节点地址需要以 http:// 或 https:// 开头")
    }
    return value
}

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

private data class PresetChain(val title: String, val chainId: Long, val endpoint: String)

private val presetChains = listOf(
    PresetChain("BNB Smart Chain", 56, "https://rpc-bsc.blockmachine.io"),
    PresetChain("Arbitrum One", 42161, "https://rpc-arbitrum.blockmachine.io"),
    PresetChain("Polygon Mainnet", 137, "https://polygon.gateway.tenderly.co"),
)

private fun presetEndpoint(wire: String): String? = presetChains.find { wire == "evm-${it.chainId}" }?.endpoint

private fun presetTokens(): List<SavedToken> = listOf(
    SavedToken(NetworkId.ETHEREUM_MAINNET.wire, normalizeEthAddress("0xdac17f958d2ee523a2206206994597c13d831ec7"), "Tether USD", "USDT", 6),
    SavedToken("evm-56", normalizeEthAddress("0x55d398326f99059ff775485246999027b3197955"), "Tether USD", "USDT", 18),
    SavedToken("evm-42161", normalizeEthAddress("0xaf88d065e77c8cc2239327c5edb3a432268e5831"), "USD Coin", "USDC", 6),
    SavedToken("evm-137", normalizeEthAddress("0x3c499c542cef5e3811e1192ce70d8cc03d5c3359"), "USD Coin", "USDC", 6),
)

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
