package dev.netmtr.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.netmtr.app.ui.NetMtrScreen
import dev.netmtr.app.ui.theme.NetMtrTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NetMtrTheme {
                NetMtrScreen()
            }
        }
    }
}
