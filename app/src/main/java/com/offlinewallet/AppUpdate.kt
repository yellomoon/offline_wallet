package com.offlinewallet

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.offlinewallet.core.WalletException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

sealed interface UpdateNotice {
    data object Latest : UpdateNotice
    data class Available(val release: RemoteRelease) : UpdateNotice
    data class Failed(val message: String) : UpdateNotice
}

data class RemoteRelease(
    val version: String,
    val notes: String,
    val apkUrl: String?,
)

object AppUpdate {
    private const val LATEST = "https://api.github.com/repos/yellomoon/offline_wallet/releases/latest"
    private val http = OkHttpClient.Builder()
        .callTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun latest(): RemoteRelease? {
        val request = Request.Builder()
            .url(LATEST)
            .header("User-Agent", "offline-wallet")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw WalletException(text.trim().take(180).ifBlank { "检查更新失败 ${response.code}" })
            }
            val json = JSONObject(text)
            val tag = json.optString("tag_name").ifBlank { json.optString("name") }
            if (tag.isBlank()) return null
            val assets = json.optJSONArray("assets")
            var apk: String? = null
            if (assets != null) {
                for (index in 0 until assets.length()) {
                    val asset = assets.getJSONObject(index)
                    val name = asset.optString("name")
                    if (!name.endsWith(".apk", true)) continue
                    apk = asset.optString("browser_download_url")
                    if (name.equals("offline-wallet.apk", true)) break
                }
            }
            return RemoteRelease(tag, json.optString("body").trim(), apk?.takeIf { it.isNotBlank() })
        }
    }

    fun isNewer(remote: String, local: String): Boolean {
        val left = versionParts(remote)
        val right = versionParts(local)
        val size = maxOf(left.size, right.size)
        for (index in 0 until size) {
            val a = left.getOrElse(index) { 0 }
            val b = right.getOrElse(index) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    fun download(url: String, dest: File) {
        dest.parentFile?.mkdirs()
        val request = Request.Builder().url(url).header("User-Agent", "offline-wallet").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw WalletException("下载失败 ${response.code}")
            val body = response.body ?: throw WalletException("下载失败")
            dest.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        if (dest.length() < 1000L) throw WalletException("下载到的文件不是安装包")
    }

    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun versionParts(raw: String): List<Int> {
        val body = raw.trim().removePrefix("v").removePrefix("V")
        return body.split('.', '-', '_').take(3).map { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
    }
}
