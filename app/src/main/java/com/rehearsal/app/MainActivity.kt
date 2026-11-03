package com.rehearsal.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rehearsal.app.ui.RehearsalRoot
import com.rehearsal.app.ui.theme.RehearsalTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RehearsalTheme {
                RehearsalRoot()
            }
        }
    }
}
