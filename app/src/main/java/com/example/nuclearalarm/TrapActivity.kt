package com.example.nuclearalarm

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.location.*
import kotlin.random.Random

class TrapActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var mathProblemText: TextView
    private val buttons = mutableListOf<Button>()

    private var problemsSolved = 0
    private var requiredProblems = 5
    private var correctAns = 0

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var isAtSafeZone = false

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SMASH THROUGH THE LOCK SCREEN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // FULL IMMERSIVE MODE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                )

        // Penalty Check
        val prefs = getSharedPreferences("AlarmPrefs", MODE_PRIVATE)
        val isPenalty = intent.getBooleanExtra("PENALTY_MODE", false)
        val wasActive = prefs.getBoolean("IS_ACTIVE", false)
        val penaltyCount = prefs.getInt("PENALTY_COUNT", 15)

        if (wasActive || isPenalty) {
            requiredProblems = penaltyCount
        } else {
            prefs.edit().putBoolean("IS_ACTIVE", true).apply()
            requiredProblems = 5
        }

        setupUI(wasActive || isPenalty)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startLocationTracking()
        generateProblem()
    }

    private fun setupUI(isPenaltyMode: Boolean) {
        val overlayLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(if (isPenaltyMode) Color.parseColor("#440000") else Color.BLACK)
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

        setContentView(overlayLayout)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val blockedKeys = listOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_BACK)
        if (event.keyCode in blockedKeys) return true
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            try { sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) } catch (e: SecurityException) { }
        }
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

        // Generate answers safely
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
        fusedLocationClient.removeLocationUpdates(locationCallback)
        stopService(Intent(this, AlarmTrapService::class.java))
        finish()
    }
}