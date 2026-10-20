package com.offlinewallet.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigInteger

class WalletCoreTest {
    @Test
    fun secp256k1GeneratorAndBech32MatchPublicVectors() {
        val priv = ByteArray(32).also { it[31] = 1 }
        assertEquals(
            "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
            Secp256k1.compressedPub(priv).toHex(),
        )
        assertEquals("751e76e8199196d454941c45d1b3a323f1433bd6", hash160(Secp256k1.compressedPub(priv)).toHex())
        assertEquals(
            "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
            Bech32.encodeSegwit("bc", 0, "751e76e8199196d454941c45d1b3a323f1433bd6".hexToBytes()),
        )
        assertEquals("0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf", ethAddress(priv))
        assertEquals("KwDiBf89QgGbjEhKnhXJuH7LrciVrZi3qYjgd9M7rFU73sVHnoWn", exportWif(priv, NetworkId.BITCOIN_MAINNET))
        assertEquals("1BgGZ9tcN4rm9KBzDn7KprQz87SZ26SAMH", bitcoinAddress(priv, NetworkId.BITCOIN_MAINNET, BtcAddressType.P2PKH))
        assertEquals(
            "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
            bitcoinAddress(priv, NetworkId.BITCOIN_MAINNET, BtcAddressType.P2WPKH),
        )
        val nested = bitcoinAddress(priv, NetworkId.BITCOIN_MAINNET, BtcAddressType.P2SH_P2WPKH)
        val taproot = bitcoinAddress(priv, NetworkId.BITCOIN_MAINNET, BtcAddressType.P2TR)
        assertEquals(nested, scriptToAddress(addressToScript(nested, NetworkId.BITCOIN_MAINNET), NetworkId.BITCOIN_MAINNET))
        assertEquals(taproot, scriptToAddress(addressToScript(taproot, NetworkId.BITCOIN_MAINNET), NetworkId.BITCOIN_MAINNET))
        assertTrue(taproot.startsWith("bc1p"))
        val aux = ByteArray(32)
        val msg = ByteArray(32)
        val secret = ByteArray(32).also { it[31] = 3 }
        val signature = "e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca821525f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0"
        assertEquals(signature, Secp256k1.schnorrSign(msg, secret, aux).toHex())
        assertTrue(Secp256k1.schnorrVerify(msg, signature.hexToBytes(), "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9".hexToBytes()))
    }

    @Test
    fun wifRejectsUncompressedAndWrongNetwork() {
        val priv = ByteArray(32).also { it[31] = 1 }
        val wif = exportWif(priv, NetworkId.BITCOIN_MAINNET)
        val imported = importWif(wif, NetworkId.BITCOIN_MAINNET)
        assertArrayEquals(priv, imported.priv)
        assertThrows(WalletException::class.java) { importWif(wif, NetworkId.BITCOIN_TESTNET3) }
        val payload = Base58.decodeCheck(wif).toMutableList()
        payload.removeAt(payload.lastIndex)
        val uncompressed = Base58.encodeCheck(payload.toByteArray())
        assertThrows(WalletException::class.java) { importWif(uncompressed, NetworkId.BITCOIN_MAINNET) }
    }

