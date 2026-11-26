package com.example.networktoolkit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ui.theme.NetworkToolkitTheme

/**
 * Activity hosting the LAN Subnet Sweep Scanner.
 */
class LanScannerActivity : ComponentActivity() {

    private val viewModel: LanScannerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NetworkToolkitTheme {
                LanScannerScreen(
                    viewModel = viewModel,
                    onBackClick = { finish() }
                )
            }
        }
    }
}
