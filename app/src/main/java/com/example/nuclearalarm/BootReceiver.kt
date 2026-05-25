package com.example.nuclearalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE)
            val isActive = prefs.getBoolean("IS_ACTIVE", false)

            if (isActive) {
                val serviceIntent = Intent(context, AlarmTrapService::class.java)
                serviceIntent.putExtra("PENALTY_MODE", true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}