package com.example.networktoolkit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ui.theme.NetworkToolkitTheme

/**
 * Activity hosting the NetworkInfoScreen Compose UI component and NetworkInfoViewModel.
 */
class NetworkInfoActivity : ComponentActivity() {

    private val viewModel: NetworkInfoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val targetSection = intent.getStringExtra("target_section")
        setContent {
            NetworkToolkitTheme {
                NetworkInfoScreen(
                    viewModel = viewModel,
                    onBackClick = { finish() },
                    targetSection = targetSection
                )
            }
        }
    }
}
