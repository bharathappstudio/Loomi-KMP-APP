package com.echo.loomi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlin.math.sqrt

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

class SOSManager(
    private val context: Context,
    private val onShakeDetected: () -> Unit
) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    
    private var lastUpdate: Long = 0
    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var lastZ: Float = 0f
    private val SHAKE_THRESHOLD = 800 // Increased threshold to prevent "light shake" triggers
    private var shakeCount = 0
    private var lastShakeTime: Long = 0

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        shakeCount = 0
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        
        val curTime = System.currentTimeMillis()
        if ((curTime - lastUpdate) > 100) {
            val diffTime = curTime - lastUpdate
            lastUpdate = curTime

            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            val speed = sqrt((x - lastX) * (x - lastX) + (y - lastY) * (y - lastY) + (z - lastZ) * (z - lastZ)) / diffTime * 10000

            if (speed > SHAKE_THRESHOLD) {
                if (curTime - lastShakeTime < 500) { // Require faster, more intentional shakes
                    shakeCount++
                } else {
                    shakeCount = 1 // Reset if shakes are too slow or it's the first shake
                }
                lastShakeTime = curTime

                if (shakeCount >= 6) { // Must reach 6 count for confirmation
                    onShakeDetected()
                    shakeCount = 0
                }
            }

            lastX = x
            lastY = y
            lastZ = z
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun uploadSOSData() {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return
        val database = FirebaseDatabase.getInstance("https://echo-loomi-app-default-rtdb.firebaseio.com/").reference
        
        vibrate()
        
        val batteryLevel = getBatteryLevel()
        val location = getLastKnownLocation()
        
        val sosData = mutableMapOf<String, Any>(
            "uid" to currentUser.uid,
            "userName" to (currentUser.displayName ?: "Unknown User"),
            "email" to (currentUser.email ?: "No Email"),
            "message" to "🚨 SOS ALERT: I need help!",
            "battery" to "$batteryLevel%",
            "deviceModel" to Build.MODEL,
            "timestamp" to ServerValue.TIMESTAMP,
            "type" to "sos"
        )

        location?.let {
            sosData["location"] = mapOf(
                "latitude" to it.latitude,
                "longitude" to it.longitude
            )
        }

        database.child("locations").child(currentUser.uid).setValue(sosData)
            .addOnSuccessListener {
                Log.d("SOSManager", "SOS data uploaded and old data removed")
            }
    }

    private fun getBatteryLevel(): Int {
        val batteryStatus: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return batteryStatus?.let { intent ->
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            (level * 100 / scale.toFloat()).toInt()
        } ?: -1
    }

    private fun getLastKnownLocation(): Location? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = locationManager.getProviders(true)
        var bestLocation: Location? = null
        for (provider in providers) {
            val l = locationManager.getLastKnownLocation(provider) ?: continue
            if (bestLocation == null || l.accuracy < bestLocation.accuracy) {
                bestLocation = l
            }
        }
        return bestLocation
    }

    private fun vibrate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            val vibrator = vibratorManager.defaultVibrator
            vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(1000)
        }
    }
}

@Composable
fun SOSOverlay(onTimeout: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(9999)
        onTimeout()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.sos),
            contentDescription = "SOS",
            modifier = Modifier
                .size(250.dp)
                .align(Alignment.Center),
            contentScale = ContentScale.Fit
        )
    }
}
