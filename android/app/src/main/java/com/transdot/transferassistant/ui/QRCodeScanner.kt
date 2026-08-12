package com.transdot.transferassistant.ui

import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

@Composable
fun QRCodeScanner(
    onQRCode: (String) -> Unit,
    onCameraError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentCallback = rememberUpdatedState(onQRCode)
    val currentErrorCallback = rememberUpdatedState(onCameraError)
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(lifecycleOwner, previewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor, QRCodeAnalyzer { currentCallback.value(it) })
            }
        var cameraProvider: ProcessCameraProvider? = null
        var disposed = false

        cameraProviderFuture.addListener(
            {
                if (disposed) return@addListener
                runCatching {
                    cameraProvider = cameraProviderFuture.get().also { provider ->
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis,
                        )
                    }
                }.onFailure {
                    currentErrorCallback.value("无法启动相机，请改用 6 位配对码。")
                }
            },
            ContextCompat.getMainExecutor(context),
        )

        onDispose {
            disposed = true
            imageAnalysis.clearAnalyzer()
            cameraProvider?.unbind(preview, imageAnalysis)
            cameraExecutor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

private class QRCodeAnalyzer(
    private val onQRCode: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }
    private var lastValue: String? = null
    private var lastEmittedAt = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes.firstOrNull() ?: return
            val buffer = plane.buffer.duplicate().apply { rewind() }
            val luminance = ByteArray(buffer.remaining()).also(buffer::get)
            val dataWidth = plane.rowStride
            val dataHeight = luminance.size / dataWidth
            val cropWidth = image.width.coerceAtMost(dataWidth)
            val cropHeight = image.height.coerceAtMost(dataHeight)
            if (cropWidth <= 0 || cropHeight <= 0) return

            val source = PlanarYUVLuminanceSource(
                luminance,
                dataWidth,
                dataHeight,
                0,
                0,
                cropWidth,
                cropHeight,
                false,
            )
            val value = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
            val now = SystemClock.elapsedRealtime()
            if (value != lastValue || now - lastEmittedAt >= EMIT_COOLDOWN_MS) {
                lastValue = value
                lastEmittedAt = now
                onQRCode(value)
            }
        } catch (_: NotFoundException) {
            // A frame without a QR code is the normal scanning state.
        } catch (_: RuntimeException) {
            // Some devices can briefly return an incomplete YUV frame; keep scanning.
        } finally {
            reader.reset()
            image.close()
        }
    }

    private companion object {
        const val EMIT_COOLDOWN_MS = 2_000L
    }
}
