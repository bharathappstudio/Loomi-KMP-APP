plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

// Dummy task to satisfy IDE sync requirement for Gradle 9.x/Kotlin 2.x
tasks.register("prepareKotlinBuildScriptModel") {}

kotlin {
    jvm("desktop")
    
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
                implementation(compose.desktop.currentOs)
                // Desktop specific dependencies (JVM only)
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
                implementation("com.google.code.gson:gson:2.10.1")
                implementation(libs.kotlinx.coroutines.swing)
                
                // JavaFX for WebView support
                val javafxVersion = "21.0.2"
                val osName = System.getProperty("os.name").lowercase()
                val targetOs = when {
                    osName.contains("win") -> "win"
                    osName.contains("mac") -> "mac"
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
        nativeDistributions {
            // Windows (.msi), macOS (.dmg), Linux (.deb)
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg, 
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi, 
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Rpm
            )
            packageName = "Loomi"
            packageVersion = "1.0.0"
            
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
