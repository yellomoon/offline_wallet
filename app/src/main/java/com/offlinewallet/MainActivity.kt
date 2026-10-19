package com.offlinewallet

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.offlinewallet.ui.WalletApp
import com.offlinewallet.ui.WalletTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0xFF101311.toInt()),
            navigationBarStyle = SystemBarStyle.dark(0xFF101311.toInt()),
        )
        setContent { WalletTheme { WalletApp() } }
    }
}
