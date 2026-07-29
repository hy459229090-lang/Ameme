package com.ameme.android.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

@Composable
fun PairingQrCode(
    payload: String,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(payload) { runCatching { encodePairingQrCode(payload) }.getOrNull() }
    if (bitmap == null) {
        Text(
            "二维码暂时无法生成；请关闭后重试。",
            color = MaterialTheme.colorScheme.error,
            modifier = modifier.testTag("pairing-qr-error"),
        )
        return
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        filterQuality = FilterQuality.None,
        modifier = modifier
            .aspectRatio(1f)
            .testTag("pairing-qr-image")
            .semantics { contentDescription = "设备配对二维码，五分钟内有效" },
    )
}

internal fun encodePairingQrCode(payload: String, size: Int = 768): Bitmap {
    require(payload.startsWith("ameme-pairing-v2:"))
    require(payload.length <= 16_384)
    require(size in 256..1_024)
    val matrix = QRCodeWriter().encode(
        payload,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(
            EncodeHintType.CHARACTER_SET to Charsets.UTF_8.name(),
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
        ),
    )
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        val offset = y * size
        for (x in 0 until size) {
            pixels[offset + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
