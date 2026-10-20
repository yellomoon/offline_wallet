package com.offlinewallet.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.offlinewallet.BuildConfig
import com.offlinewallet.UpdateNotice
import com.offlinewallet.Role
import com.offlinewallet.ScanPurpose
import com.offlinewallet.Screen
import com.offlinewallet.WalletViewModel
import com.offlinewallet.core.BtcAddressType
import com.offlinewallet.core.Chain
import com.offlinewallet.core.NetworkId as CoreNetwork
import kotlinx.coroutines.delay

@Composable
fun WalletApp(vm: WalletViewModel = viewModel()) {
    val accent = if (vm.role == Role.OFFLINE) Steel else Copper
    BackHandler(
        enabled = vm.screen !is Screen.RolePick && vm.screen !is Screen.OfflineHome && vm.screen !is Screen.OnlineHome,
    ) { vm.back() }
    Box(Modifier.fillMaxSize().background(Ink).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            BusyBar(vm.busy || vm.broadcasting, accent)
            when (val screen = vm.screen) {
                Screen.RolePick -> RoleScreen(vm)
                Screen.NetworkPick -> NetworkScreen(vm)
                Screen.OfflineHome -> OfflineHome(vm, accent)
                Screen.OnlineHome -> OnlineHome(vm, accent)
                Screen.ImportKey -> ImportKeyScreen(vm)
                Screen.ImportAddress -> ImportAddressScreen(vm)
                Screen.Send -> SendScreen(vm, accent)
                Screen.Review -> ReviewScreen(vm, accent)
                Screen.ShowQr -> QrScreen(vm, accent)
                is Screen.Scan -> ScanScreen(screen.purpose, vm)
                Screen.Settings -> SettingsScreen(vm)
                Screen.Nodes -> NodeManageScreen(vm)
                Screen.Tokens -> TokenManageScreen(vm)
                Screen.EvmNetworks -> EvmNetworkScreen(vm)
                Screen.Result -> ResultScreen(vm, accent)
            }
        }
    }
}

@Composable
private fun RoleScreen(vm: WalletViewModel) {
    ScreenColumn {
        Eyebrow("Offline Wallet")
        Gap(28)
        Headline("这台手机\n只做一件事")
        Gap(12)
        Body("私钥留在离线的那一边。在线的那一边只看地址，负责把交易发出去。可以用两台手机，也可以在同一台手机上打开系统的应用分身。两边的数据互不相通。")
        SpaceNotice(vm)
        Gap(28)
        ChoiceCard("离线签名", "导入私钥，显示地址，只签名。请保持飞行模式。", Steel) { vm.pickRole(Role.OFFLINE) }
        Gap(12)
        ChoiceCard("在线广播", "导入地址，查询余额，组装交易并广播。", Copper) { vm.pickRole(Role.ONLINE) }
    }
}

@Composable
private fun NetworkScreen(vm: WalletViewModel) {
    var armed by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    ScreenColumn {
        Eyebrow("离线端")
        Gap(20)
        Headline("先断开网络")
        Gap(8)
        Body("网络在添加每一把私钥时再选。不同主网可以同时留在这台手机上，互不混用。")
        Gap(18)
        ChoiceCard(
            if (armed) "已关闭网络" else "我已打开飞行模式",
            "同时关掉无线局域网和蓝牙。相机仍然可以扫码。",
            Steel,
            selected = armed,
        ) { armed = !armed }
        if (armed && !vm.hasAppPassword) {
            Gap(18)
            Body("设置这台手机的密码，只设这一次。之后添加私钥不用再填，签名时仍要输入这个密码。")
            Gap(12)
            WalletField(password, { password = it }, "密码，至少 8 位", visualTransformation = PasswordVisualTransformation(), keyboardType = KeyboardType.Password)
            Gap(10)
            WalletField(confirm, { confirm = it }, "再输入一次密码", visualTransformation = PasswordVisualTransformation(), keyboardType = KeyboardType.Password)
        }
        vm.error?.let { Gap(12); Notice(it, Danger) }
        Gap(20)
        PrimaryButton(
            "进入",
            enabled = armed && (vm.hasAppPassword || password.length >= 8),
        ) {
            if (vm.hasAppPassword) vm.enterOffline(null, null)
            else vm.enterOffline(password.toCharArray(), confirm.toCharArray())
        }
        QuietButton("返回", onClick = vm::back)
    }
}

