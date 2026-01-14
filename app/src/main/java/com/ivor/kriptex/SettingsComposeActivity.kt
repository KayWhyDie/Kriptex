package com.ivor.kriptex

import android.Manifest
import android.app.ActivityManager
import android.app.Activity
import android.app.ProgressDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ivor.kriptex.crypto.AdvancedCrypto
import com.ivor.kriptex.db.Contact
import com.ivor.kriptex.db.Database
import com.ivor.kriptex.tor.Tor
import com.ivor.kriptex.ui.compose.KriptexTheme
import com.ivor.kriptex.utils.PasswordValidator
import com.ivor.kriptex.utils.Settings
import com.ivor.kriptex.utils.Util
import com.ivor.kriptex.utils.ZipManager
import com.google.gson.Gson
import com.google.gson.stream.JsonWriter
import io.realm.Realm
import io.realm.RealmConfiguration
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.util.Properties
import kotlin.concurrent.thread
import kotlin.system.exitProcess

class SettingsComposeActivity : AppCompatActivity() {

    companion object {
        private const val PREF_DISGUISE_LAUNCHER = "disguise_launcher"

        // Must match the android:name of <activity-alias> entries in AndroidManifest.xml
        private const val LAUNCHER_ALIAS_DEFAULT = "LauncherDefault"
        private const val LAUNCHER_ALIAS_DISGUISED = "LauncherCalculator"
    }

    private val snackbarHostState = SnackbarHostState()

    private var pendingSnackbar by mutableStateOf<String?>(null)
    private var displayName by mutableStateOf("")
    private var address by mutableStateOf("")
    private var disguiseEnabled by mutableStateOf(false)

