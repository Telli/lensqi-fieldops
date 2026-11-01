package com.lensqi.fieldops.ui

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import com.lensqi.fieldops.ui.common.LocalDiagnostics
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lensqi.fieldops.data.settings.SettingsStore
import com.lensqi.fieldops.ui.theme.Appearance
import com.lensqi.fieldops.ui.theme.FieldOpsTheme
import com.lensqi.fieldops.ui.theme.Fo
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settings: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appearance by settings.appearance.collectAsStateWithLifecycle(Appearance.System)
            val diagnostics by settings.diagnostics.collectAsStateWithLifecycle(false)
            CompositionLocalProvider(LocalDiagnostics provides diagnostics) {
                FieldOpsTheme(appearance) {
                    // Status and navigation bar icons follow the app's appearance, not only the phone's.
                    val dark = Fo.isDark
                    LaunchedEffect(dark) {
                        val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                    }
                    Scaffold(Modifier.fillMaxSize()) { padding -> FieldOpsNavHost(Modifier.padding(padding)) }
                }
            }
        }
    }
}
