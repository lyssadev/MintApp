package mint.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CancelDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(DownloadService.EXTRA_CANCEL_ID)
        if (id != null) DownloadService.cancel(id)
    }
}