    private val requestWriteStorage = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingExportRequested = true
        } else {
            pendingSnackbar = "External storage access denied"
        }
    }

    private var pendingExportRequested by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = Settings.getPrefs(this)
        disguiseEnabled = prefs.getBoolean(PREF_DISGUISE_LAUNCHER, false)
        applyLauncherDisguise(disguiseEnabled)
        refreshProfile()

        setContent {
            KriptexTheme {
                SettingsScreen(
                    snackbarHostState = snackbarHostState,
                    pendingSnackbarMessage = pendingSnackbar,
                    onClearPendingSnackbar = { pendingSnackbar = null },
                    name = displayName,
                    address = address,
                    disguiseEnabled = disguiseEnabled,
                    onBack = { finish() },
                    onChangeName = { newName ->
                        Database.getInstance(this).setName(newName.trim())
                        refreshProfile()
                        pendingSnackbar = "Alias changed"
                    },
                    onExport = {
                        if (hasWriteExternalStoragePermission()) {
                            pendingExportRequested = true
                        } else {
                            requestWriteStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        }
                    },
                    onExportWithPassword = { password ->
                        this@SettingsComposeActivity.createExportZip(password) { pathOrNull ->
                            if (pathOrNull.isNullOrBlank()) {
                                pendingSnackbar = "Export failed"
                            } else {
                                pendingSnackbar = "Zip file created: $pathOrNull"
                            }
                        }
                    },
                    onToggleDisguise = { enabled ->
                        disguiseEnabled = enabled
                        Settings.putBoolean(this, PREF_DISGUISE_LAUNCHER, enabled)
                        applyLauncherDisguise(enabled)
                        pendingSnackbar = if (enabled) {
                            "Disguise enabled (launcher icon/name may take a moment to update)"
                        } else {
                            "Disguise disabled"
                        }
                    },
                    onHardReset = {
                        hardResetAndExit()
                    },
                    exportRequested = pendingExportRequested,
                    onExportHandled = { pendingExportRequested = false },
                )
            }
        }
    }

    private fun applyLauncherDisguise(enabled: Boolean) {
        // Swaps launcher icon + label using two activity-alias entries.
        // Note: Launchers may cache icons; user might need a short wait or a launcher refresh.
        val pm = packageManager
        val defaultAlias = ComponentName(packageName, "$packageName.$LAUNCHER_ALIAS_DEFAULT")
        val disguisedAlias = ComponentName(packageName, "$packageName.$LAUNCHER_ALIAS_DISGUISED")

        try {
            pm.setComponentEnabledSetting(
                defaultAlias,
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            pm.setComponentEnabledSetting(
                disguisedAlias,
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        } catch (_: Exception) {
            // If aliases aren't present (e.g., during development), fail silently.
        }
    }

    private fun hardResetAndExit() {
        val progress = ProgressDialog(this)
        progress.setMessage("Wiping")
        progress.setCancelable(false)
        progress.show()

        // Prefer OS-level wipe (clears app user data and kills the process).
        val wipedBySystem = try {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            am.clearApplicationUserData()
        } catch (_: Exception) {
            false
        }

        if (wipedBySystem) {
            // System will kill us, but keep a short fallback exit.
            try {
                progress.dismiss()
            } catch (_: Exception) {
            }
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    finishAffinity()
                } catch (_: Exception) {
                }
                Process.killProcess(Process.myPid())
                exitProcess(0)
            }, 2000)
            return
        }

        // Fallback: best-effort manual wipe.
        thread {
            try {
                try {
                    Tor.getInstance(this).stop()
                } catch (_: Exception) {
                }

                try {
                    val cfg: RealmConfiguration? = Realm.getDefaultConfiguration()
                    if (cfg != null) {
                        try {
                            Realm.getDefaultInstance().close()
                        } catch (_: Exception) {
                        }
                        try {
                            Realm.deleteRealm(cfg)
                        } catch (_: Exception) {
                            try {
                                val realm = Realm.getDefaultInstance()
                                realm.executeTransaction { it.deleteAll() }
                                realm.close()
                            } catch (_: Exception) {
                            }
                        }
                    }
                } catch (_: Exception) {
                }

                try {
                    Settings.getPrefs(this).edit().clear().apply()
                } catch (_: Exception) {
                }

                try {
                    filesDir.listFiles()?.forEach { deleteRecursively(it) }
                } catch (_: Exception) {
                }
                try {
                    cacheDir.listFiles()?.forEach { deleteRecursively(it) }
                } catch (_: Exception) {
                }
            } finally {
                runOnUiThread {
                    try {
                        progress.dismiss()
                    } catch (_: Exception) {
                    }
                    try {
                        finishAffinity()
                    } catch (_: Exception) {
                    }
                    Process.killProcess(Process.myPid())
                    exitProcess(0)
                }
            }
        }
    }

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        try {
            file.delete()
        } catch (_: Exception) {
        }
    }

    private fun hasWriteExternalStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun refreshProfile() {
        val db = Database.getInstance(this)
        val n = db.getName().trim()
        displayName = if (n.isBlank()) "Anonymous" else n
        address = Tor.getInstance(this).getID() ?: ""
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    snackbarHostState: SnackbarHostState,
    pendingSnackbarMessage: String?,
    onClearPendingSnackbar: () -> Unit,
    name: String,
    address: String,
    disguiseEnabled: Boolean,
    onBack: () -> Unit,
    onChangeName: (String) -> Unit,
    onExport: () -> Unit,
    onExportWithPassword: (String) -> Unit,
    onToggleDisguise: (Boolean) -> Unit,
    onHardReset: () -> Unit,
    exportRequested: Boolean,
    onExportHandled: () -> Unit,
) {
    var showNameDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showHardResetDialog by remember { mutableStateOf(false) }

    LaunchedEffect(pendingSnackbarMessage) {
        val msg = pendingSnackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        onClearPendingSnackbar()
    }

    LaunchedEffect(exportRequested) {
        if (exportRequested) {
            onExportHandled()
            showExportDialog = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SETTINGS") },
                navigationIcon = { TextButton(onClick = onBack) { Text("<") } },
                actions = {}
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "PROFILE",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { showNameDialog = true }
                    )
                    Text(
                        text = address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "tap name to edit",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            Text(
                text = "ACTIONS",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "DISGUISE ICON",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Show as Kriptex Calculator",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
                Switch(
                    checked = disguiseEnabled,
                    onCheckedChange = { onToggleDisguise(it) },
                )
            }

            Button(
                onClick = onExport,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("EXPORT ID / BACKUP")
            }

            Button(
                onClick = { showHardResetDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text("WIPE")
            }

            // About button removed from Settings per UX request.
            // Theme lock text removed per UX request.
        }

        if (showNameDialog) {
            NameDialog(
                initial = name,
                onDismiss = { showNameDialog = false },
                onApply = {
                    showNameDialog = false
                    onChangeName(it)
                }
            )
        }

        if (showExportDialog) {
            ExportDialog(
                onDismiss = { showExportDialog = false },
                onExport = { password ->
                    showExportDialog = false
                    onExportWithPassword(password)
                }
            )
        }

        if (showHardResetDialog) {
            Dialog(onDismissRequest = { showHardResetDialog = false }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(12.dp)
                ) {
                    Text(text = "WIPE", fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "This will wipe local data (keys, rooms, messages, settings) and close the app.",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showHardResetDialog = false }) { Text("CANCEL") }
                        TextButton(onClick = {
                            showHardResetDialog = false
                            onHardReset()
                        }) { Text("WIPE") }
                    }
                }
            }
        }
    }
}

