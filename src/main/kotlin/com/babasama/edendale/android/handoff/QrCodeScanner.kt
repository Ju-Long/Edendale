package com.babasama.edendale.android.handoff

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.babasama.edendale.android.BodyCopyStyle
import com.babasama.edendale.android.EdendaleColors
import com.babasama.edendale.android.EdendaleRadii
import com.babasama.edendale.android.R
import com.babasama.edendale.handoff.HandoffV2
import com.babasama.edendale.handoff.PairingFlow
import com.babasama.edendale.handoff.QrFrameDecoder
import com.babasama.edendale.sync.SyncLog
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The camera view that reads another device's pairing code on a phone or
 * tablet, as `QRScannerView` does on iPhone and iPad: the camera is asked
 * for when the viewer chooses to scan, frames are read on the device and
 * never kept, and the first `edendale://handoff` code seen is handed back
 * once; other codes are ignored. Without the camera (refused, or none that
 * opens) it says so and points to showing a code instead.
 */
@Composable
fun QrCodeScanner(onCode: (HandoffV2.QrPayload) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var refused by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        granted = allowed
        refused = !allowed
    }
    LaunchedEffect(Unit) {
        if (!granted) permission.launch(Manifest.permission.CAMERA)
    }
    val shape = RoundedCornerShape(EdendaleRadii.Card.dp)
    Box(
        modifier = modifier.fillMaxWidth().height(SCANNER_HEIGHT.dp).clip(shape).background(EdendaleColors.SurfaceLow),
        contentAlignment = Alignment.Center,
    ) {
        when {
            granted && !failed -> CameraPreview(onCode = onCode, onFailure = { failed = true })
            refused || failed -> Text(
                stringResource(R.string.pairing_camera_unavailable),
                style = BodyCopyStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp),
            )
            else -> Unit
        }
    }
}

@Composable
private fun CameraPreview(onCode: (HandoffV2.QrPayload) -> Unit, onFailure: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCode by rememberUpdatedState(onCode)
    val currentOnFailure by rememberUpdatedState(onFailure)
    // TextureView, so the preview clips to the card's rounded corners inside the dialog.
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val main = Handler(Looper.getMainLooper())
        val delivered = AtomicBoolean(false)
        val decoder = QrFrameDecoder()
        var frame = ByteArray(0)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build(),
            )
            .build()
        analysis.setAnalyzer(executor) { image: ImageProxy ->
            image.use {
                if (delivered.get()) return@use
                val plane = it.planes[0]
                val buffer = plane.buffer.apply { rewind() }
                val size = buffer.remaining()
                if (frame.size < size) frame = ByteArray(size)
                buffer.get(frame, 0, size)
                val text = runCatching { decoder.decode(frame, plane.rowStride, it.width, it.height) }.getOrNull()
                val payload = PairingFlow.scanned(text) ?: return@use
                if (delivered.compareAndSet(false, true)) main.post { if (!disposed) currentOnCode(payload) }
            }
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                if (disposed) return@addListener
                try {
                    val cameras = future.get()
                    provider = cameras
                    val selector = when {
                        cameras.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                        cameras.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                        else -> null
                    }
                    if (selector == null) {
                        currentOnFailure()
                        return@addListener
                    }
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    cameras.unbindAll()
                    cameras.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                } catch (error: Exception) {
                    SyncLog.log(TAG, "camera failed: ${SyncLog.describe(error)}")
                    currentOnFailure()
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            disposed = true
            analysis.clearAnalyzer()
            provider?.unbindAll()
            executor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/** The scanner card's height, Apple's 360 points. */
private const val SCANNER_HEIGHT = 360

private const val TAG = "PairingScanner"
