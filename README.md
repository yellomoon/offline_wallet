# 离线钱包

一个 Android 冷钱包。同一份安装包里有两个角色，装在两台手机上：

- **离线签名**：导入私钥，显示地址，只负责签名。不查询余额，不广播。
- **在线广播**：只导入地址，查询余额，组装交易，验签后广播。

私钥不会放进二维码。两台手机之间用二维码或复制报文传递地址和交易。

第一期支持：

- Bitcoin 主网和 Testnet3。导入私钥时选择网络，并可以同时勾选 Native SegWit（`bc1q` / `tb1q`）、Nested SegWit（`3…` / `2…`）、Legacy（`1…` / `m…` 或 `n…`）、Taproot（`bc1p` / `tb1p`）。每种地址单独成为一个账户
- Ethereum 主网和 Sepolia，只转原生 ETH，EIP-1559，`gasLimit` 固定 21000。每个账户自己带网络，可以和比特币账户放在同一台手机上

交易确认页从交易原文解析。比特币找零如果不是本机地址，或者以太坊交易带有 `data`，离线端会拒绝签名。

这个软件没有经过独立安全审计。先在 Testnet3 和 Sepolia 上走通，再考虑主网。作者不对资产损失负责。

## 两台手机怎么用

1. 准备两台 Android 8.0 以上的手机，都安装 `offline-wallet.apk`。
2. 离线那台打开飞行模式，关掉无线局域网和蓝牙。选择「离线签名」，确认已断开网络，并在这一页设置至少 8 位密码。这个密码全应用只设一次。导入私钥时再选这把钥匙的网络和地址类型，并写备注；备注留空会记为账户1、账户2。同一把私钥勾选的多种地址共用这个备注。后面再加的私钥可以选另一个网络，互不影响。
3. 在离线端首页点选一个账户，出示它的地址二维码。
4. 在线那台选择「在线广播」。导入地址时选择这个地址所属的网络和备注，或扫描离线端出示的地址码。地址类型由地址本身决定。它只能看到地址和余额。转账和查余额都针对当前选中的那个账户。
5. 在线端填写收款地址、金额和手续费，生成未签名交易二维码。
6. 离线端扫描它，核对收款地址、金额、找零和手续费，输入密码后签名。如果这笔比特币交易属于另一把已导入的私钥，会自动切换到那一把。
7. 在线端扫描已签名二维码。应用会先核对这笔交易是不是刚才那一笔，再广播，并给出 txid。

相机扫不出来时，每个二维码页面都可以复制报文，到另一边粘贴。

## 同一台手机用应用分身

安装包可以装在分身空间里，数据和主应用分开。能不能在系统设置里打开分身，取决于手机厂商的名单，不是这个应用自己能打开的开关。

华为 HarmonyOS 4 的内置名单只有微信、QQ、Facebook、WhatsApp、LINE、Snapchat、Messenger。离线钱包不在里面，设置里的「应用分身」不会出现它。这份名单在系统分区，应用无法把自己加进去。

小米等机型的应用双开通常能给任意应用打开分身。打开之后，一边选「离线签名」，另一边选「在线广播」。设置页的「运行空间」会写明当前是主应用还是分身。离线的那一个仍要打开飞行模式，并关掉无线局域网和蓝牙。

分身和主应用在同一台手机上，隔离比两台手机弱。这台华为手机请用两台手机。

## 安装根目录里的 APK

仓库根目录的 `offline-wallet.apk` 是从当前源码打出来的 release 包，方便直接安装。签名密钥在本机第一次编译时生成，密码写在构建脚本里，只能用来区分安装包，不能当作开发者身份。要信任这份软件，请自己从源码编译。

把 APK 拷到手机后打开安装。如果系统拦截，允许安装未知来源应用。也可以在电脑上执行：

```bat
adb install -r offline-wallet.apk
```

## 从源码编译

需要：

- JDK 17 或 21（不要用太新的 JDK，Android Gradle Plugin 8.7 配 JDK 21 即可）
- Android SDK，至少安装 `platforms;android-35` 和 `build-tools;35.0.0`

Windows 示例：

```bat
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21
set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
```

在项目根目录写 `local.properties`，注意反斜杠要写成 `\\`：

```properties
sdk.dir=C:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk
```

然后：

```bat
gradlew.bat :core:test
gradlew.bat :app:assembleRelease
```

macOS 和 Linux 把 `gradlew.bat` 换成 `./gradlew`，并先执行 `chmod +x gradlew`。`local.properties` 里的 `sdk.dir` 用本机 SDK 路径。

测试通过后，安装包在：

```text
app/build/outputs/apk/release/app-release.apk
```

第一次打 release 包时，构建脚本会在 `keystore/open-signing.jks` 生成一个本地签名密钥，别名 `open`，store 密码和 key 密码都是 `offlinewallet`。这个目录已加入 `.gitignore`。换一台电脑会生成另一把密钥，系统会把它当成另一个应用，需要先卸载旧包。

## 报文

二维码和粘贴文本使用同一段文字，前缀是 `owtx1:`，后面是一层 JSON：

```json
{"v":1,"kind":"unsigned","chain":"bitcoin","network":"bitcoin-testnet3","body":"..."}
```

| 字段 | 取值 |
| --- | --- |
| `v` | 目前只有 `1` |
| `kind` | `address`、`unsigned`、`signed` |
| `chain` | `bitcoin` 或 `evm` |
| `network` | `bitcoin-mainnet`、`bitcoin-testnet3`、`ethereum-mainnet`、`ethereum-sepolia` |
| `body` | 地址原文，或交易的 Base64 |

地址、未签名交易、已签名交易不能混扫。Bitcoin 的交易正文是 PSBT。EVM 的未签名正文是 `0x02` 开头的 EIP-1559 RLP，已签名正文带 `yParity`、`r`、`s`。

默认节点：

- Bitcoin 主网 `https://blockstream.info/api`
- Bitcoin Testnet3 `https://blockstream.info/testnet/api`
- Ethereum 主网 `https://ethereum.publicnode.com`
- Sepolia `https://ethereum-sepolia-rpc.publicnode.com`

在线端设置里可以分别修改四个网络的节点，`http://` 和 `https://` 都可以。余额最多显示 8 位小数。

## 安全边界

- 离线模式的代码路径不会发起网络请求。系统仍然有网络权限，所以离线手机必须自己保持飞行模式。
- 离线端在确认断开网络时设置一次本机密码。之后每把私钥都用这个密码做 PBKDF2-HMAC-SHA256（20 万次），再用 AES-GCM 存在本机。签名时仍要输入这个密码。应用禁止截图和最近任务预览。
- 不提供云备份，`allowBackup` 已关闭。
- 在线端向公共节点查询时，节点提供方能看到地址。
- 分身只提供另一份应用数据，不能代替两台手机之间的气隙。
- 不做助记词、HD 派生、代币、合约调用、多签、闪电网络，也不做蓝牙传输。

## 许可

MIT，见 `LICENSE`。
