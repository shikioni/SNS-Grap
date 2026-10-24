package com.instagrab.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.instagrab.app.data.auth.SessionManager
import com.instagrab.app.data.downloader.MediaDownloader
import com.instagrab.app.data.model.ExtractionResult
import com.instagrab.app.data.model.Platform
import com.instagrab.app.data.parser.UnifiedExtractor
import com.instagrab.app.ui.components.LoginDialog
import com.instagrab.app.ui.components.MediaCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    sessionManager: SessionManager,
    initialUrl: String = ""
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    val downloader = remember { MediaDownloader(context) }
    val extractor = remember { UnifiedExtractor(sessionManager) }

    var urlInput by remember { mutableStateOf(initialUrl) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var extractionResult by remember { mutableStateOf<ExtractionResult?>(null) }

    var showLoginDialog by remember { mutableStateOf(false) }
    var isLoggedIn by remember { mutableStateOf(sessionManager.isLoggedIn()) }

    val detectedPlatform = remember(urlInput) { Platform.detect(urlInput) }

    // Update if initialUrl changes (e.g. from external Share intent)
    LaunchedEffect(initialUrl) {
        if (initialUrl.isNotBlank()) {
            urlInput = initialUrl
        }
    }

    // Login Dialog for Meta/Instagram
    if (showLoginDialog) {
        LoginDialog(
            sessionManager = sessionManager,
            onDismiss = { showLoginDialog = false },
            onLoginSuccess = {
                isLoggedIn = true
                showLoginDialog = false
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "SNS Grab",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    }
                },
                actions = {
                    // Instagram / Threads Login status button (Optional login)
                    if (isLoggedIn) {
                        FilledTonalButton(
                            onClick = {
                                sessionManager.clearSession()
                                isLoggedIn = false
                                Toast.makeText(context, "已登出 IG", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "已登入",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "IG 已登入 (點此登出)", fontSize = 12.sp)
                        }
                    } else {
                        Button(
                            onClick = { showLoginDialog = true },
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = "登入 IG",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "IG 登入 (解鎖限動)", fontSize = 12.sp)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(10.dp))

            // 1. Full Width URL Input Field
            OutlinedTextField(
                value = urlInput,
                onValueChange = {
                    urlInput = it
                    if (errorMessage != null) errorMessage = null
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("貼上 FB / 陸抖 / TikTok / IG / Threads / X 等連結...", fontSize = 14.sp) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = "網址",
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                trailingIcon = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        if (urlInput.isNotEmpty()) {
                            IconButton(onClick = { urlInput = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "清除",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            IconButton(onClick = {
                                val clip = clipboardManager.getText()?.text
                                if (!clip.isNullOrBlank()) {
                                    urlInput = Platform.extractUrl(clip)
                                } else {
                                    Toast.makeText(context, "剪貼簿內無文字", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = "貼上",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            )

            // Auto-detected Platform Indicator
            if (urlInput.isNotBlank() && detectedPlatform != Platform.UNKNOWN) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                    ) {
                        Text(
                            text = "已自動辨識：${detectedPlatform.iconEmoji} ${detectedPlatform.displayName}",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 2. Centered Submit Button
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = {
                        val url = urlInput.trim()
                        if (url.isBlank()) {
                            Toast.makeText(context, "請先輸入或貼上網址", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        coroutineScope.launch {
                            isLoading = true
                            errorMessage = null
                            extractionResult = null

                            try {
                                val result = extractor.extract(url)
                                extractionResult = result
                            } catch (e: Exception) {
                                errorMessage = e.message ?: "解析發生未知錯誤"
                            } finally {
                                isLoading = false
                            }
                        }
                    },
                    enabled = !isLoading && urlInput.isNotBlank(),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(48.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "送出",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "開始解析", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Main Content Area (Loading / Error / Result List)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.TopCenter
            ) {
                // Loading State
                if (isLoading) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "正在解析 ${if (detectedPlatform != Platform.UNKNOWN) detectedPlatform.displayName else "社群媒體"} 內容...",
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Error State
                errorMessage?.let { error ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "錯誤",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center
                            )
                            if (error.contains("限時動態") || error.contains("登入") || error.contains("IG")) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = { showLoginDialog = true },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("立即前往登入 IG")
                                }
                            }
                        }
                    }
                }

                // Result State
                extractionResult?.let { result ->
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Summary Bar & "Download All" Button
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Row(
                                modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${result.platform.iconEmoji} ${result.platform.displayName}" +
                                                if (result.author.isNotBlank()) " • @${result.author}" else "",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "共解析出 ${result.items.size} 個檔案",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                // Batch "Download All" button if > 1 items
                                if (result.items.size > 1) {
                                    FilledTonalButton(
                                        onClick = {
                                            downloader.downloadAll(result.items)
                                        },
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Download,
                                            contentDescription = "全部下載",
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("全部下載")
                                    }
                                }
                            }
                        }

                        // List of items
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            items(result.items) { item ->
                                MediaCard(
                                    item = item,
                                    onDownloadClick = { targetItem ->
                                        downloader.download(targetItem)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

