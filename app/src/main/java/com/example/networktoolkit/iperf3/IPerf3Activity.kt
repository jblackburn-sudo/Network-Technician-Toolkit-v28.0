package com.example.networktoolkit.iperf3

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ui.theme.NetworkToolkitTheme

class IPerf3Activity : ComponentActivity() {

    private val viewModel: IPerf3ViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            NetworkToolkitTheme {
                IPerf3Screen(
                    viewModel = viewModel,
                    onBackClick = {
                        viewModel.stopServerIfRunning()
                        finish()
                    }
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            viewModel.stopServerIfRunning(
                reason = "Local iPerf3 server stopped automatically when leaving the foreground."
            )
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            viewModel.stopServerIfRunning()
        }
        super.onDestroy()
    }
}