    @Test
    fun bip143SighashMatchesTheSpecificationVector() {
        val scriptCode = "76a9141d0f172a0ecb48aee1be1f2687d2963ae33f71a188ac".hexToBytes()
        val tx = Psbt(
            version = 1,
            locktime = 0x11,
            inputs = listOf(
                BtcIn(
                    txidInternal = "fff7f7881a8099afa6940d42d1e7f6362bec38171ea3edf433541db4e4ad969f".hexToBytes(),
                    vout = 0,
                    sequence = 0xffffffeeL,
                    value = 625_000_000,
                    scriptPubKey = byteArrayOf(0),
                ),
                BtcIn(
                    txidInternal = "ef51e1b804cc89d182d279655c3aa89e815b1b309fe287d9b2b55d57b90ec68a".hexToBytes(),
                    vout = 1,
                    sequence = 0xffffffffL,
                    value = 600_000_000,
                    scriptPubKey = "00141d0f172a0ecb48aee1be1f2687d2963ae33f71a1".hexToBytes(),
                ),
            ),
            outputs = listOf(
                BtcOut(112_340_000, "76a9148280b37df378db99f66f85c95a783a76ac7a6d5988ac".hexToBytes(), ""),
                BtcOut(223_450_000, "76a9143bde42dbee7e4dbe6a21b2d50ce2f0167faa815988ac".hexToBytes(), ""),
            ),
        )
        val preimage = bip143Preimage(tx, 1, scriptCode)
        assertEquals(
            "0100000096b827c8483d4e9b96712b6713a7b68d6e8003a781feba36c31143470b4efd37" +
                "52b0a642eea2fb7ae638c36f6252b6750293dbe574a806984b8e4d8548339a3b" +
                "ef51e1b804cc89d182d279655c3aa89e815b1b309fe287d9b2b55d57b90ec68a01000000" +
                "1976a9141d0f172a0ecb48aee1be1f2687d2963ae33f71a188ac0046c32300000000ffffffff" +
                "863ef3e1a92afbfdb97f31ad0fc7683ee943e9abcf2501590ff8f6551f47e5e5" +
                "1100000001000000",
            preimage.toHex(),
        )
        assertEquals("c37af31116d1b27caf68aae9e3ac82f1477929014d5b917657d0eb49478cb670", hash256(preimage).toHex())
        val signature = "304402203609e17b84f6a7d30c80bfa610b5b4542f32a8a0d5447a12fb1366d7f01cc44a0220573a954c4518331561406f90300e8f3358f51928d43c212a8caed02de67eebee01".hexToBytes()
        val (r, s) = Secp256k1.derDecode(signature.copyOf(signature.size - 1))
        val pub = "025476c2e83188368da1ff3e292e7acafcdb3566bb0ad253f62fc70f07aeee6357".hexToBytes()
        assertTrue(Secp256k1.verify(hash256(preimage), r, s, pub))
    }

    @Test
    fun bitcoinSignVerifyAndRejectsTamperedChange() {
        val network = NetworkId.BITCOIN_TESTNET3
        val payer = importWif(exportWif(ByteArray(32).also { it[31] = 3 }, network), network)
        val other = importWif(exportWif(ByteArray(32).also { it[31] = 4 }, network), network)
        val ownScript = addressToScript(payer.address, network)
        val payScript = addressToScript(other.address, network)
        val utxo = Utxo("ab".repeat(32), 1, 250_000)
        val plan = selectCoins(listOf(utxo), 80_000, payScript.size, ownScript.size, 3.0)
        assertEquals(plan.inputs.sumOf { it.value }, plan.payValue + plan.changeValue + plan.fee)
        val psbt = buildPsbt(plan, payScript, other.address, ownScript, payer.address)
        val decoded = decodePsbt(encodePsbt(psbt))
        assertTrue(sameUnsignedBitcoin(psbt, decoded))
        val signed = signBitcoin(decoded, payer.priv)
        verifyBitcoin(signed)
        assertEquals(64, bitcoinTxid(signed).length)
        assertTrue(extractRawBitcoin(signed).size > 80)

        val stolen = psbt.copy(outputs = listOf(psbt.outputs[0], psbt.outputs[1].copy(script = payScript, address = other.address)))
        assertThrows(WalletException::class.java) { reviewBitcoin(stolen, payer.address, network) }
        assertThrows(WalletException::class.java) {
            selectCoins(listOf(Utxo("cd".repeat(32), 0, 1_000)), 50_000, 22, 22, 2.0)
        }
    }

