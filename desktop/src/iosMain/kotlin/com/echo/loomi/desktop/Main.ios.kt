package com.echo.loomi.desktop

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController {
    // This is where the shared App() composable would go
    // For now, it just acts as a placeholder for iOS entry point
}
