package ua.ukrainedrones

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** Info about the current device manufacturer's battery-restriction behavior. */
data class OemInfo(
    val manufacturer: String,
    val isAggressive: Boolean,
    val hasAutoStartIntent: Boolean
)

/** Helpers for the "keep monitoring alive" battery-exemption flow. */
object BatteryOptimization {

    private const val PREF_NAME = "battery_sim"
    private const val KEY_SIM_OEM = "sim_oem"

    /** True when the OS already lets this app run unrestricted in the background. */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Detects the device manufacturer and returns battery-restriction info.
     * On Pixel/Nexus devices this returns `isAggressive = false` because stock Android
     * does not impose OEM-specific background restrictions beyond Doze.
     *
     * Call [setSimulatedOem] to override for testing on non-OEM devices.
     */
    fun getOemInfo(context: Context? = null): OemInfo {
        // Check for simulation override (for testing on Pixel etc.)
        val simOverride = getSimulatedOem(context)
        val raw = simOverride ?: Build.MANUFACTURER
        val mfg = raw.lowercase().trim()

        return when {
            mfg.contains("xiaomi") || mfg.contains("redmi") || mfg.contains("poco") ->
                OemInfo(raw, isAggressive = true, hasAutoStartIntent = true)
            mfg.contains("samsung") ->
                OemInfo(raw, isAggressive = true, hasAutoStartIntent = false)
            mfg.contains("huawei") || mfg.contains("honor") ->
                OemInfo(raw, isAggressive = true, hasAutoStartIntent = true)
            mfg.contains("oppo") || mfg.contains("realme") || mfg.contains("oneplus") ->
                OemInfo(raw, isAggressive = true, hasAutoStartIntent = true)
            mfg.contains("vivo") ->
                OemInfo(raw, isAggressive = true, hasAutoStartIntent = false)
            mfg.contains("tecno") || mfg.contains("infinix") || mfg.contains("itel") ->
                OemInfo(raw, isAggressive = true, hasAutoStartIntent = true)
            else ->
                OemInfo(raw, isAggressive = true, hasAutoStartIntent = false)
        }
    }

    /**
     * Get the currently overridden OEM for testing, or null if none is set.
     */
    fun getSimulatedOem(context: Context?): String? {
        return context?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            ?.getString(KEY_SIM_OEM, null)
    }

    /**
     * Override the detected OEM for testing. Pass `null` to clear the override
     * and revert to automatic detection from `Build.MANUFACTURER`.
     */
    fun setSimulatedOem(context: Context, manufacturer: String?) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SIM_OEM, manufacturer).apply()
    }

    /** Shows the system "allow background run?" dialog for this app. */
    fun requestExemption(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
            // Some devices don't offer the dialog — fall back to the general settings list.
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (_: Exception) {
                // No battery-settings intent available at all — nothing to show.
            }
        }
    }

    /**
     * Attempt to open the manufacturer-specific auto-start / battery settings page.
     * Returns `true` if an intent was launched, `false` if none available for this OEM.
     */
    fun requestExemptionOemAware(context: Context): Boolean {
        val oem = getOemInfo(context)
        val intent = when {
            // Xiaomi / Redmi / POCO — MIUI auto-start manager
            oem.manufacturer.lowercase().let { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") } ->
                Intent().apply {
                    component = ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            // Huawei / Honor — launch management
            oem.manufacturer.lowercase().let { it.contains("huawei") || it.contains("honor") } ->
                Intent().apply {
                    component = ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            // Oppo / Realme / OnePlus — ColorOS auto-start
            oem.manufacturer.lowercase().let { it.contains("oppo") || it.contains("realme") || it.contains("oneplus") } ->
                Intent().apply {
                    component = ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.startupapp.StartupAppActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            // Transsion (Tecno / Infinix / Itel) — HiOS auto-start
            oem.manufacturer.lowercase().let { it.contains("tecno") || it.contains("infinix") || it.contains("itel") } ->
                Intent().apply {
                    component = ComponentName(
                        "com.transsion.phonemanager",
                        "com.transsion.phonemanager.view.AutoRunListActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            else -> null
        }

        return if (intent != null) {
            try {
                context.startActivity(intent)
                true
            } catch (_: Exception) {
                // OEM intent not available on this ROM version — fall back to standard
                requestExemption(context)
                false
            }
        } else {
            // No OEM-specific intent — use standard Android dialog
            requestExemption(context)
            false
        }
    }
}
