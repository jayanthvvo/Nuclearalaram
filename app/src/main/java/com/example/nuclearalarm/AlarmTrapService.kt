package com.example.nuclearalarm

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlin.random.Random

class AlarmTrapService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayLayout: LinearLayout
    private var mediaPlayer: MediaPlayer? = null

    // Enforcers
    private lateinit var wakeLock: PowerManager.WakeLock
    private var volumeThread: Thread? = null
    private var isAlarmRunning = true
    private var screenReceiver: BroadcastReceiver? = null

    // Auto Stop Timer
    private var autoStopJob: Job? = null

    // Tracking
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var isAtSafeZone = false

    // Logic
    private var problemsSolved = 0
    private var requiredProblems = 5
    private var correctAns = 0

    // UI Elements
    private lateinit var statusText: TextView
    private lateinit var mathProblemText: TextView
    private val buttons = mutableListOf<Button>()

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("InvalidWakeLockTag")
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager

        wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "NuclearAlarm::TrapWakeLock"
        )
        wakeLock.acquire(30 * 60 * 1000L) // Max 30 mins

        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    val wl = powerManager.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "NuclearAlarm::ReviveScreen"
                    )
                    wl.acquire(3000)
                    wl.release()
                }
            }
        }
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))

        startForegroundServiceWithNotification()
        startBulletproofVolumeEnforcer()
        setupNuclearUI()
        startLocationTracking()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("AlarmPrefs", MODE_PRIVATE)
        val wasActive = prefs.getBoolean("IS_ACTIVE", false)
        val isPenalty = intent?.getBooleanExtra("PENALTY_MODE", false) ?: false
        val penaltyCount = prefs.getInt("PENALTY_COUNT", 15)

        if (wasActive || isPenalty) {
            requiredProblems = penaltyCount
            overlayLayout.setBackgroundColor(Color.parseColor("#440000"))
            statusText.text = "REBOOT PENALTY.\nSolve $requiredProblems + Reach Safe Zone."
        } else {
            prefs.edit().putBoolean("IS_ACTIVE", true).apply()
            requiredProblems = 5
        }

        generateProblem()

        // --- Auto-Stop Countdown ---
        val autoStopMinutes = prefs.getInt("AUTO_STOP_MINUTES", 0)
        if (autoStopMinutes > 0) {
            autoStopJob = CoroutineScope(Dispatchers.Main).launch {
                delay(autoStopMinutes * 60 * 1000L)

                if (isAlarmRunning) {
                    Toast.makeText(this@AlarmTrapService, "Alarm Auto-Stopped after $autoStopMinutes minutes.", Toast.LENGTH_LONG).show()
                    unlockAndStop()
                }
            }
        }

        return START_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val channelId = "nuclear_alarm_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Alarm Active", NotificationManager.IMPORTANCE_HIGH)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val intent = Intent(this, TrapActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("WAKE UP")
            .setContentText("Solve the math and walk to the safe zone.")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pendingIntent, true)
            .build()
        startForeground(1, notification)
    }

    @SuppressLint("MissingPermission")
    private fun startBulletproofVolumeEnforcer() {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val maxAlarm = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val maxRing = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)

        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setDataSource(applicationContext, Settings.System.DEFAULT_ALARM_ALERT_URI)
            isLooping = true
            prepare()
            start()
        }

        isAlarmRunning = true
        volumeThread = Thread {
            while (isAlarmRunning) {
                try {
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
                    audioManager.setStreamVolume(AudioManager.STREAM_RING, maxRing, 0)

                    try { sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) } catch (e: SecurityException) { }
                    Thread.sleep(100)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        volumeThread?.start()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationTracking() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000).build()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) checkIfInSafeZone(location)
            }
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    private fun checkIfInSafeZone(currentLoc: Location) {
        val prefs = getSharedPreferences("AlarmPrefs", MODE_PRIVATE)
        val targetLat = prefs.getFloat("TARGET_LAT", 0f).toDouble()
        val targetLng = prefs.getFloat("TARGET_LNG", 0f).toDouble()

        if (targetLat == 0.0 && targetLng == 0.0) {
            isAtSafeZone = true
            return
        }

        val targetLoc = Location("").apply { latitude = targetLat; longitude = targetLng }
        val distance = currentLoc.distanceTo(targetLoc)

        if (distance < 15) {
            isAtSafeZone = true
            statusText.text = "Safe Zone Verified."
            statusText.setTextColor(Color.GREEN)

            if (problemsSolved >= requiredProblems) unlockAndStop()
        } else {
            isAtSafeZone = false
            statusText.text = "Move to Safe Zone! (${distance.toInt()}m away)"
            statusText.setTextColor(Color.RED)
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun setupNuclearUI() {
        overlayLayout = object : LinearLayout(this) {
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (!hasWindowFocus) {
                    try { context.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) } catch (e: SecurityException) { }
                }
            }
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                val blockedKeys = listOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_BACK)
                if (event.keyCode in blockedKeys) return true
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            isFocusableInTouchMode = true
            requestFocus()
        }

        statusText = TextView(this).apply {
            text = "Checking GPS..."
            setTextColor(Color.YELLOW)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
        }
        overlayLayout.addView(statusText)

        mathProblemText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 50)
        }
        overlayLayout.addView(mathProblemText)

        val btnFrame = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        for (i in 0..2) {
            val btn = Button(this).apply {
                textSize = 20f
                setPadding(20, 20, 20, 20)
            }
            buttons.add(btn)
            btnFrame.addView(btn)
        }
        overlayLayout.addView(btnFrame)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP
        windowManager.addView(overlayLayout, params)
    }

    // --- NEW: Difficulty-Based Math Generator ---
    private fun generateProblem() {
        val prefs = getSharedPreferences("AlarmPrefs", MODE_PRIVATE)
        val difficulty = prefs.getInt("MATH_DIFFICULTY", 1) // 0=Easy, 1=Medium, 2=Hard

        val a: Int
        val b: Int
        val c: Int

        when (difficulty) {
            0 -> { // Easy
                a = Random.nextInt(2, 10)
                b = Random.nextInt(2, 10)
                c = Random.nextInt(10, 50)
            }
            2 -> { // Hard
                a = Random.nextInt(50, 100)
                b = Random.nextInt(50, 100)
                c = Random.nextInt(500, 1000)
            }
            else -> { // Medium (Default)
                a = Random.nextInt(15, 50)
                b = Random.nextInt(15, 50)
                c = Random.nextInt(100, 500)
            }
        }

        correctAns = (a * b) + c

        mathProblemText.text = "Streak: $problemsSolved / $requiredProblems\n($a × $b) + $c = ?"

        val answers = mutableListOf(correctAns, correctAns + Random.nextInt(5, 20), correctAns - Random.nextInt(5, 20)).shuffled()

        for (i in 0..2) {
            buttons[i].text = answers[i].toString()
            buttons[i].setOnClickListener(null)
            buttons[i].setOnClickListener { checkAnswer(buttons[i].text.toString().toInt()) }
        }
    }

    private fun checkAnswer(selected: Int) {
        if (selected == correctAns) {
            problemsSolved++
            if (problemsSolved >= requiredProblems) {
                if (isAtSafeZone) {
                    unlockAndStop()
                } else {
                    problemsSolved = requiredProblems
                    for (btn in buttons) btn.visibility = View.INVISIBLE
                    mathProblemText.text = "MATH COMPLETE!\n\nNow walk to the safe zone or wait for GPS signal..."
                    mathProblemText.setTextColor(Color.YELLOW)
                    return
                }
            }
        } else {
            problemsSolved = 0
            Toast.makeText(this, "WRONG! Streak reset.", Toast.LENGTH_SHORT).show()
        }
        generateProblem()
    }

    private fun unlockAndStop() {
        getSharedPreferences("AlarmPrefs", MODE_PRIVATE).edit().putBoolean("IS_ACTIVE", false).apply()
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        isAlarmRunning = false
        autoStopJob?.cancel()
        volumeThread?.interrupt()
        screenReceiver?.let { unregisterReceiver(it) }
        if (wakeLock.isHeld) wakeLock.release()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        mediaPlayer?.stop()
        mediaPlayer?.release()
        if (::overlayLayout.isInitialized) windowManager.removeView(overlayLayout)
    }
}