package ua.ukrainedrones

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Restarts the monitoring service after a reboot and after an in-app update replaces the
 * package — without a BOOT_COMPLETED hook the app would sit silent until the user opens it.
 * Respects the user's "Restart monitoring after reboot" choice in Settings: if it is off,
 * the service is not restarted until the app itself is opened.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val bootRestart = runBlocking {
                    UserPrefs(context.applicationContext).bootRestartEnabled().first()
                }
                if (bootRestart) {
                    AlertService.start(context)
                }
            }
        }
    }
}