package com.tonoyama.filemanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tonoyama.filemanager.home.AnalysisScreen
import com.tonoyama.filemanager.home.CategoryScreen
import com.tonoyama.filemanager.home.CategoryViewModel
import com.tonoyama.filemanager.home.HomeScreen
import com.tonoyama.filemanager.home.HomeViewModel
import com.tonoyama.filemanager.local.LocalScreen
import com.tonoyama.filemanager.local.LocalViewModel
import com.tonoyama.filemanager.local.StoragePermissionGate
import com.tonoyama.filemanager.media.Category
import com.tonoyama.filemanager.pcloud.PCloudScreen
import com.tonoyama.filemanager.pcloud.PCloudViewModel
import com.tonoyama.filemanager.ui.FileManagerTheme

/** 画面の種類 */
sealed interface Screen {
    data object Home : Screen
    data object Browser : Screen
    data class Files(val category: Category) : Screen
    data object Analysis : Screen
    data object Cloud : Screen
}

/** 画面の履歴（戻るボタンで1つ前へ） */
class NavViewModel : ViewModel() {
    val stack = mutableStateListOf<Screen>(Screen.Home)

    fun push(screen: Screen) {
        // 同じ画面を重ねない（フォルダ画面はフォルダを切り替えるだけ）
        if (stack.last() == screen) return
        if (screen == Screen.Browser) stack.remove(Screen.Browser)
        stack.add(screen)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FileManagerTheme {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                        StoragePermissionGate { App() }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun App() {
    val nav: NavViewModel = viewModel()
    val localVm: LocalViewModel = viewModel()
    val homeVm: HomeViewModel = viewModel()
    val categoryVm: CategoryViewModel = viewModel()
    val cloudVm: PCloudViewModel = viewModel()

    // 各画面の「戻る」処理（フォルダを上がる等）が優先され、それ以外はここで前の画面へ
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }

    val openDir: (java.io.File) -> Unit = { dir ->
        localVm.open(dir)
        nav.push(Screen.Browser)
    }

    when (val screen = nav.stack.last()) {
        Screen.Home -> HomeScreen(
            vm = homeVm,
            localVm = localVm,
            onOpenDir = openDir,
            onCategory = { nav.push(Screen.Files(it)) },
            onAnalysis = { nav.push(Screen.Analysis) },
            onCloud = { nav.push(Screen.Cloud) },
        )
        Screen.Browser -> LocalScreen(localVm, onBack = nav::pop)
        is Screen.Files -> CategoryScreen(
            category = screen.category,
            vm = categoryVm,
            localVm = localVm,
            onBack = nav::pop,
            onOpenFolder = openDir,
        )
        Screen.Analysis -> AnalysisScreen(
            vm = homeVm,
            localVm = localVm,
            onBack = nav::pop,
            onCategory = { nav.push(Screen.Files(it)) },
        )
        Screen.Cloud -> PCloudScreen(cloudVm, onBack = nav::pop)
    }
}
