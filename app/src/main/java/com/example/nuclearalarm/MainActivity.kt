package com.example.nuclearalarm

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: AlarmAdapter
    private lateinit var db: AppDatabase
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private lateinit var safeZoneStatusText: TextView

    // Normal permissions launcher (Location, Notifications)
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            Toast.makeText(this, "Location Granted", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = AppDatabase.getDatabase(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        safeZoneStatusText = findViewById(R.id.safeZoneStatusText)

        // 1. Setup UI
        setupRecyclerView()
        updateSafeZoneUI()

        // 2. Button Listeners
        findViewById<FloatingActionButton>(R.id.addAlarmFab).setOnClickListener {
            openTimePicker()
        }

        findViewById<Button>(R.id.setSafeZoneButton).setOnClickListener {
            setSafeZoneToCurrentLocation()
        }

        // --- Handle Penalty Settings ---
        val penaltyInput = findViewById<android.widget.EditText>(R.id.penaltyCountInput)
        val savePenaltyBtn = findViewById<Button>(R.id.savePenaltyButton)

        // Load the saved penalty amount (defaults to 15) when app opens
        val prefs = getSharedPreferences("AlarmPrefs", MODE_PRIVATE)
        penaltyInput.setText(prefs.getInt("PENALTY_COUNT", 15).toString())

        // Save it when the button is clicked
        savePenaltyBtn.setOnClickListener {
            val countStr = penaltyInput.text.toString()
            if (countStr.isNotEmpty()) {
                val count = countStr.toIntOrNull() ?: 15
                prefs.edit().putInt("PENALTY_COUNT", count).apply()
                Toast.makeText(this, "Reboot Penalty set to $count problems!", Toast.LENGTH_SHORT).show()

                // Close the keyboard
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(penaltyInput.windowToken, 0)
            }
        }

        // 3. Aggressive Permission Checks
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }

        // Check Display Over Other Apps (SYSTEM_ALERT_WINDOW)
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please allow 'Display over other apps' to let the alarm lock your screen.", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
        }

        // Check Exact Alarm Permission (Android 12+)
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            Toast.makeText(this, "Please allow 'Alarms & Reminders' for accurate timing.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
        }
    }

    @SuppressLint("MissingPermission")
    private fun setSafeZoneToCurrentLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Location permission required!", Toast.LENGTH_SHORT).show()
            checkAndRequestPermissions()
            return
        }

        Toast.makeText(this, "Fetching location...", Toast.LENGTH_SHORT).show()

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener { location ->
            if (location != null) {
                val prefs = getSharedPreferences("AlarmPrefs", MODE_PRIVATE)
                prefs.edit()
                    .putFloat("TARGET_LAT", location.latitude.toFloat())
                    .putFloat("TARGET_LNG", location.longitude.toFloat())
                    .apply()

                Toast.makeText(this, "Safe Zone Saved!", Toast.LENGTH_SHORT).show()
                updateSafeZoneUI()
            } else {
                Toast.makeText(this, "Failed to get location. Turn on GPS.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateSafeZoneUI() {
        val prefs = getSharedPreferences("AlarmPrefs", MODE_PRIVATE)
        val lat = prefs.getFloat("TARGET_LAT", 0f)
        val lng = prefs.getFloat("TARGET_LNG", 0f)

        if (lat == 0f && lng == 0f) {
            safeZoneStatusText.text = "Safe Zone: NOT SET"
            safeZoneStatusText.setTextColor(Color.RED)
        } else {
            safeZoneStatusText.text = "Safe Zone: LOCKED IN\n(Lat: ${String.format("%.3f", lat)}, Lng: ${String.format("%.3f", lng)})"
            safeZoneStatusText.setTextColor(Color.GREEN)
        }
    }

    private fun setupRecyclerView() {
        val recyclerView = findViewById<RecyclerView>(R.id.alarmRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = AlarmAdapter(emptyList()) { alarm, isEnabled ->
            handleAlarmToggle(alarm, isEnabled)
        }
        recyclerView.adapter = adapter
        loadAlarms()
    }

    private fun loadAlarms() {
        CoroutineScope(Dispatchers.IO).launch {
            val alarms = db.alarmDao().getAllAlarms()
            withContext(Dispatchers.Main) {
                adapter.updateAlarms(alarms) // Using the correct method name
            }
        }
    }

    private fun handleAlarmToggle(alarm: AlarmEntity, isEnabled: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            val updatedAlarm = alarm.copy(isEnabled = isEnabled)
            db.alarmDao().updateAlarm(updatedAlarm)
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
                    loadAlarms()
                }
            }
        }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), false).show()
    }

    private fun scheduleAlarm(hourOfDay: Int, minute: Int, alarmId: Int) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java).apply { putExtra("ALARM_ID", alarmId) }
        val pendingIntent = PendingIntent.getBroadcast(
            this, alarmId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hourOfDay)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) add(Calendar.DATE, 1)
        }

        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
    }

    private fun cancelAlarm(alarmId: Int) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, alarmId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }
}