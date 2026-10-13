package com.tonoyama.filemanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.tonoyama.filemanager.local.LocalScreen
import com.tonoyama.filemanager.pcloud.PCloudScreen
import com.tonoyama.filemanager.ui.FileManagerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FileManagerTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = tab == 0,
                                onClick = { tab = 0 },
                                icon = { Icon(Icons.Filled.PhoneAndroid, null) },
                                label = { Text("端末") },
                            )
                            NavigationBarItem(
                                selected = tab == 1,
                                onClick = { tab = 1 },
                                icon = { Icon(Icons.Filled.Cloud, null) },
                                label = { Text("pCloud") },
                            )
                        }
                    },
                ) { padding ->
                    // 上側の余白は各画面の上部バーが受け持つので、下側だけ空ける
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(bottom = padding.calculateBottomPadding())
                    ) {
                        when (tab) {
                            0 -> LocalScreen()
                            else -> PCloudScreen()
                        }
                    }
                }
            }
        }
    }
}
