package com.example.nuclearalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: AlarmAdapter
    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = AppDatabase.getDatabase(this)

        // 1. Setup RecyclerView
        val recyclerView = findViewById<RecyclerView>(R.id.alarmRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        // Initialize adapter with empty list and the toggle callback
        adapter = AlarmAdapter(emptyList()) { alarm, isEnabled ->
            handleAlarmToggle(alarm, isEnabled)
        }
        recyclerView.adapter = adapter

        // 2. Load existing alarms
        loadAlarms()

        // 3. Setup Add Button
        findViewById<FloatingActionButton>(R.id.addAlarmFab).setOnClickListener {
            openTimePicker()
        }
    }

    private fun loadAlarms() {
        CoroutineScope(Dispatchers.IO).launch {
            val alarms = db.alarmDao().getAllAlarms()
            withContext(Dispatchers.Main) {
                adapter.updateAlarms(alarms)
            }
        }
    }

    private fun handleAlarmToggle(alarm: AlarmEntity, isEnabled: Boolean) {
        // Update the database in the background
        CoroutineScope(Dispatchers.IO).launch {
            val updatedAlarm = alarm.copy(isEnabled = isEnabled)
            // Note: You need an update statement in your Dao.
            // If you don't have one, we will need to add it! (Let me know)
            // db.alarmDao().updateAlarm(updatedAlarm)

            withContext(Dispatchers.Main) {
                if (isEnabled) {
                    scheduleAlarm(alarm.hour, alarm.minute, alarm.id)
                    Toast.makeText(this@MainActivity, "Alarm Enabled", Toast.LENGTH_SHORT).show()
                } else {
                    cancelAlarm(alarm.id)
                    Toast.makeText(this@MainActivity, "Alarm Disabled", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openTimePicker() {
        val calendar = Calendar.getInstance()
        TimePickerDialog(this, { _, hourOfDay, minute ->

            CoroutineScope(Dispatchers.IO).launch {
                val newAlarm = AlarmEntity(hour = hourOfDay, minute = minute, isEnabled = true, daysOfWeek = "")
                val generatedId = db.alarmDao().insertAlarm(newAlarm).toInt()

                withContext(Dispatchers.Main) {
                    scheduleAlarm(hourOfDay, minute, generatedId)
                    loadAlarms() // Refresh the list
                }
            }

        }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), false).show()
    }

    private fun scheduleAlarm(hourOfDay: Int, minute: Int, alarmId: Int) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java).apply {
            putExtra("ALARM_ID", alarmId)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            this, alarmId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hourOfDay)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) add(Calendar.DATE, 1)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
            return
        }

        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
    }

    // NEW FUNCTION: Cancels an alarm in Android system using its unique ID
    private fun cancelAlarm(alarmId: Int) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, alarmId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }
}