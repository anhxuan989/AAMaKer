package com.aamaker.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aamaker.app.ui.AAMaKerAppScreen
import com.aamaker.app.ui.MainViewModel
import com.aamaker.app.ui.theme.AAMaKerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AAMaKerTheme {
                val mainViewModel: MainViewModel = viewModel()
                AAMaKerAppScreen(viewModel = mainViewModel)
            }
        }
    }
}