private fun addressHint(testNet: Boolean, type: BtcAddressType): String = when (type) {
    BtcAddressType.P2WPKH -> if (testNet) "tb1q…" else "bc1q…"
    BtcAddressType.P2SH_P2WPKH -> if (testNet) "2…" else "3…"
    BtcAddressType.P2PKH -> if (testNet) "m… 或 n…" else "1…"
    BtcAddressType.P2TR -> if (testNet) "tb1p…" else "bc1p…"
}

@Composable
private fun OfflineHome(vm: WalletViewModel, accent: androidx.compose.ui.graphics.Color) {
    var query by remember { mutableStateOf("") }
    ScreenColumn {
        HomeHeader(vm, if (vm.accounts.isEmpty()) "还没有账户" else "账户列表")
        if (vm.accounts.size > 3) {
            Gap(4)
            WalletField(query, { query = it }, "搜索")
        }
        SpaceNotice(vm)
        Messages(vm)
        Notice("请确认这台手机处于飞行模式，并关闭无线局域网和蓝牙。", Steel)
        Gap(16)
        if (vm.accounts.isEmpty()) {
            Gap(8)
            Body("可以添加多把私钥。每把私钥选择网络和地址类型，并写一个备注。都用这台手机上已经设好的密码加密。")
            Gap(20)
            PrimaryButton("导入私钥") { vm.open(Screen.ImportKey) }
        } else {
            Body("点选一个账户。签名和出示二维码都用它，网络也跟这个账户走。")
            Gap(14)
            AccountList(vm, accent, offline = true, query = if (vm.accounts.size > 3) query else "")
            Gap(8)
            PrimaryButton("扫描未签名交易") { vm.openScan(ScanPurpose.UNSIGNED) }
            Gap(10)
            SecondaryButton("出示当前地址", onClick = vm::showAddressQr)
            Gap(10)
            SecondaryButton("添加私钥") { vm.open(Screen.ImportKey) }
        }
        Gap(8)
        QuietButton("设置") { vm.open(Screen.Settings) }
    }
}

@Composable
private fun OnlineHome(vm: WalletViewModel, accent: androidx.compose.ui.graphics.Color) {
    LaunchedEffect(vm.address, vm.selectedWire) {
        if (vm.address != null) vm.refresh()
    }
    ScreenColumn {
        HomeHeader(vm, if (vm.accounts.isEmpty()) "还没有账户" else "账户列表")
        SpaceNotice(vm)
        Messages(vm)
        Notice("这台手机只保存地址。不要在这里输入私钥。")
        Gap(16)
        if (vm.accounts.isEmpty()) {
            Gap(8)
            Body("可以添加多个地址。每个地址有自己的网络、地址类型和备注。")
            Gap(20)
            PrimaryButton("导入地址") { vm.open(Screen.ImportAddress) }
        } else {
            Body("点选一个作为当前地址。余额、转账和广播都针对这一笔。")
            Gap(14)
            AccountList(vm, accent, offline = false)
            Gap(8)
            Text(vm.balance, color = Ivory, fontSize = 32.sp, fontFamily = Mono)
            if (vm.balanceNote.isNotBlank()) {
                Gap(4)
                Body(vm.balanceNote, Muted)
            }
            Gap(18)
            PrimaryButton("转账", enabled = !vm.busy) { vm.open(Screen.Send) }
            Gap(10)
            SecondaryButton("扫描已签名交易") { vm.openScan(ScanPurpose.SIGNED) }
            Gap(10)
            SecondaryButton(if (vm.busy) "正在刷新" else "刷新余额") { vm.refresh() }
            Gap(10)
            SecondaryButton("添加地址") { vm.open(Screen.ImportAddress) }
        }
        Gap(8)
        QuietButton("设置") { vm.open(Screen.Settings) }
    }
}

