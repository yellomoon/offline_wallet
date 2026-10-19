package com.offlinewallet.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.offlinewallet.ScanPurpose
import com.offlinewallet.WalletViewModel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun ScanScreen(purpose: ScanPurpose, vm: WalletViewModel) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    var manual by remember { mutableStateOf("") }
    val title = when (purpose) {
        ScanPurpose.ADDRESS -> "扫描地址"
        ScanPurpose.UNSIGNED -> "扫描未签名交易"
        ScanPurpose.SIGNED -> "扫描已签名交易"
    }
    Box(Modifier.fillMaxSize().background(Ink)) {
        if (granted) CameraPreview(onText = { vm.onScanned(purpose, it) })
        Column(Modifier.fillMaxSize().navigationBarsPadding().padding(20.dp)) {
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Raised)
                    .padding(16.dp),
            ) {
                Text(title, color = Ivory)
                vm.error?.let { Text(it, color = Danger, modifier = Modifier.padding(top = 8.dp)) }
                if (!granted) {
                    Text("需要相机权限。也可以把报文粘贴在下面。", color = Dim, modifier = Modifier.padding(top = 8.dp))
                }
                Gap(10)
                WalletField(manual, { manual = it }, "粘贴报文", minLines = 2)
                Gap(10)
                PrimaryButton("使用粘贴内容") { vm.onScanned(purpose, manual) }
                QuietButton("取消", onClick = vm::back)
            }
        }
    }
}

@Composable
private fun CameraPreview(onText: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val seen = remember { AtomicBoolean(false) }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val view = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val camera = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { image ->
                    try {
                        val text = decode(image)
                        if (text != null && seen.compareAndSet(false, true)) {
                            ContextCompat.getMainExecutor(ctx).execute { onText(text) }
                        }
                    } finally {
                        image.close()
                    }
                }
                camera.unbindAll()
                camera.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(ctx))
            view
        },
    )
}

private fun decode(image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer.duplicate()
    buffer.rewind()
    val width = image.width
    val height = image.height
    val luma = ByteArray(width * height)
    var offset = 0
    for (row in 0 until height) {
        var pos = row * plane.rowStride
        for (col in 0 until width) {
            if (pos >= buffer.limit()) return null
            luma[offset++] = buffer.get(pos)
            pos += plane.pixelStride
        }
    }
    return decodeLuma(luma, width, height) ?: decodeLuma(rotate(luma, width, height, image.imageInfo.rotationDegrees), rotatedWidth(width, height, image.imageInfo.rotationDegrees), rotatedHeight(width, height, image.imageInfo.rotationDegrees))
}

private fun decodeLuma(luma: ByteArray, width: Int, height: Int): String? {
    if (width <= 0 || height <= 0 || luma.size < width * height) return null
    val source = PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false)
    val reader = MultiFormatReader()
    reader.setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.CHARACTER_SET to "UTF-8"))
    return try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
    }
}

private fun rotatedWidth(width: Int, height: Int, degrees: Int) = if (degrees == 90 || degrees == 270) height else width
private fun rotatedHeight(width: Int, height: Int, degrees: Int) = if (degrees == 90 || degrees == 270) width else height

private fun rotate(src: ByteArray, width: Int, height: Int, degrees: Int): ByteArray {
    if (degrees == 0) return src
    val out = ByteArray(src.size)
    when (degrees) {
        90 -> for (y in 0 until height) for (x in 0 until width) out[x * height + (height - 1 - y)] = src[y * width + x]
        270 -> for (y in 0 until height) for (x in 0 until width) out[(width - 1 - x) * height + y] = src[y * width + x]
        180 -> for (i in src.indices) out[src.size - 1 - i] = src[i]
        else -> return src
    }
    return out
}
