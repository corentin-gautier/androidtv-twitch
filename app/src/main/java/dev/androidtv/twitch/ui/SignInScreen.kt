package dev.androidtv.twitch.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SignInScreen(vm: AppViewModel) {
    when (val state = vm.signIn) {
        SignInState.Requesting -> Box(Modifier.fillMaxSize()) { Spinner(Modifier.align(Alignment.Center)) }
        is SignInState.Failed -> Message(state.message, action = "Retry", onAction = vm::startSignIn)
        is SignInState.Code -> CodeScreen(state)
    }
}

@Composable
private fun CodeScreen(state: SignInState.Code) {
    Column(Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 54.dp)) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(60.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(27.dp)) {
                Label("Sign in with Twitch", Type.Display)
                Step(1) {
                    Row {
                        Label("On your phone or computer, open ", Type.Body)
                        Label("twitch.tv/activate", Type.Body.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text)
                    }
                }
                Step(2) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Label("Enter this code", Type.Body)
                        Label(
                            state.userCode,
                            Type.Code,
                            Modifier.clip(RoundedCornerShape(12.dp)).background(Palette.Surface).padding(horizontal = 24.dp, vertical = 10.dp),
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(
                    bitmap = state.qr,
                    contentDescription = "QR code to twitch.tv/activate",
                    modifier = Modifier.size(195.dp).clip(RoundedCornerShape(15.dp)).background(Palette.Text).padding(16.dp),
                    filterQuality = FilterQuality.None,
                )
                Label("Or scan to open the page", Type.Body.copy(fontSize = Type.Button.fontSize), color = Palette.TextMuted)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.Accent))
            Label("Waiting for confirmation… this screen continues on its own.", Type.Body.copy(fontSize = Type.Button.fontSize), color = Palette.TextMuted)
        }
    }
}

@Composable
private fun Step(number: Int, content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(15.dp)) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(Palette.SurfaceHigh), contentAlignment = Alignment.Center) {
            Label(number.toString(), Type.Button)
        }
        Box(Modifier.padding(top = 3.dp)) { content() }
    }
}