@Composable
private fun NameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(12.dp)
        ) {
            Text(text = "CHANGE ALIAS", fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= 32) text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("CANCEL") }
                TextButton(onClick = { onApply(text) }) { Text("APPLY") }
            }
        }
    }
}

@Composable
private fun ExportDialog(
    onDismiss: () -> Unit,
    onExport: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(12.dp)
        ) {
            Text(text = "PASSWORD FOR EXPORT BACKUP", fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))

            if (error != null) {
                Text(text = error!!, color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(6.dp))
            }

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Confirm") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("CANCEL") }
                TextButton(onClick = {
                    val p = password.trim()
                    val c = confirm.trim()
                    if (p.length < 8) {
                        error = "Password is less than 8 characters"
                        return@TextButton
                    }
                    if (p != c) {
                        error = "Password does not match"
                        return@TextButton
                    }
                    val pv = PasswordValidator.getInstance()
                    if (!pv.validate(p)) {
                        error = "Password must have 1 numeric, 1 upper letter and 1 special character"
                        return@TextButton
                    }
                    onExport(p)
                }) { Text("OK") }
            }
        }
    }
}

private fun Activity.createExportZip(password: String, onResult: (String?) -> Unit) {
    val progress = ProgressDialog(this)
    progress.setMessage("Creating export zip")
    progress.setCancelable(false)
    progress.show()

    thread {
        val outputPath = try {
            if (!Util.EXTERNAL_FOLDER.exists()) {
                Util.EXTERNAL_FOLDER.mkdir()
            }

            val dest = File(filesDir, "kriptex_backup.zip")
            val zipManager = ZipManager(this)
            zipManager.makeZip(dest.absolutePath)

            val privateKey = "tor/torserv/private_key"
            val hostname = "tor/torserv/hostname"
            val settingsName = "settings.prop"
            val contactFile = "contacts.json"

            val properties = Properties()
            properties.setProperty("name", Database.getInstance(this).get("name"))
            properties.setProperty("use_dark_theme", "true")
            try {
                properties.store(FileOutputStream(File(cacheDir, settingsName)), null)
            } catch (_: Exception) {
            }

            zipManager.addZipFile(File(filesDir, privateKey).absolutePath, privateKey)
            zipManager.addZipFile(File(filesDir, hostname).absolutePath, hostname)
            zipManager.addZipFile(File(cacheDir, settingsName).absolutePath, settingsName)

            try {
                // Preserve legacy behavior: contacts export only.
                val contactsJson = File(cacheDir, contactFile)
                val gson = Gson()
                val realm = Realm.getDefaultInstance()
                val contacts = realm.where(Contact::class.java).findAll()
                val jsonWriter = JsonWriter(FileWriter(contactsJson))
                jsonWriter.beginArray()
                for (i in 0 until contacts.size) {
                    jsonWriter.jsonValue(gson.toJson(realm.copyFromRealm(contacts[i])))
                    jsonWriter.flush()
                }
                jsonWriter.endArray()
                jsonWriter.close()
                realm.close()
                zipManager.addZipFile(contactsJson.absolutePath, contactFile)
            } catch (_: Exception) {
            }

            zipManager.closeZip()

            val destination = File(Util.EXTERNAL_FOLDER, "kriptex_backup.zip")
            val advancedCrypto = AdvancedCrypto(password)
            advancedCrypto.encryptFile(dest.absolutePath, destination.absolutePath)
            destination.absolutePath
        } catch (_: Exception) {
            null
        }

        runOnUiThread {
            progress.dismiss()
            onResult(outputPath)
        }
    }
}
