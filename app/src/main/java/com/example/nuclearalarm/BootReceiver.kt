package com.example.nuclearalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.LOCKED_BOOT_COMPLETED" || action == "android.intent.action.QUICKBOOT_POWERON") {

            val prefs = context.getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE)
            val wasActive = prefs.getBoolean("IS_ACTIVE", false)

            // THE TRAP: If they rebooted while the alarm was actively ringing, punish them immediately upon startup!
            if (wasActive) {
                val serviceIntent = Intent(context, AlarmTrapService::class.java).apply {
                    putExtra("PENALTY_MODE", true)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }

            // Reschedule all enabled alarms from the database
            CoroutineScope(Dispatchers.IO).launch {
                val db = AppDatabase.getDatabase(context)
                val alarms = db.alarmDao().getAllAlarms()

                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

                for (alarm in alarms) {
                    if (alarm.isEnabled) {
                        scheduleAlarm(context, alarmManager, alarm)
                    }
                }
            }
        }
    }

    private fun scheduleAlarm(context: Context, alarmManager: AlarmManager, alarm: AlarmEntity) {
        val alarmIntent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("ALARM_ID", alarm.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, alarm.id, alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) {
                add(Calendar.DATE, 1)
            }
        }

        // Security check for Android 12+ Exact Alarms
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            return
        }

        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
    }
}