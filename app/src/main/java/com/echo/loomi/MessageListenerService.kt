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
    private var existenceListener: ValueEventListener? = null
    private var connectedListener: ValueEventListener? = null
    private var sosGlobalListener: ValueEventListener? = null
    private var lastUid: String? = null
    private val heartbeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private val heartbeatRunnable: Runnable = object : Runnable {
        override fun run() {
            val uid = FirebaseAuth.getInstance().currentUser?.uid
            if (uid == null) return
            
            database.child(".info/connected").addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    val connected = s.getValue(Boolean::class.java) == true
                    if (connected) {
                        database.child("users").child(uid).child("name").get().addOnSuccessListener { userSnapshot ->
                            if (userSnapshot.exists() && userSnapshot.value != null) {
                                database.child("users").child(uid).child("status").setValue("Online")
                                heartbeatHandler.postDelayed(heartbeatRunnable, 180000)
                            } else {
                                // Clean up ghost data if any
                                database.child("users").child(uid).removeValue()
                                FirebaseAuth.getInstance().signOut()
                                stopSelf()
                            }
                        }
                    } else {
                        heartbeatHandler.postDelayed(heartbeatRunnable, 180000)
                    }
                }
                override fun onCancelled(e: DatabaseError) {}
            })
        }
    }

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

                // Verify user existence before updating location
                database.child("users").child(uid).child("name").get().addOnSuccessListener { s ->
                    if (s.exists()) {
                        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

                        val locationData = mapOf(
                            "name" to (user?.displayName ?: "Unknown"),
                            "email" to (user?.email ?: ""),
                            "location" to mapOf(
                                "latitude" to lastLocation.latitude,
                                "longitude" to lastLocation.longitude
                            ),
                            "battery" to "$batteryLevel%",
                            "timestamp" to ServerValue.TIMESTAMP
                        )

                        database.child("locations").child(uid).updateChildren(locationData)
                    }
                }
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

        if (uid != lastUid) {
            cleanupListeners(lastUid)
            lastUid = uid
        }

        // --- EXISTENCE CHECK & AUTO-LOGOUT ---
        if (existenceListener == null) {
            existenceListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists() || !snapshot.hasChild("name")) {
                        // Node deleted from console or incomplete, clean up and sign out
                        if (snapshot.exists()) snapshot.ref.removeValue()
                        FirebaseAuth.getInstance().signOut()
                        stopSelf()
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            database.child("users").child(uid).addValueEventListener(existenceListener!!)
        }

        // --- PRESENCE LOGIC ---
        val userStatusRef = database.child("users").child(uid).child("status")
        val lastSeenRef = database.child("users").child(uid).child("lastSeen")
        val connectedRef = database.child(".info/connected")

        // Set Online when connected, and Offline on disconnect automatically
        if (connectedListener == null) {
            connectedListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val connected = snapshot.getValue(Boolean::class.java) ?: false
                    if (connected) {
                        database.child("users").child(uid).child("name").get().addOnSuccessListener { userSnapshot ->
                            if (userSnapshot.exists() && userSnapshot.value != null) {
                                userStatusRef.onDisconnect().setValue("Offline")
                                lastSeenRef.onDisconnect().setValue(ServerValue.TIMESTAMP)
                                userStatusRef.setValue("Online")
                            } else {
                                // If name doesn't exist, we are a ghost. Clean up, sign out and stop.
                                database.child("users").child(uid).removeValue()
                                FirebaseAuth.getInstance().signOut()
                                stopSelf()
                            }
                        }
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            connectedRef.addValueEventListener(connectedListener!!)
        }

        // Heartbeat to keep connection alive
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        heartbeatHandler.post(heartbeatRunnable)

        startListening()
        listenForCalls()
        startLocationUpdates()
        
        return START_STICKY
    }

    private fun cleanupListeners(uid: String?) {
        uid?.let {
            existenceListener?.let { l -> database.child("users").child(it).removeEventListener(l) }
            callsListener?.let { l -> database.child("calls").child(it).removeEventListener(l) }
        }
        connectedListener?.let { l -> database.child(".info/connected").removeEventListener(l) }
        usersListener?.let { l -> database.child("users").removeEventListener(l) }
        
        // Remove SOS global listener
        sosGlobalListener?.let { l -> database.child("locations").removeEventListener(l) }
        sosGlobalListener = null

        listeners.forEach { (chatId, listener) ->
            database.child("chats").child(chatId).removeEventListener(listener)
        }
        
        existenceListener = null
        connectedListener = null
        usersListener = null
        callsListener = null
        listeners.clear()
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
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

        // Keep locations synced for real-time SOS alerts
        database.child("locations").keepSynced(true)

        // Listen for all user locations to catch SOS updates
        if (sosGlobalListener == null) {
            sosGlobalListener = object : ValueEventListener {
                private val shownSosIds = mutableSetOf<String>()

                override fun onDataChange(snapshot: DataSnapshot) {
                    val now = System.currentTimeMillis()
                    for (locSnapshot in snapshot.children) {
                        val uid = locSnapshot.key ?: continue
                        if (uid == myUid) continue

                        val type = locSnapshot.child("type").getValue(String::class.java)
                        if (type?.lowercase() == "sos") {
                            // Try to get timestamp as Long or Double (Firebase sometimes fluctuates)
                            val timestamp = when (val tsValue = locSnapshot.child("timestamp").value) {
                                is Long -> tsValue
                                is Double -> tsValue.toLong()
                                is Number -> tsValue.toLong()
                                else -> 0L
                            }
                            
                            // If timestamp is 0 or too old, but "type" is "sos", it might be a stale node
                            // We only trigger if it's within the last 5 minutes to be safe but responsive
                            if (timestamp > 0 && now - timestamp < 300000) {
                                val sosId = "${uid}_$timestamp"
                                if (!shownSosIds.contains(sosId)) {
                                    val name = locSnapshot.child("userName").getValue(String::class.java) ?: 
                                               locSnapshot.child("name").getValue(String::class.java) ?: "Someone"
                                    val email = locSnapshot.child("email").getValue(String::class.java) ?: "No Email"
                                    val battery = locSnapshot.child("battery").getValue(String::class.java) ?: "0%"
                                    val device = locSnapshot.child("deviceModel").getValue(String::class.java) ?: "Unknown"
                                    
                                    val lat = locSnapshot.child("location/latitude").getValue(Double::class.java) ?: 
                                              locSnapshot.child("latitude").getValue(Double::class.java) ?: 0.0
                                    val lon = locSnapshot.child("location/longitude").getValue(Double::class.java) ?: 
                                              locSnapshot.child("longitude").getValue(Double::class.java) ?: 0.0

                                    if (lat != 0.0 && lon != 0.0) {
                                        NotificationHelper.showSOSNotification(
                                            this@MessageListenerService,
                                            name, email, battery, device, lat, lon, timestamp
                                        )
                                        shownSosIds.add(sosId)
                                    }
                                }
                            }
                        }
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            database.child("locations").addValueEventListener(sosGlobalListener!!)
        }

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
        cleanupListeners(lastUid)
        super.onDestroy()
    }
}
