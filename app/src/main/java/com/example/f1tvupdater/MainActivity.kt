package com.example.f1tvupdater

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class MainActivity : ComponentActivity() {

    private val githubApiUrl = "https://api.github.com/repos/Alexvbp/f1tv-4k-patch/releases/latest"
    private val prefsName = "updater_prefs"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF121212)
                ) {
                    UpdaterScreen()
                }
            }
        }
    }

    @Composable
    fun UpdaterScreen() {
        val scope = rememberCoroutineScope()
        val prefs = remember { getSharedPreferences(prefsName, Context.MODE_PRIVATE) }

        var localVersion by remember { mutableStateOf(prefs.getString("installed_tag", "Geen") ?: "Geen") }
        var latestVersion by remember { mutableStateOf("Bezig met ophalen...") }
        var downloadUrl by remember { mutableStateOf<String?>(null) }
        var statusText by remember { mutableStateOf("Opstarten en controleren op updates...") }
        var isWorking by remember { mutableStateOf(false) }

        val checkFocusRequester = remember { FocusRequester() }
        val installFocusRequester = remember { FocusRequester() }

        suspend fun performUpdateCheck() {
            isWorking = true
            statusText = "Controleren op GitHub..."
            val release = checkLatestRelease()
            if (release != null) {
                latestVersion = release.first
                downloadUrl = release.second
                statusText = if (latestVersion != localVersion) {
                    "Nieuwe update beschikbaar ($latestVersion)! Druk op OK om te installeren."
                } else {
                    "Je hebt al de nieuwste versie ($localVersion)."
                }
            } else {
                statusText = "Fout bij ophalen release of geen .apkm/.apk gevonden."
            }
            isWorking = false
        }

        LaunchedEffect(Unit) {
            performUpdateCheck()
        }

        LaunchedEffect(isWorking, downloadUrl) {
            if (!isWorking) {
                try {
                    if (downloadUrl != null && latestVersion != localVersion) {
                        installFocusRequester.requestFocus()
                    } else {
                        checkFocusRequester.requestFocus()
                    }
                } catch (_: Exception) {
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "F1TV 4K Patch Updater",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(text = "Geïnstalleerde versie: $localVersion", color = Color.LightGray)
            Text(text = "Nieuwste op GitHub: $latestVersion", color = Color.LightGray)
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFFFD54F)
            )
            Spacer(modifier = Modifier.height(28.dp))

            if (isWorking) {
                CircularProgressIndicator(color = Color.Red)
                Spacer(modifier = Modifier.height(20.dp))
            }

            TvButton(
                text = "Opnieuw controleren",
                enabled = !isWorking,
                modifier = Modifier.focusRequester(checkFocusRequester),
                onClick = {
                    scope.launch { performUpdateCheck() }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (downloadUrl != null) {
                val isNewer = latestVersion != localVersion
                TvButton(
                    text = if (isNewer) "Download & Installeer ($latestVersion)" else "Herinstalleer ($latestVersion)",
                    enabled = !isWorking,
                    modifier = Modifier.focusRequester(installFocusRequester),
                    onClick = {
                        if (!packageManager.canRequestPackageInstalls()) {
                            startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:$packageName")
                                )
                            )
                            statusText = "Geef eerst toestemming voor 'Onbekende apps installeren' en klik opnieuw."
                            return@TvButton
                        }

                        scope.launch {
                            isWorking = true
                            statusText = "Starten met downloaden van $latestVersion..."
                            val success = downloadAndInstallApkm(
                                url = downloadUrl!!,
                                onProgress = { msg -> statusText = msg }
                            )
                            if (success) {
                                prefs.edit().putString("installed_tag", latestVersion).apply()
                                localVersion = latestVersion
                            }
                            isWorking = false
                        }
                    }
                )
            }
        }
    }

    @Composable
    fun TvButton(
        text: String,
        enabled: Boolean,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        var isFocused by remember { mutableStateOf(false) }

        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier
                .widthIn(min = 280.dp)
                .height(56.dp)
                .onFocusChanged { isFocused = it.isFocused },
            border = if (isFocused) BorderStroke(3.dp, Color.White) else null,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isFocused) Color(0xFFE10600) else Color(0xFF333333),
                contentColor = Color.White,
                disabledContainerColor = Color(0xFF222222),
                disabledContentColor = Color.Gray
            )
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }

    private suspend fun checkLatestRelease(): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(githubApiUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.setRequestProperty("User-Agent", "F1TV-4K-Patch-Updater")

            if (connection.responseCode != 200) return@withContext null

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            val tagName = json.getString("tag_name")
            val assets = json.getJSONArray("assets")

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name").lowercase()
                if (name.endsWith(".apkm") || name.endsWith(".apk")) {
                    return@withContext Pair(tagName, asset.getString("browser_download_url"))
                }
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun downloadAndInstallApkm(
        url: String,
        onProgress: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val downloadFile = File(cacheDir, "update_bundle.apkm")
            if (downloadFile.exists()) downloadFile.delete()

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connect()

            val totalBytes = connection.contentLength
            connection.inputStream.use { input ->
                FileOutputStream(downloadFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var downloaded = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        if (totalBytes > 0) {
                            val pct = (downloaded * 100 / totalBytes).toInt()
                            withContext(Dispatchers.Main) {
                                onProgress("Downloaden... $pct%")
                            }
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                onProgress("Bezig met uitpakken en klaarzetten voor Android Installer...")
            }

            val packageInstaller = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            if (url.lowercase().endsWith(".apk")) {
                downloadFile.inputStream().use { input ->
                    session.openWrite("base.apk", 0, downloadFile.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
            } else {
                ZipInputStream(downloadFile.inputStream()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name.endsWith(".apk")) {
                            val safeName = entry.name.substringAfterLast("/")
                            session.openWrite(safeName, 0, -1).use { output ->
                                zis.copyTo(output)
                                session.fsync(output)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            val intent = Intent(this@MainActivity, InstallReceiver::class.java)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(this@MainActivity, sessionId, intent, flags)
            session.commit(pendingIntent.intentSender)
            session.close()

            withContext(Dispatchers.Main) {
                onProgress("Bevestig de installatie in het pop-upvenster op je scherm.")
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                onProgress("Fout tijdens installatie: ${e.localizedMessage}")
            }
            false
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val activityIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                activityIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (activityIntent != null) {
                    context.startActivity(activityIntent)
                }
            }
            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                android.util.Log.e("F1TVUpdater", "Installatie mislukt ($status): $msg")
            }
        }
    }
}
