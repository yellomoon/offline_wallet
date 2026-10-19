package com.offlinewallet.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
            BusyBar(vm.busy, accent)
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
        Body("网络在添加每一把私钥时再选。主网和测试网可以同时留在这台手机上，互不混用。")
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

private fun addressHint(network: CoreNetwork, type: BtcAddressType): String = when (type) {
    BtcAddressType.P2WPKH -> if (network.testNet) "tb1q…" else "bc1q…"
    BtcAddressType.P2SH_P2WPKH -> if (network.testNet) "2…" else "3…"
    BtcAddressType.P2PKH -> if (network.testNet) "m… 或 n…" else "1…"
    BtcAddressType.P2TR -> if (network.testNet) "tb1p…" else "bc1p…"
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
    LaunchedEffect(vm.address, vm.network) {
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
    var chosen by remember { mutableStateOf(CoreNetwork.BITCOIN_TESTNET3) }
    var kinds by remember { mutableStateOf(setOf(BtcAddressType.P2WPKH)) }
    var preview by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var previewError by remember { mutableStateOf<String?>(null) }
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
        CoreNetwork.entries.forEach { net ->
            ChoiceCard(net.title, if (net.chain == Chain.BITCOIN) "压缩 WIF" else "64 位十六进制私钥", if (net.testNet) Steel else Copper, chosen == net) {
                chosen = net
            }
            Gap(8)
        }
        if (chosen.chain == Chain.BITCOIN) {
            Gap(8)
            Body("地址类型，可多选")
            Gap(8)
            BtcAddressType.entries.forEach { type ->
                ChoiceCard(type.title, addressHint(chosen, type), Steel, kinds.contains(type)) {
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
    var chosen by remember { mutableStateOf(vm.pendingImportNetwork) }
    ScreenColumn {
        Eyebrow("导入")
        Gap(16)
        Headline("观察地址")
        Gap(8)
        Body("每个地址属于自己的网络，并带上地址类型和备注。扫到带网络标记的地址码时，以二维码里的网络为准。")
        Gap(16)
        CoreNetwork.entries.forEach { net ->
            ChoiceCard(net.title, if (net.testNet) "测试网" else "主网", if (net.testNet) Steel else Copper, chosen == net) {
                chosen = net
                vm.pendingImportNetwork = net
            }
            Gap(8)
        }
        WalletField(note, { note = it }, "备注，留空则记为账户N")
        Gap(10)
        WalletField(text, { text = it }, "地址或报文", minLines = 3)
        vm.error?.let { Gap(12); Notice(it, Danger) }
        Gap(18)
        PrimaryButton("保存地址") {
            vm.pendingImportNetwork = chosen
            vm.pendingImportNote = note
            vm.importAddress(text, chosen, note)
        }
        Gap(8)
        SecondaryButton("改为扫码") {
            vm.pendingImportNetwork = chosen
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
    val unit = vm.network?.unit ?: ""
    ScreenColumn {
        Eyebrow("转账")
        Gap(16)
        Headline("填写收款")
        Gap(8)
        Body("余额 ${vm.balance}")
        Gap(16)
        WalletField(to, { to = it }, "收款地址", minLines = 2)
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
        PrimaryButton("生成未签名交易", enabled = !vm.busy && vm.tiers.isNotEmpty()) { vm.buildSend(to, amount, tier) }
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
        Slip(
            listOf(
                "角色" to if (vm.role == Role.OFFLINE) "离线签名" else "在线广播",
                "运行空间" to vm.spaceLabel,
                "版本" to BuildConfig.VERSION_NAME,
            ),
        )
        if (vm.role == Role.ONLINE) {
            Gap(22)
            Headline("节点")
            Gap(8)
            Body("每个网络单独保存。http 和 https 都可以。")
            CoreNetwork.entries.forEach { net ->
                EndpointEditor(vm, net)
            }
        }
        vm.error?.let { Gap(12); Notice(it, Danger) }
        Gap(12)
        QuietButton("清除本机数据并重选角色", Danger) { confirmReset = true }
        QuietButton("返回", onClick = vm::back)
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

private fun accountMatches(account: com.offlinewallet.StoredAccount, index: Int, query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    val type = BtcAddressType.fromWire(account.kind)?.title
        ?: if (account.networkId()?.chain == Chain.EVM) "以太坊地址" else ""
    val haystack = listOf(
        account.note ?: "账户${index + 1}",
        account.caption(),
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
    val visible = vm.accounts.withIndex().filter { (index, account) -> accountMatches(account, index, query) }
    if (query.isNotBlank() && visible.isEmpty()) {
        Body("没有匹配的账户")
        Gap(10)
    }
    visible.forEach { (index, account) ->
        val selected = account.address == vm.address && account.network == vm.network?.wire
        AddressCard(
            label = if (selected) "当前 · ${account.note ?: "账户${index + 1}"}" else (account.note ?: "账户${index + 1}"),
            detail = account.caption(),
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
private fun EndpointEditor(vm: WalletViewModel, network: CoreNetwork) {
    var text by remember(network) { mutableStateOf(vm.endpointOf(network)) }
    var saved by remember(network) { mutableStateOf(false) }
    Gap(14)
    WalletField(text, { text = it; saved = false }, network.title)
    Gap(8)
    SecondaryButton(if (saved) "已保存 ${network.title}" else "保存 ${network.title}") {
        vm.updateEndpoint(network, text)
        saved = vm.error == null
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
