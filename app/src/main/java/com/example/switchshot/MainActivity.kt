package com.example.switchshot

import android.Manifest
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import com.example.switchshot.net.connectToWifiSpecifier
import com.example.switchshot.net.downloadFromIndexOrImage
import com.example.switchshot.qr.CameraQrScanner
import com.example.switchshot.qr.WifiQr
import com.example.switchshot.qr.parseWifiQr
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    enum class Step { ScanWifiQR, Connecting, ScanUrlQR, Downloading, Done, Error }

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val context = LocalContext.current
                val connectivityManager = remember { context.getSystemService<ConnectivityManager>() }
                var step by remember { mutableStateOf(Step.ScanWifiQR) }
                var wifiQr by remember { mutableStateOf<WifiQr?>(null) }
                var downloadUrl by remember { mutableStateOf<String?>(null) }
                var savedCount by remember { mutableStateOf(0) }
                var errorMessage by remember { mutableStateOf<String?>(null) }

                val permissions = remember {
                    buildList {
                        add(Manifest.permission.CAMERA)
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            add(Manifest.permission.READ_MEDIA_IMAGES)
                        } else {
                            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        }
                    }
                }
                val permissionsState = rememberMultiplePermissionsState(permissions)

                LaunchedEffect(Unit) {
                    permissionsState.launchMultiplePermissionRequest()
                }

                LaunchedEffect(step, wifiQr, downloadUrl) {
                    when (step) {
                        Step.Connecting -> {
                            val data = wifiQr
                            val cm = connectivityManager
                            if (data == null || cm == null) {
                                errorMessage = "Wi-Fi情報の取得に失敗しました"
                                step = Step.Error
                            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                                errorMessage = "Android 10 以降の端末が必要です"
                                step = Step.Error
                            } else {
                                try {
                                    withContext(Dispatchers.IO) {
                                        connectToWifiSpecifier(
                                            context = context,
                                            connectivityManager = cm,
                                            ssid = data.ssid,
                                            password = data.password,
                                            security = data.security
                                        )
                                    }
                                    errorMessage = null
                                    step = Step.ScanUrlQR
                                } catch (error: Exception) {
                                    cm.bindProcessToNetwork(null)
                                    errorMessage = error.message ?: "Wi-Fi接続に失敗しました"
                                    step = Step.Error
                                }
                            }
                        }

                        Step.Downloading -> {
                            val url = downloadUrl
                            val cm = connectivityManager
                            if (url.isNullOrBlank() || cm == null) {
                                errorMessage = "ダウンロード対象のURLが見つかりません"
                                step = Step.Error
                            } else {
                                try {
                                    val count = withContext(Dispatchers.IO) {
                                        downloadFromIndexOrImage(
                                            baseUrl = url,
                                            contentResolver = context.contentResolver,
                                            relativePath = "Pictures/SwitchShots"
                                        )
                                    }
                                    savedCount = count
                                    errorMessage = null
                                    step = Step.Done
                                } catch (error: Exception) {
                                    errorMessage = error.message ?: "ダウンロードに失敗しました"
                                    step = Step.Error
                                } finally {
                                    cm.bindProcessToNetwork(null)
                                }
                            }
                        }

                        Step.Done, Step.Error -> {
                            connectivityManager?.bindProcessToNetwork(null)
                        }

                        else -> Unit
                    }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    if (!permissionsState.allPermissionsGranted) {
                        PermissionScreen(
                            permissionsState = permissionsState,
                            rationale = "カメラと位置情報の許可が必要です。SwitchのQRコードを読み取るために許可してください。"
                        )
                    } else {
                        SwitchShotScreen(
                            step = step,
                            errorMessage = errorMessage,
                            savedCount = savedCount,
                            onWifiQrScanned = { raw ->
                                val parsed = parseWifiQr(raw)
                                if (parsed != null) {
                                    wifiQr = parsed
                                    step = Step.Connecting
                                }
                            },
                            onUrlQrScanned = { raw ->
                                if (raw.startsWith("http://") || raw.startsWith("https://")) {
                                    downloadUrl = raw
                                    step = Step.Downloading
                                }
                            },
                            onRetry = {
                                connectivityManager?.bindProcessToNetwork(null)
                                savedCount = 0
                                downloadUrl = null
                                wifiQr = null
                                errorMessage = null
                                step = Step.ScanWifiQR
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun PermissionScreen(
    permissionsState: MultiplePermissionsState,
    rationale: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "SwitchShot へようこそ", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = rationale, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = { permissionsState.launchMultiplePermissionRequest() }) {
            Text(text = "許可する")
        }
    }
}

@Composable
private fun SwitchShotScreen(
    step: MainActivity.Step,
    errorMessage: String?,
    savedCount: Int,
    onWifiQrScanned: (String) -> Unit,
    onUrlQrScanned: (String) -> Unit,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        when (step) {
            MainActivity.Step.ScanWifiQR -> {
                ScannerStep(
                    title = "STEP1: Switch の『スマートフォンへ送る』で表示される Wi-Fi QR を読み取ってください",
                    onQrDetected = onWifiQrScanned
                )
            }

            MainActivity.Step.Connecting -> {
                LoadingStep(
                    title = "Switch のアクセスポイントへ接続中…",
                    description = "接続中はNintendo Switchのローカルネットワークのみ利用できます。"
                )
            }

            MainActivity.Step.ScanUrlQR -> {
                ScannerStep(
                    title = "STEP3: 2枚目のQRコードを読み取ってください",
                    onQrDetected = onUrlQrScanned
                )
            }

            MainActivity.Step.Downloading -> {
                LoadingStep(
                    title = "Switch からスクリーンショットを受信中…",
                    description = null
                )
            }

            MainActivity.Step.Done -> {
                ResultStep(
                    title = "受信完了",
                    message = "保存した画像: ${'$'}savedCount 枚",
                    buttonLabel = "もう一度",
                    onClick = onRetry
                )
            }

            MainActivity.Step.Error -> {
                ResultStep(
                    title = "エラーが発生しました",
                    message = errorMessage ?: "原因不明のエラー",
                    buttonLabel = "リトライ",
                    onClick = onRetry
                )
            }
        }

        if (step != MainActivity.Step.Error && errorMessage != null) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(8.dp)
            )
        }
    }
}

@Composable
private fun ScannerStep(
    title: String,
    onQrDetected: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(12.dp))
        CameraQrScanner(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            onQrDetected = onQrDetected
        )
    }
}

@Composable
private fun LoadingStep(
    title: String,
    description: String?
) {
    Column(
        modifier = Modifier
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(24.dp))
        CircularProgressIndicator()
        if (!description.isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = description, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ResultStep(
    title: String,
    message: String,
    buttonLabel: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onClick) {
            Text(text = buttonLabel)
        }
    }
}
