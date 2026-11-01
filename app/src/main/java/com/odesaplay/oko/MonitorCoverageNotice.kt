package com.odesaplay.oko

import android.content.Context
import com.odesaplay.oko.domain.MonitorCoverage

/**
 * One writer for the standing coverage notice, so every surface that learns the answer can state
 * it immediately.
 *
 * The service reconciles this on a cadence of its own, which is fine while it is alive and useless
 * exactly when it is not: a blocked monitor is the state where Android stops the service, so the
 * one caller that must never be depended on is the service. [MainViewModel] therefore calls this on
 * resume — the moment the user comes back from the system page having changed the switch — and the
 * two callers cannot disagree because both hand the same [MonitorCoverage] to the same writer.
 *
 * Idempotent on purpose: posting the same id with the same text is a no-op visually, so a duplicate
 * call costs nothing and no "who owns it" state has to be kept.
 */
object MonitorCoverageNotice {

    fun sync(context: Context, coverage: MonitorCoverage, strings: Strings.StringSet) {
        val appContext = context.applicationContext
        val manager = AlertNotificationManager(appContext)
        if (!coverage.stopsBackgroundWork) {
            manager.cancelCoverage()
            return
        }
        manager.postCoverageNotice(strings.coverageTitle, strings.coverageRestrictedBody)
    }
}
