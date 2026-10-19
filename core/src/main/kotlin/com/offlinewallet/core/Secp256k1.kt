package com.offlinewallet.core

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.math.ec.ECAlgorithms
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger

object Secp256k1 {
    private val x9 = SECNamedCurves.getByName("secp256k1")
    val domain = ECDomainParameters(x9.curve, x9.g, x9.n, x9.h)
    val n: BigInteger = x9.n
    val halfN: BigInteger = x9.n.shiftRight(1)

    data class Signature(val r: BigInteger, val s: BigInteger, val recId: Int)

    fun checkPriv(priv: ByteArray) {
        if (priv.size != 32) throw WalletException("私钥必须是 32 字节")
        val d = BigInteger(1, priv)
        if (d.signum() <= 0 || d >= n) throw WalletException("私钥超出 secp256k1 曲线范围")
    }

    fun publicPoint(priv: ByteArray): ECPoint {
        checkPriv(priv)
        return domain.g.multiply(BigInteger(1, priv)).normalize()
    }

    fun compressedPub(priv: ByteArray): ByteArray = publicPoint(priv).getEncoded(true)

    fun uncompressedPub(priv: ByteArray): ByteArray = publicPoint(priv).getEncoded(false)

    fun sign(hash32: ByteArray, priv: ByteArray): Signature {
        if (hash32.size != 32) throw WalletException("待签名摘要长度不对")
        checkPriv(priv)
        val signer = ECDSASigner()
        signer.init(true, ECPrivateKeyParameters(BigInteger(1, priv), domain))
        val parts = signer.generateSignature(hash32)
        var r = parts[0]
        var s = parts[1]
        if (s > halfN) s = n.subtract(s)
        val pub = publicPoint(priv)
        val recId = (0..3).firstOrNull { candidate ->
            val recovered = recoverPoint(candidate, r, s, hash32) ?: return@firstOrNull false
            recovered.equals(pub)
        } ?: throw WalletException("无法计算签名恢复参数")
        return Signature(r, s, recId)
    }

    fun verify(hash32: ByteArray, r: BigInteger, s: BigInteger, pub: ByteArray): Boolean {
        if (r.signum() <= 0 || s.signum() <= 0 || r >= n || s > halfN) return false
        return try {
            val point = domain.curve.decodePoint(pub)
            val verifier = ECDSASigner()
            verifier.init(false, ECPublicKeyParameters(point, domain))
            verifier.verifySignature(hash32, r, s)
        } catch (_: Exception) {
            false
        }
    }

    fun recoverPoint(recId: Int, r: BigInteger, s: BigInteger, hash32: ByteArray): ECPoint? {
        if (recId !in 0..3 || r.signum() <= 0 || s.signum() <= 0 || r >= n || s >= n) return null
        val field = domain.curve.field.characteristic
        val x = r.add(BigInteger.valueOf((recId / 2).toLong()).multiply(n))
        if (x >= field) return null
        val compressed = ByteArray(33)
        compressed[0] = if ((recId and 1) == 1) 0x03 else 0x02
        val xBytes = x.toFixed32()
        xBytes.copyInto(compressed, 1)
        val pointR = try {
            domain.curve.decodePoint(compressed)
        } catch (_: Exception) {
            return null
        }
        if (!pointR.multiply(n).isInfinity) return null
        val e = BigInteger(1, hash32)
        val rInv = r.modInverse(n)
        val srInv = s.multiply(rInv).mod(n)
        val eInv = e.negate().mod(n).multiply(rInv).mod(n)
        val q = ECAlgorithms.sumOfTwoMultiplies(pointR, srInv, domain.g, eInv).normalize()
        return if (q.isInfinity) null else q
    }

    fun taggedHash(tag: String, message: ByteArray): ByteArray {
        val tagHash = sha256(tag.toByteArray(Charsets.UTF_8))
        return sha256(tagHash + tagHash + message)
    }

    fun schnorrSign(message: ByteArray, priv: ByteArray, aux: ByteArray): ByteArray {
        if (aux.size != 32) throw WalletException("Schnorr 随机数必须是 32 字节")
        val secret = evenSecret(priv)
        val d = secret.first
        val px = secret.second
        val t = xor32(d.toFixed32(), taggedHash("BIP0340/aux", aux))
        val rand = taggedHash("BIP0340/nonce", t + px + message)
        val k0 = BigInteger(1, rand).mod(n)
        if (k0.signum() == 0) throw WalletException("Schnorr nonce 无效")
        var kPoint = domain.g.multiply(k0).normalize()
        var k = k0
        if (!hasEvenY(kPoint)) {
            k = n.subtract(k0)
            kPoint = kPoint.negate().normalize()
        }
        val rx = xOnly(kPoint)
        val e = BigInteger(1, taggedHash("BIP0340/challenge", rx + px + message)).mod(n)
        val s = k.add(e.multiply(d)).mod(n)
        return rx + s.toFixed32()
    }