@Composable
private fun ImportKeyScreen(vm: WalletViewModel) {
    var key by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(CoreNetwork.BITCOIN_MAINNET.wire) }
    var kinds by remember { mutableStateOf(setOf(BtcAddressType.P2WPKH)) }
    var preview by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var previewError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(vm.chainChoices) {
        if (vm.chainChoices.none { it.wire == chosen }) {
            chosen = vm.chainChoices.firstOrNull()?.wire ?: CoreNetwork.BITCOIN_MAINNET.wire
        }
    }
    LaunchedEffect(key, chosen, kinds) {
        if (key.isBlank()) {
            preview = emptyList()
            previewError = null
            return@LaunchedEffect
        }
        delay(180)
        runCatching { vm.previewAccounts(key, chosen, kinds) }
            .onSuccess { preview = it; previewError = null }
            .onFailure { preview = emptyList(); previewError = it.message }
    }
    ScreenColumn {
        Eyebrow("导入")
        Gap(16)
        Headline("私钥")
        Gap(8)
        Body("先选这把私钥所属的网络。比特币可以同时勾选多种地址，这些地址共用一个备注。")
        Gap(16)
        ChainDropdown("网络", vm.chainChoices, chosen) { chosen = it }
        val chosenChain = vm.chainChoices.find { it.wire == chosen }
        if (chosenChain?.chain == Chain.BITCOIN) {
            Gap(8)
            Body("地址类型，可多选")
            Gap(8)
            BtcAddressType.entries.forEach { type ->
                ChoiceCard(type.title, addressHint(chosenChain.testNet, type), Steel, kinds.contains(type)) {
                    kinds = if (kinds.contains(type)) kinds - type else kinds + type
                }
                Gap(8)
            }
        }
        WalletField(note, { note = it }, "备注，留空则记为账户N")
        Gap(10)
        WalletField(key, { key = it }, "私钥", minLines = 3)
        preview.forEach { (label, value) ->
            Gap(12)
            AddressCard(label, value, Steel)
        }
        previewError?.let {
            Gap(8)
            Notice(it, Danger)
        }
        vm.error?.let { Gap(12); Notice(it, Danger) }
        Gap(18)
        PrimaryButton(if (vm.busy) "正在加密保存" else "核对地址并保存", enabled = preview.isNotEmpty() && !vm.busy) {
            vm.saveKey(key, chosen, kinds, note)
        }
        QuietButton("返回", onClick = vm::back)
    }
}

@Composable
private fun ImportAddressScreen(vm: WalletViewModel) {
    var text by remember { mutableStateOf("") }
    var note by remember { mutableStateOf(vm.pendingImportNote) }
    var chosen by remember { mutableStateOf(vm.pendingImportWire) }
    LaunchedEffect(vm.chainChoices) {
        if (vm.chainChoices.none { it.wire == chosen }) {
            chosen = vm.chainChoices.firstOrNull()?.wire ?: CoreNetwork.BITCOIN_MAINNET.wire
            vm.pendingImportWire = chosen
        }
    }
    ScreenColumn {
        Eyebrow("导入")
        Gap(16)
        Headline("观察地址")
        Gap(8)
        Body("每个地址属于自己的网络，并带上地址类型和备注。扫到带网络标记的地址码时，以二维码里的网络为准。")
        Gap(16)
        ChainDropdown("网络", vm.chainChoices, chosen) {
            chosen = it
            vm.pendingImportWire = it
        }
        Gap(10)
        WalletField(note, { note = it }, "备注，留空则记为账户N")
        Gap(10)
        WalletField(text, { text = it }, "地址或报文", minLines = 3)
        vm.error?.let { Gap(12); Notice(it, Danger) }
        Gap(18)
        PrimaryButton("保存地址") {
            vm.pendingImportWire = chosen
            vm.pendingImportNote = note
            vm.importAddress(text, chosen, note)
        }
        Gap(8)
        SecondaryButton("改为扫码") {
            vm.pendingImportWire = chosen
            vm.pendingImportNote = note
            vm.openScan(ScanPurpose.ADDRESS)
        }
        QuietButton("返回", onClick = vm::back)
    }
}

