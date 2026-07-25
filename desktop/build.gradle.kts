plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

// Dummy task to satisfy IDE sync requirement for Gradle 9.x/Kotlin 2.x
tasks.register("prepareKotlinBuildScriptModel") {}

kotlin {
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    
    // Add iOS Targets
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }
    
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                
                // Multiplatform Coroutines
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        
        val desktopMain by getting {
            dependsOn(commonMain)
            dependencies {
                // Support for all Desktop platforms in one JAR
                implementation(compose.desktop.linux_x64)
                implementation(compose.desktop.windows_x64)
                implementation(compose.desktop.macos_x64)
                implementation(compose.desktop.macos_arm64)
                
                // Desktop specific dependencies (JVM only)
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
                implementation("com.google.code.gson:gson:2.10.1")
                implementation(libs.kotlinx.coroutines.swing)
                
                // Optimized JavaFX: Only include the current OS libraries to save ~200MB
                val javafxVersion = "21.0.2"
                val osName = System.getProperty("os.name").lowercase()
                val targetOs = when {
                    osName.contains("win") -> "win"
                    osName.contains("mac") -> if (System.getProperty("os.arch") == "aarch64") "mac-aarch64" else "mac"
                    else -> "linux"
                }
                
                implementation("org.openjfx:javafx-base:$javafxVersion:$targetOs")
                implementation("org.openjfx:javafx-graphics:$javafxVersion:$targetOs")
                implementation("org.openjfx:javafx-controls:$javafxVersion:$targetOs")
                implementation("org.openjfx:javafx-web:$javafxVersion:$targetOs")
                implementation("org.openjfx:javafx-swing:$javafxVersion:$targetOs")
                implementation("org.openjfx:javafx-media:$javafxVersion:$targetOs")
            }
        }
        
        val iosX64Main by getting
        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting
        val iosMain by creating {
            dependsOn(commonMain)
            iosX64Main.dependsOn(this)
            iosArm64Main.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.echo.loomi.desktop.MainKt"
        
        val osName = System.getProperty("os.name").lowercase()
        
        // Use JAVA_HOME if available, otherwise fallback to Linux default for packaging
        (System.getenv("JAVA_HOME") ?: if (osName.contains("linux")) "/usr/lib/jvm/java-17-openjdk" else null)?.let {
            javaHome = it
        }

        jvmArgs += listOf(
            "-Dcompose.application.dev.mode=false",
            "-Dcompose.interop.blending=true",
            "-Dcompose.swing.interop.expose.native.window=true",
            "-Dskiko.renderApi=SOFTWARE", 
            "--add-opens", "java.desktop/sun.awt=ALL-UNNAMED",
            "--add-opens", "java.desktop/java.awt.event=ALL-UNNAMED",
            "--add-opens", "java.desktop/sun.swing=ALL-UNNAMED",
            "--add-exports", "java.desktop/jdk.swing.interop=ALL-UNNAMED",
            "--add-exports", "jdk.unsupported.desktop/jdk.swing.interop=ALL-UNNAMED"
        )

        if (osName.contains("linux")) {
            jvmArgs += listOf(
                "-Djdk.gtk.version=3",
                "--add-opens", "java.desktop/sun.awt.X11=ALL-UNNAMED"
            )
        }
        nativeDistributions {
            // Include only essential modules
            modules("java.desktop", "java.net.http", "jdk.crypto.ec", "jdk.unsupported")

            // Windows (.msi, .exe), macOS (.dmg)
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg, 
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe
            )
            packageName = "Loomi"
            packageVersion = "1.0.0"
            
            // Enable ProGuard shrinking to reduce size
            buildTypes.release.proguard {
                isEnabled.set(true)
                optimize.set(true)
                configurationFiles.from(project.file("compose-desktop.pro"))
            }
            
            // Linux specific
            linux {
                shortcut = true
                menuGroup = "Chat"
            }
            
            // macOS specific
            macOS {
                bundleID = "com.echo.loomi.desktop"
            }
            
            // Windows specific
            windows {
                shortcut = true
                menu = true
                upgradeUuid = "ce32039a-6539-4d6d-8e42-0f9c31e9674a"
            }
        }
    }
}
