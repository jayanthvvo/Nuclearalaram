package com.example.nuclearalarm

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(50, 50, 50, 50)
        }

        val title = TextView(this).apply {
            text = "NUCLEAR ALARM"
            textSize = 28f
            setPadding(0, 0, 0, 50)
            gravity = Gravity.CENTER
        }
        layout.addView(title)

        statusText = TextView(this).apply {
            text = "Status: Not Set"
            textSize = 18f
            setPadding(0, 0, 0, 50)
            gravity = Gravity.CENTER
        }
        layout.addView(statusText)

        val btnSetLocation = Button(this).apply {
            text = "1. Lock 'Safe Zone' GPS"
            setOnClickListener { lockCurrentLocation() }
        }
        layout.addView(btnSetLocation)

        val btnSetAlarm = Button(this).apply {
            text = "2. Set Alarm Time"
            setOnClickListener { openTimePicker() }
        }
        layout.addView(btnSetAlarm)

        val btnEnableAdmin = Button(this).apply {
            text = "3. Armor App (Prevent Uninstall)"
            setOnClickListener { activateDeviceAdmin() }
        }
        layout.addView(btnEnableAdmin)

        setContentView(layout)
        requestPermissions()
    }

    private fun requestPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        }
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
            Toast.makeText(this, "Please allow 'Display over other apps'", Toast.LENGTH_LONG).show()
        }
    }

    private fun lockCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    val prefs = getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE)
                    prefs.edit().apply {
                        putFloat("TARGET_LAT", location.latitude.toFloat())
                        putFloat("TARGET_LNG", location.longitude.toFloat())
                        apply()
                    }
                    statusText.text = "Safe Zone Locked!\nLat: ${location.latitude.toFloat()}\nLng: ${location.longitude.toFloat()}"
                    Toast.makeText(this, "Location Saved!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Make sure your GPS is turned ON!", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            requestPermissions()
        }
    }

    private fun openTimePicker() {
        val calendar = Calendar.getInstance()
        TimePickerDialog(this, { _, hourOfDay, minute ->
            scheduleAlarm(hourOfDay, minute)
        }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), false).show()
    }

    private fun scheduleAlarm(hourOfDay: Int, minute: Int) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hourOfDay)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) add(Calendar.DATE, 1)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
            Toast.makeText(this, "Please allow Exact Alarms", Toast.LENGTH_LONG).show()
            return
        }

        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
        getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE).edit().putBoolean("ALARM_SCHEDULED", true).apply()

        statusText.text = "Alarm Set For:\n${String.format("%02d:%02d", hourOfDay, minute)}"
        Toast.makeText(this, "Alarm Scheduled!", Toast.LENGTH_SHORT).show()
    }

    private fun activateDeviceAdmin() {
        val componentName = ComponentName(this, AdminReceiver::class.java)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

        if (!dpm.isAdminActive(componentName)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "This prevents uninstalling the app during a morning panic.")
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, "Armor is already active!", Toast.LENGTH_LONG).show()
        }
    }
}