    fun schnorrVerify(message: ByteArray, signature: ByteArray, xOnlyPub: ByteArray): Boolean {
        if (signature.size != 64 || xOnlyPub.size != 32) return false
        val r = BigInteger(1, signature.copyOfRange(0, 32))
        val s = BigInteger(1, signature.copyOfRange(32, 64))
        if (s.signum() <= 0 || s >= n) return false
        val pointR = liftX(r) ?: return false
        val pointP = liftX(BigInteger(1, xOnlyPub)) ?: return false
        val e = BigInteger(1, taggedHash("BIP0340/challenge", signature.copyOfRange(0, 32) + xOnlyPub + message)).mod(n)
        val computed = ECAlgorithms.sumOfTwoMultiplies(domain.g, s, pointP, n.subtract(e)).normalize()
        return !computed.isInfinity && hasEvenY(computed) && xOnly(computed).contentEquals(xOnly(pointR))
    }

    fun taprootTweakedKey(priv: ByteArray): Pair<ByteArray, ByteArray> {
        val secret = evenSecret(priv)
        val tweak = BigInteger(1, taggedHash("TapTweak", secret.second)).mod(n)
        val internal = liftX(BigInteger(1, secret.second)) ?: throw WalletException("Taproot 内部公钥无效")
        var output = internal.add(domain.g.multiply(tweak)).normalize()
        var tweaked = secret.first.add(tweak).mod(n)
        if (tweaked.signum() == 0) throw WalletException("Taproot 私钥无效")
        if (!hasEvenY(output)) {
            tweaked = n.subtract(tweaked)
            output = output.negate().normalize()
        }
        return tweaked.toFixed32() to xOnly(output)
    }

    private fun evenSecret(priv: ByteArray): Pair<BigInteger, ByteArray> {
        checkPriv(priv)
        val point = publicPoint(priv)
        val secret = BigInteger(1, priv)
        val d = if (hasEvenY(point)) secret else n.subtract(secret)
        val even = if (hasEvenY(point)) point else point.negate().normalize()
        return d to xOnly(even)
    }

    private fun xOnly(point: ECPoint): ByteArray = point.normalize().affineXCoord.toBigInteger().toFixed32()

    private fun hasEvenY(point: ECPoint): Boolean = !point.normalize().affineYCoord.toBigInteger().testBit(0)

    private fun liftX(x: BigInteger): ECPoint? {
        if (x.signum() < 0 || x >= domain.curve.field.characteristic) return null
        val compressed = ByteArray(33)
        compressed[0] = 0x02
        x.toFixed32().copyInto(compressed, 1)
        return try {
            val point = domain.curve.decodePoint(compressed).normalize()
            if (point.isInfinity || !point.multiply(n).isInfinity) null else point
        } catch (_: Exception) {
            null
        }
    }

    private fun xor32(left: ByteArray, right: ByteArray): ByteArray {
        val out = ByteArray(32)
        for (index in 0 until 32) out[index] = (left[index].toInt() xor right[index].toInt()).toByte()
        return out
    }

    fun derEncode(r: BigInteger, s: BigInteger): ByteArray {
        val rb = derInteger(r)
        val sb = derInteger(s)
        val len = rb.size + sb.size
        if (len > 127) throw WalletException("签名编码过长")
        val out = ByteArray(2 + len)
        out[0] = 0x30
        out[1] = len.toByte()
        rb.copyInto(out, 2)
        sb.copyInto(out, 2 + rb.size)
        return out
    }

    fun derDecode(der: ByteArray): Pair<BigInteger, BigInteger> {
        if (der.size < 8 || der[0] != 0x30.toByte()) throw WalletException("签名不是 DER 编码")
        val seqLen = der[1].toInt() and 0xff
        if (seqLen != der.size - 2) throw WalletException("签名长度不正确")
        var i = 2
        if (der[i] != 0x02.toByte()) throw WalletException("签名不是 DER 编码")
        val rLen = der[i + 1].toInt() and 0xff
        i += 2
        val r = BigInteger(1, der.copyOfRange(i, i + rLen))
        i += rLen
        if (i >= der.size || der[i] != 0x02.toByte()) throw WalletException("签名不是 DER 编码")
        val sLen = der[i + 1].toInt() and 0xff
        i += 2
        val s = BigInteger(1, der.copyOfRange(i, i + sLen))
        if (i + sLen != der.size) throw WalletException("签名长度不正确")
        return r to s
    }

    private fun derInteger(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(2 + raw.size)
        out[0] = 0x02
        out[1] = raw.size.toByte()
        raw.copyInto(out, 2)
        return out
    }
}

fun BigInteger.toFixed32(): ByteArray {
    val raw = toByteArray()
    if (raw.size == 32) return raw
    if (raw.size == 33 && raw[0] == 0.toByte()) return raw.copyOfRange(1, 33)
    if (raw.size > 32) throw WalletException("整数超过 32 字节")
    val out = ByteArray(32)
    raw.copyInto(out, 32 - raw.size)
    return out
}
