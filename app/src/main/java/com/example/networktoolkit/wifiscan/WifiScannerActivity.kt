package com.example.networktoolkit.wifiscan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ui.theme.NetworkToolkitTheme

/**
 * Activity hosting the Wi-Fi Scanner tool for detecting both visible and hidden Wi-Fi connections.
 */
class WifiScannerActivity : ComponentActivity() {

    private val viewModel: WifiScannerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            NetworkToolkitTheme {
                WifiScannerScreen(
                    viewModel = viewModel,
                    onBackClick = { finish() }
                )
            }
        }
    }
}