@Composable
private fun SendScreen(vm: WalletViewModel, accent: androidx.compose.ui.graphics.Color) {
    var to by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var tier by remember { mutableIntStateOf(1) }
    var asset by remember(vm.selectedWire) { mutableStateOf<String?>(null) }
    val evm = vm.isEvmAccount()
    val tokens = vm.currentTokens()
    LaunchedEffect(vm.selectedWire, tokens) {
        if (asset != null && tokens.none { it.contract == asset }) asset = null
    }
    val token = tokens.find { it.contract == asset }
    val unit = token?.symbol ?: if (evm) vm.nativeCoinSymbol() else vm.network?.unit ?: ""
    LaunchedEffect(vm.selectedWire, asset, vm.balance) {
        if (evm) vm.refreshAsset(asset)
    }
    ScreenColumn {
        Eyebrow("转账")
        Gap(16)
        Headline("填写收款")
        Gap(8)
        Body("余额 ${if (evm) vm.assetBalance.ifBlank { vm.balance } else vm.balance}")
        if (evm && vm.assetBalanceNote.isNotBlank()) {
            Gap(4)
            Body(vm.assetBalanceNote, Muted)
        }
        Gap(16)
        WalletField(to, { to = it }, "收款地址", minLines = 2)
        if (evm) {
            val options = listOf<Pair<String?, String>>(null to "原生币 ${vm.nativeCoinSymbol()}") +
                tokens.map { it.contract to "${it.symbol} · ${it.name}" }
            val selected = options.find { it.first == asset }?.second ?: options.first().second
            Gap(12)
            DropdownField("资产类别", selected, options.map { it.second }) { index ->
                asset = options[index].first
            }
        }
        Gap(10)
        WalletField(amount, { amount = it }, "金额（$unit）", keyboardType = KeyboardType.Decimal)
        Gap(16)
        if (vm.tiers.isEmpty()) {
            Notice("还没有手续费报价。先返回首页刷新。")
        } else {
            vm.tiers.forEachIndexed { index, choice ->
                ChoiceCard(choice.label, choice.detail, accent, selected = tier == index) { tier = index }
                Gap(8)
            }
        }
        vm.error?.let { Notice(it, Danger); Gap(8) }
        Gap(8)
        PrimaryButton("生成未签名交易", enabled = !vm.busy && vm.tiers.isNotEmpty()) { vm.buildSend(to, amount, tier, asset) }
        QuietButton("返回", onClick = vm::back)
    }
}

@Composable
private fun ReviewScreen(vm: WalletViewModel, accent: androidx.compose.ui.graphics.Color) {
    var password by remember { mutableStateOf("") }
    var confirmHighFee by remember { mutableStateOf(false) }
    ScreenColumn {
        Eyebrow("签名前核对", accent)
        Gap(12)
        Headline("看见的才会被签")
        Gap(8)
        Body("下面每一项都是从交易原文解析出来的。")
        Gap(16)
        Slip(vm.reviewLines)
        Gap(16)
        WalletField(password, { password = it }, "解锁密码", visualTransformation = PasswordVisualTransformation(), keyboardType = KeyboardType.Password)
        vm.error?.let { Gap(12); Notice(it, Danger) }
        Gap(18)
        PrimaryButton(if (vm.busy) "正在签名" else "确认并签名", enabled = !vm.busy && password.isNotEmpty()) {
            if (vm.needsFeeWarning) confirmHighFee = true else vm.sign(password.toCharArray())
        }
        QuietButton("取消", onClick = vm::back)
    }
    if (confirmHighFee) {
        AlertDialog(
            onDismissRequest = { confirmHighFee = false },
            containerColor = Raised,
            titleContentColor = Ivory,
            textContentColor = Dim,
            title = { Text("手续费偏高") },
            text = { Text("请再看一次金额和手续费。确认后才会签名。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmHighFee = false
                    vm.sign(password.toCharArray())
                }) { Text("仍然签名", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmHighFee = false }) { Text("返回修改", color = Ivory) } },
        )
    }
}

@Composable
private fun QrScreen(vm: WalletViewModel, accent: androidx.compose.ui.graphics.Color) {
    val clipboard = LocalClipboardManager.current
    val image = remember(vm.qrPayload) { runCatching { qrBitmap(vm.qrPayload) }.getOrNull() }
    ScreenColumn {
        Eyebrow(vm.qrTitle, accent)
        Gap(12)
        Body(vm.qrNote)
        Gap(18)
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = vm.qrTitle,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Ivory).padding(16.dp),
            )
        } else {
            Notice("这段报文太长，二维码放不下。请用下面的复制。", Copper)
        }
        Gap(14)
        SelectionContainer {
            Text(vm.qrPayload, color = Dim, fontFamily = Mono, fontSize = 12.sp, lineHeight = 18.sp)
        }
        vm.info?.let { Gap(10); Notice(it, Ok) }
        Gap(16)
        SecondaryButton("复制报文") {
            clipboard.setText(AnnotatedString(vm.qrPayload))
            vm.info = "报文已复制"
        }
        Gap(8)
        PrimaryButton("完成", onClick = vm::back)
    }
}

