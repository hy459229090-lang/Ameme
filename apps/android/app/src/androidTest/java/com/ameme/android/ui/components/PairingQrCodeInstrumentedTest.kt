package com.ameme.android.ui.components

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairingQrCodeInstrumentedTest {
    @Test
    fun renderedQrRoundTripsAFullSizedPairingPayload() {
        val payload = "ameme-pairing-v1:" + "A".repeat(900)
        val bitmap = encodePairingQrCode(payload, size = 768)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        val decoded = QRCodeReader().decode(
            BinaryBitmap(
                HybridBinarizer(
                    RGBLuminanceSource(bitmap.width, bitmap.height, pixels),
                ),
            ),
        )

        assertEquals(payload, decoded.text)
    }
}
