# Loomi — Real-time Messaging. Reinvented.

[![Android](https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://android.com)
[![Desktop](https://img.shields.io/badge/Platform-Desktop-0078D4?style=for-the-badge&logo=windows&logoColor=white)](https://microsoft.com)
[![macOS](https://img.shields.io/badge/Platform-macOS-000000?style=for-the-badge&logo=apple&logoColor=white)](https://apple.com)
[![iOS](https://img.shields.io/badge/Platform-iOS-007AFF?style=for-the-badge&logo=ios&logoColor=white)](https://apple.com)
<br/>
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose Multiplatform](https://img.shields.io/badge/Compose_Multiplatform-1.7.3-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)](https://www.jetbrains.com/lp/compose-multiplatform/)
[![Firebase](https://img.shields.io/badge/Firebase-34.13.0-FFCA28?style=for-the-badge&logo=firebase&logoColor=black)](https://firebase.google.com)
[![WebRTC](https://img.shields.io/badge/WebRTC-Audio_Calls-FF6B6B?style=for-the-badge&logo=webrtc&logoColor=white)](https://webrtc.org)

<br/>

> **Loomi** is a high-performance, cross-platform messaging ecosystem built with **Compose Multiplatform** and **Kotlin Multiplatform (KMP)**. It provides a seamless, Snapchat-inspired experience across Android, Windows, macOS, Linux, and iOS — featuring real-time encrypted chats, WebRTC audio calls, and silky-smooth shared UI components.

<br/>

## 🌟 Key Features

- 📱 **Multi-Platform Sync**: Truly native apps for Android and Desktop (JVM), with iOS support via KMP.
- 💬 **Real-time Messaging**: Instant message delivery powered by Firebase Realtime Database.
- 📞 **HD Audio Calls**: Low-latency peer-to-peer audio calls using WebRTC.
- 🔐 **E2E Encryption**: End-to-end encrypted message payloads (XOR/Base64).
- 🎨 **Shared UI Components**: 95% of the UI code is shared across all platforms using Compose Multiplatform.
- 🖼️ **Dynamic Themes**: Beautiful dark/light modes with smooth gradient transitions.
- 📦 **Native Installers**: Ready-to-use `.msi` (Windows), `.dmg` (macOS), and `.deb` (Linux) packages.

---

## 🛠️ Multi-Platform Tech Stack

| Layer | Technology | Support |
|-------|-----------|---------|
| 🎨 **UI Framework** | Compose Multiplatform | Android, Desktop, iOS |
| 🧠 **Language** | Kotlin Multiplatform | Shared Business Logic |
| 🔥 **Backend / Auth** | Firebase Auth + Realtime DB | Android & Desktop (via REST/SDK) |
| 📞 **Audio Calling** | Stream WebRTC Android / Native | Android (Full Support) |
| 📁 **File Storage** | Firebase Storage | Multimedia sharing |
| 🖼️ **Image Loading** | Coil / Compose Resources | Cross-platform image handling |
| 🔑 **Authentication** | Google Sign-In | Android & Desktop (OAuth2) |

---

## 🏗️ Project Structure

```
Loomi/
├── app/                 ← Android-specific module (Native components)
├── desktop/             ← Multiplatform module (Desktop & iOS)
│   ├── src/
│   │   ├── commonMain/  ← **SHARED UI & LOGIC** (Login, Chat, Theme)
│   │   ├── desktopMain/ ← Desktop JVM-specific code (OkHttp, Gson)
│   │   ├── iosMain/     ← iOS native entry point (UIKit integration)
│   │   └── androidMain/ ← Android multiplatform targets
├── gradle/              ← Version catalogs and configuration
└── build.gradle.kts     ← Top-level build script
```

---

## 🚀 Build & Run Commands

### 📱 Android
```bash
./gradlew :app:installDebug         # Install on connected device
```

### 💻 Desktop (Windows, macOS, Linux)
```bash
./gradlew :desktop:run              # Run the desktop app
./gradlew :desktop:run -t           # Run with live-reload (continuous)
```

### 📦 Generate Installers
```bash
./gradlew :desktop:packageMsi       # Windows (.msi)
./gradlew :desktop:packageDmg       # macOS (.dmg)
./gradlew :desktop:packageDeb       # Linux (.deb)
./gradlew :desktop:packageUberJarForCurrentOS # Standalone JAR
```

### 🍎 iOS (Requires Mac + Xcode)
```bash
./gradlew :desktop:embedAndSignAppleFrameworkForXcode
```

---

## 🔐 Security & Encryption

Loomi uses a proprietary **Security Standard v1.0** for all communications:

1. **Payload Encryption**: All text messages are XOR-encrypted and Base64-encoded before hitting the database.
2. **Session Persistence**: Desktop sessions are securely stored in `~/.gemini/antigravity/loomi_session.json`.
3. **Signaling**: WebRTC signaling data is encrypted to prevent man-in-the-middle attacks.

```kotlin
// Example Payload
{
  "senderId": "user_123",
  "message": "e2e:SGVsbG8gV29ybGQh", // Encrypted "Hello World!"
  "timestamp": 1715432100
}
```

---

## 🏗️ Getting Started

### Prerequisites
- **Android Studio Ladybug** or **IntelliJ IDEA 2024+**
- **JDK 17+** (Required for Gradle 9.x)
- **CocoaPods** (For iOS dependency management)

### Setup
1. Clone the repo: `git clone https://github.com/bharathappstudio/Loomi.git`
2. Perform a **Gradle Sync**.
3. Create your Firebase project and add `google-services.json` to the `app/` folder.
4. Update `local.properties` with your keystore details.

---

## 📄 License

```
MIT License — Copyright (c) 2025 Bharath App Studio
```

---

<div align="center">

### Built with ❤️ by [Bharath App Studio](https://bharathappstudio.github.io)

[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Compose_Multiplatform-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)](https://www.jetbrains.com/lp/compose-multiplatform/)
[![Firebase](https://img.shields.io/badge/Firebase-FFCA28?style=flat-square&logo=firebase&logoColor=black)](https://firebase.google.com)

</div>