@Composable
private fun ResultScreen(vm: WalletViewModel, accent: androidx.compose.ui.graphics.Color) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    ScreenColumn {
        Eyebrow(vm.resultTitle, accent)
        Gap(16)
        Headline("交易已提交")
        Gap(12)
        SelectionContainer { Text(vm.resultBody, color = Ivory, fontFamily = Mono, fontSize = 16.sp, lineHeight = 24.sp) }
        Gap(18)
        SecondaryButton("复制 txid") { clipboard.setText(AnnotatedString(vm.resultBody)) }
        vm.resultLink?.let { link ->
            Gap(10)
            SecondaryButton("在浏览器中查看") {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
            }
        }
        Gap(10)
        PrimaryButton("回到首页", onClick = vm::back)
    }
}

@Composable
private fun SettingsScreen(vm: WalletViewModel) {
    var confirmReset by remember { mutableStateOf(false) }
    ScreenColumn {
        Eyebrow("设置")
        Gap(16)
        Headline("这台设备")
        Gap(12)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Raised)
                .border(1.dp, Line, RoundedCornerShape(20.dp))
                .padding(horizontal = 18.dp, vertical = 8.dp),
        ) {
            listOf(
                "角色" to if (vm.role == Role.OFFLINE) "离线签名" else "在线广播",
                "运行空间" to vm.spaceLabel,
            ).forEach { (label, value) ->
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Text(label, color = Muted, fontSize = 12.sp)
                    Gap(4)
                    Text(value, color = Ivory, fontSize = 15.sp)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("版本", color = Muted, fontSize = 12.sp)
                    Gap(4)
                    Text(BuildConfig.VERSION_NAME, color = Ivory, fontSize = 15.sp)
                }
                Text(
                    if (vm.updateBusy) "正在检查" else "更新",
                    color = if (vm.updateBusy) Muted else Copper,
                    fontSize = 14.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, if (vm.updateBusy) Line else Copper, RoundedCornerShape(12.dp))
                        .clickable(enabled = !vm.updateBusy) { vm.checkUpdate() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        if (vm.role == Role.OFFLINE) {
            Gap(22)
            SecondaryButton("EVM 网络管理") { vm.open(Screen.EvmNetworks) }
        }
        if (vm.role == Role.ONLINE) {
            Gap(22)
            SecondaryButton("节点管理") { vm.open(Screen.Nodes) }
            Gap(10)
            SecondaryButton("代币管理") { vm.open(Screen.Tokens) }
        }
        vm.error?.let { Gap(12); Notice(it, Danger) }
        Gap(12)
        QuietButton("清除本机数据并重选角色", Danger) { confirmReset = true }
        QuietButton("返回", onClick = vm::back)
    }
    when (val notice = vm.updateNotice) {
        null -> Unit
        UpdateNotice.Latest -> UpdateDialog("当前已是最新版本", "现在用的就是 GitHub 上的最新发布。", "知道了", onConfirm = vm::dismissUpdate, onDismiss = vm::dismissUpdate)
        is UpdateNotice.Available -> UpdateDialog(
            "发现新版本 ${notice.release.version.removePrefix("v").removePrefix("V")}",
            notice.release.notes.ifBlank { "可以下载并安装这个版本。" }.take(400),
            if (vm.updateBusy) "正在下载" else "更新",
            enabled = !vm.updateBusy,
            onConfirm = vm::installUpdate,
            onDismiss = vm::dismissUpdate,
        )
        is UpdateNotice.Failed -> UpdateDialog("更新", notice.message, "知道了", onConfirm = vm::dismissUpdate, onDismiss = vm::dismissUpdate)
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = Raised,
            titleContentColor = Ivory,
            textContentColor = Dim,
            title = { Text("清除这台手机上的钱包数据") },
            text = { Text("只会清除当前这个应用里的私钥和地址。主应用和分身互不影响。请确认你还留着私钥的另一份抄写。") },
            confirmButton = { TextButton(onClick = vm::resetAll) { Text("清除", color = Danger) } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消", color = Ivory) } },
        )
    }
}

