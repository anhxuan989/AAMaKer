package com.aamaker.app

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.aamaker.app.ui.AAMaKerAppScreen
import com.aamaker.app.ui.MainViewModel
import com.aamaker.app.ui.theme.AAMaKerTheme

class MainActivity : ComponentActivity() {
    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AAMaKerTheme {
                AAMaKerAppScreen(viewModel = mainViewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mainViewModel.syncLanguageFromSystemIfChanged()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        mainViewModel.syncLanguageFromSystemIfChanged()
    }
}
