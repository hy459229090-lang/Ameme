package com.ameme.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ameme.android.ui.AmemeApp
import com.ameme.android.ui.theme.AmemeTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ameme.android.data.source.IncomingShareParser
import com.ameme.android.domain.SourceCaptureRequest
import com.ameme.android.domain.LocatorPermissionState

class MainActivity : ComponentActivity() {
    private var pendingShare by mutableStateOf<SourceCaptureRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingShare = parseIncomingShare(intent)
        enableEdgeToEdge()
        setContent {
            AmemeTheme {
                AmemeApp(
                    incomingShare = pendingShare,
                    onIncomingShareConsumed = { pendingShare = null },
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
}