@Composable
private fun UpdateDialog(
    title: String,
    body: String,
    confirm: String,
    enabled: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (enabled) onDismiss() },
        containerColor = Raised,
        titleContentColor = Ivory,
        textContentColor = Dim,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = enabled) { Text(confirm, color = if (enabled) Ivory else Muted) }
        },
        dismissButton = {
            if (confirm == "更新" || confirm == "正在下载") {
                TextButton(onClick = onDismiss, enabled = enabled) { Text("关闭", color = if (enabled) Ivory else Muted) }
            }
        },
    )
}

private fun accountMatches(vm: WalletViewModel, account: com.offlinewallet.StoredAccount, index: Int, query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    val type = BtcAddressType.fromWire(account.kind)?.title ?: vm.chainTitle(account.network)
    val haystack = listOf(
        account.note ?: "账户${index + 1}",
        vm.accountCaption(account),
        account.address,
        account.network,
        account.networkId()?.title,
        account.kind,
        type,
    ).filterNotNull().joinToString(" ")
    var from = 0
    for (ch in needle) {
        val found = haystack.indexOf(ch, from, ignoreCase = true)
        if (found < 0) return false
        from = found + 1
    }
    return true
}

@Composable
private fun AccountList(vm: WalletViewModel, accent: androidx.compose.ui.graphics.Color, offline: Boolean, query: String = "") {
    val clipboard = LocalClipboardManager.current
    var pendingRemove by remember { mutableStateOf<Pair<String, String?>?>(null) }
    val visible = vm.accounts.withIndex().filter { (index, account) -> accountMatches(vm, account, index, query) }
    if (query.isNotBlank() && visible.isEmpty()) {
        Body("没有匹配的账户")
        Gap(10)
    }
    visible.forEach { (index, account) ->
        val selected = vm.isCurrent(account)
        AddressCard(
            label = if (selected) "当前 · ${account.note ?: "账户${index + 1}"}" else (account.note ?: "账户${index + 1}"),
            detail = vm.accountCaption(account),
            address = account.address,
            accent = accent,
            selected = selected,
            onClick = { vm.select(account.address, account.network) },
            onCopy = {
                clipboard.setText(AnnotatedString(account.address))
                vm.info = "地址已复制"
            },
            onRemove = { pendingRemove = account.address to account.network },
        )
        Gap(10)
    }
    pendingRemove?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            containerColor = Raised,
            titleContentColor = Ivory,
            textContentColor = Dim,
            title = { Text("移除这个地址") },
            text = {
                Text(
                    if (offline) "移除后，这把私钥的签名能力会从这台手机消失。请确认你还留着抄写。"
                    else "只是不再观察这个地址，不会影响链上资产。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeAccount(target.first, target.second)
                    pendingRemove = null
                }) { Text("移除", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { pendingRemove = null }) { Text("取消", color = Ivory) } },
        )
    }
}

@Composable
private fun ChainDropdown(
    label: String,
    choices: List<com.offlinewallet.ChainChoice>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    if (choices.isEmpty()) return
    val index = choices.indexOfFirst { it.wire == selected }.let { if (it < 0) 0 else it }
    val labels = choices.map { if (it.detail.isBlank()) it.title else "${it.title} · ${it.detail}" }
    DropdownField(label, labels[index], labels) { onSelect(choices[it].wire) }
}

@Composable
private fun NodeManageScreen(vm: WalletViewModel) {
    ScreenColumn {
        Eyebrow("设置")
        Gap(16)
        Headline("节点")
        Gap(8)
        Body("先在上面添加网络。下面每张卡片可以改节点地址。http 和 https 都可以。以太坊主网和比特币主网不能删除。")
        Gap(16)
        AddEvmCard(vm)
        Gap(16)
        NodeCard("Bitcoin 主网", "比特币", vm.endpointOf(CoreNetwork.BITCOIN_MAINNET.wire), null) { _, endpoint ->
            vm.updateEndpoint(CoreNetwork.BITCOIN_MAINNET.wire, endpoint)
        }
        vm.evmChains.forEach { chain ->
            Gap(10)
            NodeCard(
                chain.title,
                "链 ID ${chain.chainId}",
                vm.endpointOf(chain.wire),
                if (chain.wire == CoreNetwork.ETHEREUM_MAINNET.wire) null else ({ vm.deleteEvmChain(chain.wire) }),
                if (chain.builtin) null else chain.title,
            ) { name, endpoint ->
                if (name == null || vm.renameEvmChain(chain.wire, name)) vm.updateEndpoint(chain.wire, endpoint)
            }
        }
        vm.error?.let { Gap(12); Notice(it, Danger) }
        Gap(12)
        QuietButton("返回", onClick = vm::back)
    }
}

