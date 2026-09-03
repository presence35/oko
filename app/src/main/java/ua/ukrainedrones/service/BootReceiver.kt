package ua.ukrainedrones

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Restarts the monitoring service after a reboot and after an in-app update replaces the
 * package — without a BOOT_COMPLETED hook the app would sit silent until the user opens it.
 * Respects the user's "Stop Monitoring & Exit" choice: if monitoring_enabled is false,
 * the service is not restarted.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val monitoringEnabled = runBlocking {
                    UserPrefs(context.applicationContext).monitoringEnabled().first()
                }
                if (monitoringEnabled) {
                    AlertService.start(context)
                }
            }
        }
    }
}