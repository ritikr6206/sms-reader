package com.smsreader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restarts the ForwarderService after device reboot
 * so SMS forwarding continues automatically.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Device booted — starting ForwarderService")

            val prefs = context.getSharedPreferences("sms_reader_config", Context.MODE_PRIVATE)
            val isEnabled = prefs.getBoolean("service_enabled", false)

            if (isEnabled) {
                val serviceIntent = Intent(context, ForwarderService::class.java).apply {
                    action = ForwarderService.ACTION_START_FOREGROUND
                }
                try {
                    context.startForegroundService(serviceIntent)
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Failed to start service after boot", e)
                }
            }
        }
    }
}