    @Test
    fun bitcoinSignsLegacyNestedAndTaproot() {
        val network = NetworkId.BITCOIN_MAINNET
        val priv = ByteArray(32).also { it[31] = 1 }
        val payee = bitcoinAddress(ByteArray(32).also { it[31] = 2 }, network, BtcAddressType.P2WPKH)
        val payScript = addressToScript(payee, network)
        for (type in BtcAddressType.entries) {
            val own = bitcoinAddress(priv, network, type)
            val ownScript = addressToScript(own, network)
            assertEquals(type, btcScriptType(ownScript))
            val plan = selectCoins(listOf(Utxo("11".repeat(32), 0, 200_000)), 50_000, payScript.size, ownScript.size, 2.0, type)
            val signed = signBitcoin(buildPsbt(plan, payScript, payee, ownScript, own), priv)
            verifyBitcoin(signed)
            val raw = extractRawBitcoin(signed)
            assertTrue(raw.size > 80)
            if (type == BtcAddressType.P2PKH) {
                assertEquals(0x01, raw[4].toInt() and 0xff)
            } else {
                assertEquals(0x00, raw[4].toInt() and 0xff)
                assertEquals(0x01, raw[5].toInt() and 0xff)
            }
        }
    }

    @Test
    fun ethereumSignsAndRejectsContractData() {
        val priv = ByteArray(32).also { it[31] = 1 }
        val from = ethAddress(priv)
        val unsigned = encodeUnsignedEth(
            EthUnsigned(
                chainId = 11155111,
                nonce = 7,
                maxPriorityFeePerGas = BigInteger.valueOf(1_000_000_000),
                maxFeePerGas = BigInteger("20000000000"),
                gasLimit = 21_000,
                to = "0000000000000000000000000000000000000001".hexToBytes(),
                value = BigInteger("1000000000000000"),
            ),
        )
        val review = reviewEthereum(unsigned, 11155111)
        assertEquals("0x0000000000000000000000000000000000000001", review.to)
        assertEquals(BigInteger("420000000000000"), review.maxFee)
        val signed = signEthereum(unsigned, priv)
        assertEquals(from, ethereumSender(signed))
        assertArrayEquals(unsigned, unsignedFromSignedEth(signed))
        val bad = byteArrayOf(0x02) + Rlp.encode(
            Rlp.Item.List(
                listOf(
                    Rlp.uint(1),
                    Rlp.uint(0),
                    Rlp.uint(1),
                    Rlp.uint(1),
                    Rlp.uint(21_000),
                    Rlp.bytes(ByteArray(20)),
                    Rlp.uint(1),
                    Rlp.bytes(byteArrayOf(1)),
                    Rlp.Item.List(emptyList()),
                ),
            ),
        )
        assertThrows(WalletException::class.java) { reviewEthereum(bad, 1) }
        assertThrows(WalletException::class.java) { normalizeEthAddress("0x7e5f4552091A69125d5DfCb7b8C2659029395Bdf") }
        val tokenTo = "0000000000000000000000000000000000000002".hexToBytes()
        val tokenTx = encodeUnsignedEth(
            EthUnsigned(
                chainId = 1,
                nonce = 3,
                maxPriorityFeePerGas = BigInteger.ONE,
                maxFeePerGas = BigInteger.TEN,
                gasLimit = ERC20_TRANSFER_GAS,
                to = ByteArray(20) { 0x11 },
                value = BigInteger.ZERO,
                data = erc20TransferData(tokenTo, BigInteger("1000000")),
            ),
        )
        val tokenReview = reviewEthereum(tokenTx, 1)
        assertEquals("0x0000000000000000000000000000000000000002", tokenReview.tokenRecipient)
        assertEquals(BigInteger("1000000"), tokenReview.tokenAmount)
        assertEquals(BigInteger.ZERO, tokenReview.value)
        val tokenSigned = signEthereum(tokenTx, priv)
        assertEquals(from, ethereumSender(tokenSigned))
        assertArrayEquals(tokenTx, unsignedFromSignedEth(tokenSigned))
        val transfer = erc20TransferData(tokenTo, BigInteger("1000000"))
        assertThrows(WalletException::class.java) {
            encodeUnsignedEth(
                EthUnsigned(1, 3, BigInteger.ONE, BigInteger.TEN, ERC20_TRANSFER_GAS, ByteArray(20) { 0x11 }, BigInteger.ONE, transfer),
            )
        }
        assertThrows(WalletException::class.java) {
            encodeUnsignedEth(
                EthUnsigned(1, 3, BigInteger.ONE, BigInteger.TEN, NATIVE_TRANSFER_GAS, ByteArray(20) { 0x11 }, BigInteger.ZERO, transfer),
            )
        }
    }

