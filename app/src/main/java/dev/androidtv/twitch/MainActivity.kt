package dev.androidtv.twitch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import dev.androidtv.twitch.ui.App
import dev.androidtv.twitch.ui.AppViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Started by scripts/screenshot.sh with "--ez demo true", to take screenshots without personal data.
        if (intent.getBooleanExtra(EXTRA_DEMO, false)) viewModel.startDemo()
        setContent { App(viewModel) }
    }

    private companion object {
        const val EXTRA_DEMO = "demo"
    }
}
