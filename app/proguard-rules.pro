# WebRTC ProGuard rules
-keep class org.webrtc.** { *; }
-keep interface org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Firebase/Google Auth
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }

# Coil
-keep class coil.** { *; }

# Keep data classes and models for Firebase
-keepclassmembers class com.echo.loomi.** {
    @com.google.firebase.database.IgnoreExtraProperties *;
    @com.google.firebase.database.PropertyName *;
}
-keep class com.echo.loomi.SnapUser { *; }
-keep class com.echo.loomi.Story { *; }
-keep class com.echo.loomi.ChatMessage { *; }
-keep class com.echo.loomi.CallData { *; }
-keep class com.echo.loomi.MusicTrack { *; }

# General app keeping
-keep class com.echo.loomi.** { *; }

# Keep annotations
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# Compose rules
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
