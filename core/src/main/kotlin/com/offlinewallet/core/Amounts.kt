package com.offlinewallet.core

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

fun parseMinor(text: String, scale: Int, unit: String): BigInteger {
    val trimmed = text.trim()
    if (!trimmed.matches(Regex("\\d+(\\.\\d+)?"))) throw WalletException("$unit 金额格式不正确")
    val parts = trimmed.split('.')
    val fraction = if (parts.size == 2) parts[1] else ""
    if (fraction.length > scale) throw WalletException("$unit 小数位最多 $scale 位")
    val digits = parts[0] + fraction.padEnd(scale, '0')
    val normalized = digits.trimStart('0')
    if (normalized.isEmpty()) throw WalletException("金额必须大于 0")
    return BigInteger(normalized)
}

fun formatMinor(value: BigInteger, scale: Int): String {
    val negative = value.signum() < 0
    val digits = value.abs().toString().padStart(scale + 1, '0')
    val whole = digits.dropLast(scale)
    val fraction = digits.takeLast(scale).trimEnd('0')
    val body = if (fraction.isEmpty()) whole else "$whole.$fraction"
    return if (negative) "-$body" else body
}

fun formatBalance(value: BigInteger, scale: Int, fractionDigits: Int = 8): String {
    val negative = value.signum() < 0
    val digits = value.abs().toString().padStart(scale + 1, '0')
    val whole = digits.dropLast(scale)
    val kept = digits.takeLast(scale).take(fractionDigits.coerceAtMost(scale)).trimEnd('0')
    val body = if (kept.isEmpty()) whole else "$whole.$kept"
    return if (negative) "-$body" else body
}

fun formatBtc(sats: Long): String = formatMinor(BigInteger.valueOf(sats), 8)

fun formatEth(wei: BigInteger): String = formatMinor(wei, 18)

fun formatGwei(wei: BigInteger): String {
    val gwei = BigDecimal(wei).divide(BigDecimal("1000000000"), 9, RoundingMode.HALF_UP).stripTrailingZeros()
    return gwei.toPlainString()
}

fun formatSats(sats: Long): String = "%,d".format(java.util.Locale.US, sats)
