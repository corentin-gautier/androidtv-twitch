package dev.androidtv.twitch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun App(vm: AppViewModel = viewModel()) {
    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        when (val session = vm.session) {
            Session.Loading -> Spinner(Modifier.align(Alignment.Center))
            Session.Unreachable -> Message("Can't reach Twitch. Check the network connection.", action = "Retry", onAction = vm::restoreSession)
            Session.SignedOut -> SignInScreen(vm)
            is Session.SignedIn -> {
                val playing = vm.playing
                val channelPage = vm.channelPage
                val categoryPage = vm.categoryPage
                when {
                    playing != null -> PlayerScreen(vm, playing, onExit = { vm.playing = null })
                    channelPage != null -> ChannelScreen(vm, channelPage)
                    categoryPage != null -> CategoryScreen(vm, categoryPage)
                    else -> HomeScreen(vm, session.user)
                }
            }
        }
    }
}
