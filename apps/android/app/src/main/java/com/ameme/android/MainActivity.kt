package com.ameme.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ameme.android.data.transport.PairingQrScanFailure
import com.ameme.android.ui.AmemeApp
import com.ameme.android.ui.theme.AmemeTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ameme.android.data.source.IncomingShareParser
import com.ameme.android.domain.SourceCaptureRequest
import com.ameme.android.domain.LocatorPermissionState
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

class MainActivity : ComponentActivity() {
    private var pendingShare by mutableStateOf<SourceCaptureRequest?>(null)
    private var pairingQrScanPayload by mutableStateOf<String?>(null)
    private var pairingQrScanFailure by mutableStateOf<PairingQrScanFailure?>(null)
    private var pairingQrScanInFlight = false
    private val pairingQrScanner by lazy(LazyThreadSafetyMode.NONE) {
        GmsBarcodeScanning.getClient(
            this,
            GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build(),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingShare = parseIncomingShare(intent)
        enableEdgeToEdge()
        setContent {
            AmemeTheme {
                AmemeApp(
                    incomingShare = pendingShare,
                    onIncomingShareConsumed = { pendingShare = null },
                    pairingQrScannerAvailable = true,
                    pairingQrScanPayload = pairingQrScanPayload,
                    pairingQrScanFailure = pairingQrScanFailure,
                    onStartPairingQrScan = ::startPairingQrScan,
                    onPairingQrScanConsumed = {
                        pairingQrScanPayload = null
                        pairingQrScanFailure = null
                    },
                )
            }
        }
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveIncomingIntent(intent)
    }

    /** Routes a new share into the same pending-review path used by Android's lifecycle callback. */
    fun receiveIncomingIntent(intent: Intent) {
        pendingShare = parseIncomingShare(intent)
    }

    private fun parseIncomingShare(intent: Intent): SourceCaptureRequest? {
        return IncomingShareParser().parse(intent) { _, _ -> LocatorPermissionState.SessionRead }
    }

    private fun startPairingQrScan() {
        if (pairingQrScanInFlight) return
        pairingQrScanPayload = null
        pairingQrScanFailure = null
        if (
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) !=
            ConnectionResult.SUCCESS
        ) {
            pairingQrScanFailure = PairingQrScanFailure.ModuleUnavailable
            return
        }
        pairingQrScanInFlight = true
        runCatching { pairingQrScanner.startScan() }
            .onSuccess { task ->
                task
                    .addOnSuccessListener { barcode ->
                        pairingQrScanInFlight = false
                        val payload = barcode.rawValue
                        if (
                            payload.isNullOrBlank() ||
                            payload.length > 16_384
                        ) {
                            pairingQrScanFailure = PairingQrScanFailure.InvalidResult
                        } else {
                            pairingQrScanPayload = payload
                        }
                    }
                    .addOnCanceledListener {
                        pairingQrScanInFlight = false
                        pairingQrScanFailure = PairingQrScanFailure.Cancelled
                    }
                    .addOnFailureListener {
                        pairingQrScanInFlight = false
                        pairingQrScanFailure = PairingQrScanFailure.Failed
                    }
            }
            .onFailure {
                pairingQrScanInFlight = false
                pairingQrScanFailure = PairingQrScanFailure.ModuleUnavailable
            }
    }
}
