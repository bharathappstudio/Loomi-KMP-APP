package com.echo.loomi

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

object BackgroundUtils {

    /**
     * Attempts to open brand-specific settings for Auto-start or Background Activity.
     * This is crucial for brands like Techno, Xiaomi, Oppo, Vivo, and Huawei.
     */
    fun openAutoStartSettings(context: Context) {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val intents = mutableListOf<Intent>()

        when {
            manufacturer.contains("xiaomi") -> {
                intents.add(Intent().apply { component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity") })
            }
            manufacturer.contains("oppo") -> {
                intents.add(Intent().apply { component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity") })
                intents.add(Intent().apply { component = ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity") })
            }
            manufacturer.contains("vivo") -> {
                intents.add(Intent().apply { component = ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity") })
                intents.add(Intent().apply { component = ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager") })
            }
            manufacturer.contains("huawei") -> {
                intents.add(Intent().apply { component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity") })
                intents.add(Intent().apply { component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity") })
            }
            manufacturer.contains("transsion") || manufacturer.contains("tecno") || manufacturer.contains("infinix") || manufacturer.contains("itel") -> {
                // Transsion brands (Tecno/Infinix) use Phone Master / Battery Lab
                intents.add(Intent().apply { component = ComponentName("com.transsion.phonemaster", "com.transsion.phonemaster.MainActivity") })
                intents.add(Intent().apply { action = "com.transsion.phonemaster.action.AUTO_START_MANAGEMENT" })
            }
            manufacturer.contains("samsung") -> {
                intents.add(Intent().apply { component = ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity") })
            }
        }

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return // Stop at the first one that works
            } catch (e: Exception) {
                // Try next
            }
        }
    }
}
