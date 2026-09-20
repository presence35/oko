package com.presaince.oko

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Delete intent for the neutralized-threats tally notification: when the user swipes it away,
 * tell [AlertService] to reset the count so a later neutralization starts a fresh tally instead
 * of resurrecting the dismissed one.
 */
class NeutralizedDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action?.let {
            when (it) {
                AlarmEpisodeTally.ACTION_ALARM_EPISODE_DISMISS -> it
                else -> NeutralizedTally.ACTION_NEUTRALIZED_DISMISS
            }
        } ?: NeutralizedTally.ACTION_NEUTRALIZED_DISMISS
        try {
            context.startService(
                Intent(context, AlertService::class.java)
                    .setAction(action)
            )
        } catch (_: IllegalStateException) {
            // Service not running or background start blocked — safe to ignore;
            // the tally resets on next service start anyway.
        }
    }
}