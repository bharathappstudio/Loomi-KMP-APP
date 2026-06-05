package com.echo.loomi

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.BatteryManager
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.android.gms.location.*
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class MessageListenerService : Service() {

    private val database = FirebaseDatabase.getInstance("https://echo-loomi-app-default-rtdb.firebaseio.com/").reference
    private val listeners = mutableMapOf<String, ValueEventListener>()
    private var usersListener: ValueEventListener? = null
    private var callsListener: ValueEventListener? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    override fun onCreate() {
        super.onCreate()
        // Removed startForeground() call
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setupLocationCallback()
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val lastLocation = locationResult.lastLocation ?: return
                val auth = FirebaseAuth.getInstance()
                val uid = auth.currentUser?.uid ?: return
                val user = auth.currentUser

                val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

                val locationData = mapOf(
                    "name" to (user?.displayName ?: "Unknown"),
                    "email" to (user?.email ?: ""),
                    "latitude" to lastLocation.latitude,
                    "longitude" to lastLocation.longitude,
                    "battery" to "$batteryLevel%",
                    "timestamp" to ServerValue.TIMESTAMP
                )

                database.child("locations").child(uid).setValue(locationData)
            }
        }
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 300000) // Update every 5 minutes
            .setMinUpdateIntervalMillis(150000) // At least 2.5 minutes between updates
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, android.os.Looper.getMainLooper())
        } catch (e: SecurityException) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val auth = FirebaseAuth.getInstance()
        val uid = auth.currentUser?.uid
        if (uid == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Removed startForeground() to stop showing the persistent notification
        // We rely on FCM high-priority messages and WorkManager to keep the app responsive

        // --- ALWAYS ONLINE ROOT LOGIC ---
        val userStatusRef = database.child("users").child(uid).child("status")
        userStatusRef.keepSynced(true)
        val connectedRef = database.child(".info/connected")

        // Force set Online and keep it that way
        connectedRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                if (connected) {
                    userStatusRef.setValue("Online")
                    // Removed onDisconnect().setValue("Offline") to stay "Always Online"
                    // We only set Offline on explicit Logout in MainActivity
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        // Heartbeat to force Online status every 4 minutes
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val heartbeat = object : Runnable {
            override fun run() {
                if (FirebaseAuth.getInstance().currentUser != null) {
                    userStatusRef.setValue("Online")
                    handler.postDelayed(this, 240000) // 4 minutes
                }
            }
        }
        handler.post(heartbeat)

        startListening()
        listenForCalls()
        startLocationUpdates()
        
        return START_STICKY
    }

    private fun listenForCalls() {
        val auth = FirebaseAuth.getInstance()
        val myUid = auth.currentUser?.uid ?: return

        if (callsListener != null) return

        callsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val callData = snapshot.getValue(CallData::class.java)
                if (callData != null && callData.status == "ringing") {
                    val decryptedName = EncryptionUtils.decrypt(callData.callerName)
                    val decryptedImage = EncryptionUtils.decrypt(callData.callerImage)
                    NotificationHelper.showCallNotification(
                        this@MessageListenerService,
                        callData.callerId,
                        decryptedName,
                        decryptedImage
                    )
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        database.child("calls").child(myUid).addValueEventListener(callsListener!!)
    }

    private fun startListening() {
        val auth = FirebaseAuth.getInstance()
        val myUid = auth.currentUser?.uid ?: return

        if (usersListener != null) return

        usersListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (userSnapshot in snapshot.children) {
                    val otherUid = userSnapshot.child("uid").getValue(String::class.java) ?: continue
                    if (otherUid == myUid) continue
                    
                    val otherName = userSnapshot.child("name").getValue(String::class.java) ?: "Unknown"
                    val otherImage = userSnapshot.child("imageName").getValue(String::class.java) ?: ""
                    val chatId = if (myUid < otherUid) "${myUid}_$otherUid" else "${otherUid}_$myUid"
                    
                    if (!listeners.containsKey(chatId)) {
                        val chatRef = database.child("chats").child(chatId)
                        chatRef.keepSynced(true)

                        val listener = object : ValueEventListener {
                            private var firstLoad = true
                            override fun onDataChange(chatSnapshot: DataSnapshot) {
                                if (chatSnapshot.exists()) {
                                    val lastMsgObj = chatSnapshot.children.lastOrNull()?.getValue(ChatMessage::class.java)
                                    if (lastMsgObj != null && lastMsgObj.senderId != myUid) {
                                        val now = System.currentTimeMillis()
                                        val isRecent = (now - lastMsgObj.timestamp) < 30000
                                        if (!firstLoad || isRecent) {
                                            NotificationHelper.showMessageNotification(
                                                this@MessageListenerService,
                                                otherUid,
                                                otherName,
                                                otherImage,
                                                lastMsgObj.message,
                                                chatId
                                            )
                                        }
                                    }
                                }
                                firstLoad = false
                            }
                            override fun onCancelled(error: DatabaseError) {}
                        }
                        chatRef.limitToLast(1).addValueEventListener(listener)
                        listeners[chatId] = listener
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        database.child("users").addValueEventListener(usersListener!!)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Advanced Root-Style Restart: Uses AlarmManager to force-restart service in 1 second
        val restartServiceIntent = Intent(applicationContext, this.javaClass).also {
            it.setPackage(packageName)
        }
        val restartServicePendingIntent: PendingIntent = PendingIntent.getService(
            this, 1, restartServiceIntent, 
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmService: AlarmManager = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmService.set(
            AlarmManager.ELAPSED_REALTIME, 
            SystemClock.elapsedRealtime() + 1000, 
            restartServicePendingIntent
        )

        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid != null) {
            // Remove listeners but DO NOT set status to Offline here
            // This keeps the user "Always Online" while the service attempts to restart
            database.child("calls").child(uid).removeEventListener(callsListener!!)
        }
        usersListener?.let { database.child("users").removeEventListener(it) }
        listeners.forEach { (chatId, listener) ->
            database.child("chats").child(chatId).removeEventListener(listener)
        }
        super.onDestroy()
    }
}