@Composable
private fun TokenManageScreen(vm: WalletViewModel) {
    ScreenColumn {
        Eyebrow("设置")
        Gap(16)
        Headline("代币")
        Gap(8)
        Body("选择网络和合约地址。添加时从链上读取名称、符号和小数位。")
        Gap(16)
        AddTokenCard(vm)
        if (vm.savedTokens.isEmpty()) {
            Gap(16)
            Body("还没有代币。")
        }
        vm.savedTokens.forEach { token ->
            Gap(10)
            TokenRow(vm, token)
        }
        vm.error?.let { Gap(12); Notice(it, Danger) }
        Gap(12)
        QuietButton("返回", onClick = vm::back)
    }
}

@Composable
private fun EvmNetworkScreen(vm: WalletViewModel) {
    ScreenColumn {
        Eyebrow("设置")
        Gap(16)
        Headline("EVM 网络")
        Gap(8)
        Body("离线手机不访问节点。添加时只记下名称和 Chain ID，导入私钥时才能选到这条链。")
        Gap(16)
        AddOfflineEvmCard(vm)
        Gap(16)
        vm.evmChains.forEach { chain ->
            OfflineChainCard(vm, chain)
            Gap(10)
        }
        vm.error?.let { Gap(12); Notice(it, Danger) }
        Gap(8)
        QuietButton("返回", onClick = vm::back)
    }
}

@Composable
private fun OfflineChainCard(vm: WalletViewModel, chain: com.offlinewallet.EvmChainConfig) {
    var name by remember(chain.wire, chain.title) { mutableStateOf(chain.title) }
    var confirm by remember { mutableStateOf(false) }
    val locked = chain.wire == CoreNetwork.ETHEREUM_MAINNET.wire
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Raised)
            .border(1.dp, Line, RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("链 ID ${chain.chainId}", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            if (!locked) {
                Text("删除", color = Danger, modifier = Modifier.clickable { confirm = true }, fontSize = 14.sp)
            }
        }
        Gap(10)
        if (locked) {
            Text(chain.title, color = Ivory, fontSize = 16.sp)
        } else {
            WalletField(name, { name = it }, "名称")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                QuietButton("保存") { vm.renameEvmChain(chain.wire, name) }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = Raised,
            titleContentColor = Ivory,
            textContentColor = Dim,
            title = { Text("删除 ${chain.title}") },
            text = { Text("这个网络上如果还有账户，需要先移除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    vm.deleteEvmChain(chain.wire)
                }) { Text("删除", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消", color = Ivory) } },
        )
    }
}

@Composable
private fun NodeCard(
    title: String,
    subtitle: String,
    endpoint: String,
    onDelete: (() -> Unit)?,
    editableName: String? = null,
    onSave: (String?, String) -> Unit,
) {
    var text by remember(title, endpoint) { mutableStateOf(endpoint) }
    var name by remember(title, editableName) { mutableStateOf(editableName ?: title) }
    var confirm by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Raised)
            .border(1.dp, Line, RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (editableName == null) Text(title, color = Ivory, fontSize = 16.sp)
                Text(subtitle, color = Muted, fontSize = 12.sp)
            }
            if (onDelete != null) {
                Text("删除", color = Danger, modifier = Modifier.clickable { confirm = true }, fontSize = 14.sp)
            }
        }
        Gap(10)
        if (editableName != null) {
            WalletField(name, { name = it }, "名称")
            Gap(8)
        }
        WalletField(text, { text = it }, "节点")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            QuietButton("保存") { onSave(if (editableName == null) null else name, text) }
        }
    }
    if (confirm && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = Raised,
            titleContentColor = Ivory,
            textContentColor = Dim,
            title = { Text("删除 $title") },
            text = { Text("这个网络上的代币也会一起去掉。上面如果还有账户，需要先移除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onDelete()
                }) { Text("删除", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消", color = Ivory) } },
        )
    }
}

