package com.tonoyama.filemanager.pcloud

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tonoyama.filemanager.thumb.ThumbBox
import com.tonoyama.filemanager.ui.FileIcon
import com.tonoyama.filemanager.ui.ProgressDialog
import com.tonoyama.filemanager.util.FileOpener
import com.tonoyama.filemanager.util.Format

private fun openInBrowser(context: Context, uri: Uri) {
    try {
        CustomTabsIntent.Builder().build().launchUrl(context, uri)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PCloudScreen(vm: PCloudViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }
    LaunchedEffect(state.readyToOpen) {
        state.readyToOpen?.let {
            FileOpener.open(context, it)
            vm.consumeReadyToOpen()
        }
    }

    if (state.loggedIn) {
        CloudBrowser(state, vm, snackbar, onBack)
    } else {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") } },
                    title = { Text("pCloud") },
                )
            },
        ) { padding ->
            LoginPane(state, vm, Modifier.padding(padding))
        }
    }

    state.download?.let { d ->
        ProgressDialog(
            title = "ダウンロード中",
            label = "${d.name}\n${d.detail}",
            done = d.percent,
            total = 100,
            onCancel = vm::cancelDownload,
        )
    }
}

@Composable
private fun LoginPane(state: PCloudUiState, vm: PCloudViewModel, modifier: Modifier) {
    val context = LocalContext.current
    var clientId by rememberSaveable { mutableStateOf(state.clientId) }
    var secret by rememberSaveable { mutableStateOf(state.clientSecret) }
    var code by rememberSaveable { mutableStateOf("") }
    var showCodeFlow by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            Text("pCloud に接続", style = MaterialTheme.typography.headlineSmall)
        }
        Text(
            "pCloud の中身を参照できます。米国・欧州どちらのアカウントでも自動で判別します。",
            style = MaterialTheme.typography.bodyMedium,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("はじめに（1回だけ）", fontWeight = FontWeight.Bold)
                Text("1. pCloud 開発者ページの「My Apps」で新しいアプリを作成します", style = MaterialTheme.typography.bodySmall)
                Text("2. 表示された Client ID を下に貼り付けます", style = MaterialTheme.typography.bodySmall)
                Text("3.「ブラウザでログイン」を押し、許可したらこの画面に戻ります", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { openInBrowser(context, Uri.parse("https://docs.pcloud.com/my_apps/")) }) {
                    Text("My Apps を開く")
                }
            }
        }

        OutlinedTextField(
            value = clientId,
            onValueChange = {
                clientId = it
                vm.setClientId(it)
            },
            label = { Text("Client ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.waitingForBrowser) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(
                        "ブラウザでの許可を待っています。許可したらこのアプリに戻ってください。",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(Modifier.padding(start = 8.dp, bottom = 8.dp)) {
                    TextButton(onClick = { vm.startBrowserLogin()?.let { openInBrowser(context, it) } }) {
                        Text("ブラウザをもう一度開く")
                    }
                    TextButton(onClick = vm::cancelBrowserLogin) { Text("キャンセル") }
                }
            }
        } else {
            Button(
                onClick = { vm.startBrowserLogin()?.let { openInBrowser(context, it) } },
                enabled = clientId.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("ブラウザでログイン") }
        }

        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        HorizontalDivider()

        // 予備：認証コード方式
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showCodeFlow = !showCodeFlow }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("うまくいかない場合（認証コードで接続）", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Icon(if (showCodeFlow) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
        }
        if (showCodeFlow) {
            Text(
                "My Apps に表示される Client Secret を入力し、コード取得ページで許可すると表示されるコードを貼り付けてください。",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = secret,
                onValueChange = {
                    secret = it
                    vm.setClientSecret(it)
                },
                label = { Text("Client Secret") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { vm.codeLoginUrl()?.let { openInBrowser(context, it) } },
                enabled = clientId.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("コード取得ページを開く") }
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("認証コード") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { vm.loginWithCode(code) },
                enabled = !state.loading && clientId.isNotBlank() && secret.isNotBlank() && code.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.loading) "接続中…" else "コードで接続") }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudBrowser(state: PCloudUiState, vm: PCloudViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    var accountMenu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<PEntry?>(null) }

    BackHandler(enabled = state.stack.size > 1) { vm.goUp() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (!vm.goUp()) onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") }
                },
                title = { Text(state.stack.last().name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = vm::loadCurrent) { Icon(Icons.Filled.Refresh, "再読み込み") }
                    Box {
                        IconButton(onClick = { accountMenu = true }) { Icon(Icons.Filled.AccountCircle, "アカウント") }
                        DropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(state.email.ifEmpty { "pCloud" })
                                        Text(
                                            "サーバー: ${state.region}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {},
                                enabled = false,
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("ログアウト") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
                                onClick = { accountMenu = false; vm.logout() },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            CloudBreadcrumbs(state.stack, onOpen = vm::openStackIndex)
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (state.entries.isEmpty() && !state.loading && state.error == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("空のフォルダです", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(state.entries, key = { (if (it.isFolder) "d" else "f") + (if (it.isFolder) it.folderId else it.fileId) }) { e ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { if (e.isFolder) vm.openFolder(e) else selected = e }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ThumbBox(
                                name = e.name,
                                isDirectory = e.isFolder,
                                selected = false,
                                thumbKey = if (e.hasThumb) vm.thumbKey(e) else null,
                                loader = if (e.hasThumb) suspend { vm.thumbnail(e) } else null,
                                modifier = Modifier.size(42.dp),
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                val sub = if (e.isFolder) "フォルダ" else Format.size(e.size)
                                Text(
                                    "$sub ・ ${Format.date(e.modified)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { e ->
        AlertDialog(
            onDismissRequest = { selected = null },
            icon = { FileIcon(e.name, false) },
            title = { Text(e.name, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            text = { Text("${Format.size(e.size)} ・ ${Format.date(e.modified)}") },
            confirmButton = {
                TextButton(onClick = { selected = null; vm.openFile(e) }) {
                    Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("開く")
                }
            },
            dismissButton = {
                TextButton(onClick = { selected = null; vm.saveToDevice(e) }) {
                    Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("端末に保存")
                }
            },
        )
    }
}

@Composable
private fun CloudBreadcrumbs(stack: List<FolderRef>, onOpen: (Int) -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(stack.size) { scroll.scrollTo(scroll.maxValue) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        stack.forEachIndexed { i, ref ->
            if (i > 0) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            val isLast = i == stack.lastIndex
            AssistChip(
                onClick = { if (!isLast) onOpen(i) },
                label = { Text(ref.name, fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal) },
            )
        }
    }
}
