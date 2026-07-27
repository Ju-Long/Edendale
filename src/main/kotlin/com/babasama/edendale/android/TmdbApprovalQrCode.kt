package com.babasama.edendale.android

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import androidx.compose.ui.res.stringResource

@Composable
fun TmdbApprovalQrCode(
    approvalUrl: String,
    isTelevision: Boolean,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(approvalUrl) { qrBitmap(approvalUrl) }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(EdendaleRadii.Soft.dp),
        color = EdendaleColors.QrCodeBackground,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.tmdb_qr_content_description),
            modifier = Modifier
                .padding(20.dp)
                .size(if (isTelevision) 280.dp else 208.dp),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None,
        )
    }
}

private fun qrBitmap(value: String): Bitmap {
    val size = 512
    val matrix = QRCodeWriter().encode(
        value,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 4,
        ),
    )
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) {
                android.graphics.Color.BLACK
            } else {
                android.graphics.Color.WHITE
            }
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
