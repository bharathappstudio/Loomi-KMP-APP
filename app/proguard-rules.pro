# WebRTC - Keep everything as it uses JNI extensively
-keep class org.webrtc.** { *; }
-keep interface org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Firebase & Google Services
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# Coil
-keep class coil.** { *; }
-dontwarn coil.**

# AndroidX & Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
-keep class androidx.core.app.CoreComponentFactory

# WorkManager Workers & Room
-keep class * extends androidx.work.ListenableWorker {
    <init>(...);
}
-keep class androidx.work.impl.WorkDatabase_Impl {
    <init>(...);
}
-keep class * extends androidx.room.RoomDatabase {
    <init>(...);
}
-dontwarn androidx.work.impl.**
-dontwarn androidx.room.**

# Startup
-keep class androidx.startup.** { *; }

# Keep the Application class
-keep class com.echo.loomi.LoomiApplication { *; }

# Keep all Activities, Services, and Receivers listed in Manifest
-keep class com.echo.loomi.MainActivity { *; }
-keep class com.echo.loomi.LoginActivity { *; }
-keep class com.echo.loomi.WelcomeActivity { *; }
-keep class com.echo.loomi.MessageActivity { *; }
-keep class com.echo.loomi.CallActivity { *; }
-keep class com.echo.loomi.Setting { *; }
-keep class com.echo.loomi.EchoActivity { *; }

-keep class com.echo.loomi.LoomiFirebaseMessagingService { *; }
-keep class com.echo.loomi.MessageListenerService { *; }
-keep class com.echo.loomi.DirectReplyReceiver { *; }
-keep class com.echo.loomi.BootReceiver { *; }

# Firebase Database Models - Keep field names for serialization
-keepclassmembers class com.echo.loomi.SnapUser { *; }
-keepclassmembers class com.echo.loomi.Story { *; }
-keepclassmembers class com.echo.loomi.ChatMessage { *; }
-keepclassmembers class com.echo.loomi.CallData { *; }
-keepclassmembers class com.echo.loomi.MusicTrack { *; }

# Keep annotations for reflection (Firebase uses them)
-keepattributes *Annotation*, Signature, EnclosingMethod, InnerClasses

# General app class keeping (allow renaming of internal logic to save space)
-keep,allowobfuscation class com.echo.loomi.** { *; }

# Optimization settings for "Full Compress"
-repackageclasses ''
-allowaccessmodification
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*
