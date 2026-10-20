package com.offlinewallet.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.offlinewallet.Screen
import com.offlinewallet.WalletViewModel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sin

@Composable
fun ScanScreen(purpose: ScanPurpose, vm: WalletViewModel) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    var manual by remember { mutableStateOf("") }
    val resumeAfterMiss = remember { AtomicBoolean(false) }
    val paused = remember { AtomicBoolean(false) }
    SideEffect { paused.set(vm.broadcasting) }
    LaunchedEffect(vm.broadcasting) {
        if (!vm.broadcasting && vm.screen is Screen.Scan && vm.error != null) resumeAfterMiss.set(true)
    }
    val title = when (purpose) {
        ScanPurpose.ADDRESS -> "扫描地址"
        ScanPurpose.UNSIGNED -> "扫描未签名交易"
        ScanPurpose.SIGNED -> "扫描已签名交易"
    }
    Box(Modifier.fillMaxSize().background(Ink)) {
        if (granted) {
            CameraPreview(paused = paused, resumeAfterMiss = resumeAfterMiss) { text ->
                playScanCue(context)
                try {
                    vm.onScanned(purpose, text)
                } finally {
                    if (!vm.broadcasting && vm.screen is Screen.Scan) resumeAfterMiss.set(true)
                }
            }
        }
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
                PrimaryButton("使用粘贴内容", enabled = !vm.broadcasting) { vm.onScanned(purpose, manual) }
                QuietButton("取消", onClick = vm::back)
            }
        }
        if (vm.broadcasting) SendingOverlay()
    }
}

@Composable
private fun SendingOverlay() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Ink.copy(alpha = 0.82f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Raised)
                .padding(horizontal = 36.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(Modifier.size(42.dp), color = Copper, strokeWidth = 3.dp)
            Gap(16)
            Text("发送中", color = Ivory, style = MaterialTheme.typography.titleLarge)
            Gap(6)
            Body("正在广播这笔交易", Muted)
        }
    }
}

@Composable
private fun CameraPreview(
    paused: AtomicBoolean,
    resumeAfterMiss: AtomicBoolean,
    onText: (String) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val gate = remember { AtomicBoolean(true) }
    val misses = remember { AtomicInteger(0) }
    val onTextState = rememberUpdatedState(onText)
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
                        if (paused.get()) return@setAnalyzer
                        val text = decode(image)
                        if (text == null) {
                            if (resumeAfterMiss.get() && misses.incrementAndGet() >= 8) {
                                resumeAfterMiss.set(false)
                                misses.set(0)
                                gate.set(true)
                            }
                        } else {
                            misses.set(0)
                            if (gate.compareAndSet(true, false)) {
                                ContextCompat.getMainExecutor(ctx).execute { onTextState.value(text) }
                            }
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

private fun playScanCue(context: Context) {
    runCatching { vibrate(context) }
    runCatching { beep() }
}

private fun vibrate(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        context.getSystemService(Vibrator::class.java)
    } ?: return
    if (!vibrator.hasVibrator()) return
    vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
}

private fun beep() {
    val sampleRate = 22050
    val toneFrames = (sampleRate * 0.12).toInt()
    val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(toneFrames * 2)
    val frames = minBytes / 2
    val pcm = ByteArray(frames * 2)
    for (i in 0 until toneFrames) {
        val env = when {
            i < toneFrames * 0.08 -> i / (toneFrames * 0.08)
            i > toneFrames * 0.7 -> (toneFrames - i) / (toneFrames * 0.3)
            else -> 1.0
        }.coerceIn(0.0, 1.0)
        val sample = (sin(2.0 * Math.PI * 1850.0 * i / sampleRate) * env * 0.8 * 32767.0).toInt()
        pcm[i * 2] = (sample and 0xff).toByte()
        pcm[i * 2 + 1] = ((sample shr 8) and 0xff).toByte()
    }
    val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setTransferMode(AudioTrack.MODE_STATIC)
        .setBufferSizeInBytes(pcm.size)
        .build()
    try {
        if (track.write(pcm, 0, pcm.size) < 0) {
            track.release()
            return
        }
        track.setVolume(1f)
        track.play()
        Handler(Looper.getMainLooper()).postDelayed({ runCatching { track.release() } }, 700)
    } catch (_: Exception) {
        runCatching { track.release() }
    }
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