@Composable
private fun AddOfflineEvmCard(vm: WalletViewModel) {
    var title by remember(vm.networkFormEpoch) { mutableStateOf("") }
    var chainId by remember(vm.networkFormEpoch) { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Raised)
            .border(1.dp, Line, RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        Text("添加 EVM 网络", color = Ivory, fontSize = 16.sp)
        Gap(10)
        WalletField(title, { title = it }, "名称，例如 BNB Chain")
        Gap(8)
        WalletField(chainId, { chainId = it }, "Chain ID", keyboardType = KeyboardType.Number)
        Gap(12)
        PrimaryButton("添加网络", enabled = !vm.busy) { vm.addOfflineEvmChain(title, chainId) }
    }
}

@Composable
private fun AddEvmCard(vm: WalletViewModel) {
    var title by remember(vm.networkFormEpoch) { mutableStateOf("") }
    var chainId by remember(vm.networkFormEpoch) { mutableStateOf("") }
    var endpoint by remember(vm.networkFormEpoch) { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Raised)
            .border(1.dp, Line, RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        Text("添加 EVM 网络", color = Ivory, fontSize = 16.sp)
        Gap(10)
        WalletField(title, { title = it }, "名称，例如 Polygon")
        Gap(8)
        WalletField(chainId, { chainId = it }, "Chain ID", keyboardType = KeyboardType.Number)
        Gap(8)
        WalletField(endpoint, { endpoint = it }, "节点")
        Gap(12)
        PrimaryButton(if (vm.busy) "正在核对链 ID" else "添加网络", enabled = !vm.busy) {
            vm.addEvmChain(title, chainId, endpoint)
        }
    }
}

@Composable
private fun AddTokenCard(vm: WalletViewModel) {
    var wire by remember { mutableStateOf(CoreNetwork.ETHEREUM_MAINNET.wire) }
    var contract by remember(vm.tokenFormEpoch) { mutableStateOf("") }
    LaunchedEffect(vm.evmChains) {
        if (vm.evmChains.none { it.wire == wire }) wire = vm.evmChains.firstOrNull()?.wire ?: wire
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Raised)
            .border(1.dp, Line, RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        Text("添加代币", color = Ivory, fontSize = 16.sp)
        Gap(10)
        val chains = vm.evmChains
        if (chains.isNotEmpty()) {
            val index = chains.indexOfFirst { it.wire == wire }.let { if (it < 0) 0 else it }
            val labels = chains.map { "${it.title} · 链 ID ${it.chainId}" }
            DropdownField("网络", labels[index], labels) { wire = chains[it].wire }
            Gap(8)
        }
        WalletField(contract, { contract = it }, "合约地址")
        Gap(12)
        PrimaryButton(if (vm.busy) "正在读取" else "添加", enabled = !vm.busy && contract.isNotBlank()) {
            vm.addToken(wire, contract)
        }
    }
}

@Composable
private fun TokenRow(vm: WalletViewModel, token: com.offlinewallet.SavedToken) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Raised)
            .border(1.dp, Line, RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${token.symbol} · ${token.name}", color = Ivory, fontSize = 16.sp)
                Text("${vm.chainTitle(token.network)} · ${token.decimals} 位小数", color = Muted, fontSize = 12.sp)
            }
            Text("删除", color = Danger, modifier = Modifier.clickable { vm.removeToken(token.network, token.contract) }, fontSize = 14.sp)
        }
        Gap(8)
        Text(displayAddress(token.contract), color = Dim, fontFamily = Mono, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun HomeHeader(vm: WalletViewModel, headline: String) {
    Eyebrow(if (vm.role == Role.OFFLINE) "离线签名" else "在线广播", if (vm.role == Role.OFFLINE) Steel else Copper)
    Gap(8)
    Text(headline, color = Ivory, fontSize = 28.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Light)
    Gap(16)
}

@Composable
private fun SpaceNotice(vm: WalletViewModel) {
    if (vm.spaceLabel == "分身") {
        Gap(14)
        Notice("当前是应用分身，私钥和地址与主应用分开。", Steel)
        Gap(14)
    }
}

@Composable
private fun Messages(vm: WalletViewModel) {
    vm.error?.let { Notice(it, Danger); Gap(12) }
    vm.info?.let { Notice(it, Ok); Gap(12) }
}

private fun qrBitmap(text: String): androidx.compose.ui.graphics.ImageBitmap {
    val hints = mapOf(
        EncodeHintType.MARGIN to 1,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val size = 720
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix.get(x, y)) 0xFF101311.toInt() else 0xFFF3F0E8.toInt()
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}