    @Test
    fun rlpAmountsEnvelopeAndSecretBox() {
        assertEquals("80", Rlp.encode(Rlp.uint(0)).toHex())
        assertEquals("01", Rlp.encode(Rlp.uint(1)).toHex())
        assertEquals("c0", Rlp.encode(Rlp.Item.List(emptyList())).toHex())
        assertEquals(10_000_000.toBigInteger(), parseMinor("0.1", 8, "BTC"))
        assertThrows(WalletException::class.java) { parseMinor("0.000000001", 8, "BTC") }
        val envelope = Envelope(1, PayloadKind.UNSIGNED, Chain.BITCOIN, NetworkId.BITCOIN_TESTNET3.asChain(), "abc+/=")
        val decoded = Envelope.decode(envelope.encode())
        assertEquals(envelope, decoded)
        assertThrows(WalletException::class.java) { Envelope.decode("{\"v\":1}") }
        val password = "correct-horse".toCharArray()
        val blob = SecretBox.encrypt("secret-key".toByteArray(), password, iterations = 1_000)
        assertArrayEquals("secret-key".toByteArray(), SecretBox.decrypt(blob, password, iterations = 1_000))
        assertThrows(WalletException::class.java) {
            SecretBox.decrypt(blob, "wrong-password".toCharArray(), iterations = 1_000)
        }
    }

    @Test
    fun erc20DecodesAbiStringBytes32AndDecimals() {
        val dynamic = ByteArray(96)
        dynamic[31] = 32
        dynamic[63] = 4
        "USDC".toByteArray().copyInto(dynamic, 64)
        assertEquals("USDC", Erc20.decodeString(dynamic))
        val word = ByteArray(32)
        "MKR".toByteArray().copyInto(word, 0)
        assertEquals("MKR", Erc20.decodeString(word))
        val decimals = ByteArray(32)
        decimals[31] = 6
        assertEquals(6.toBigInteger(), Erc20.decodeUint(decimals))
    }

    @Test
    fun standardGasTierMatchesTheQuotedNetworkPrice() {
        val gwei = BigInteger("1000000000")
        val tiers = evmGasTiers(quoted = gwei.multiply(BigInteger.valueOf(16)), base = gwei.multiply(BigInteger.valueOf(15)), highTip = gwei.multiply(BigInteger.valueOf(2)))
        assertEquals("标准", tiers[1].label)
        assertEquals(gwei.multiply(BigInteger.valueOf(16)), tiers[1].maxFee)
        assertEquals(gwei, tiers[1].tip)
        assertTrue(tiers[0].maxFee < tiers[1].maxFee)
        assertTrue(tiers[2].maxFee > tiers[1].maxFee)
        assertTrue(tiers.all { it.maxFee >= it.tip && it.maxFee.signum() > 0 })
        val cheap = evmGasTiers(quoted = BigInteger.valueOf(50_000_000), base = BigInteger.ZERO)
        assertEquals(BigInteger.valueOf(50_000_000), cheap[1].maxFee)
        assertEquals(BigInteger.valueOf(40_000_000), cheap[0].maxFee)
        assertEquals(BigInteger.valueOf(62_500_000), cheap[2].maxFee)
        assertTrue(cheap[1].maxFee < BigInteger("1000000000"))
    }
}
