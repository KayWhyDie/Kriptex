package com.ivor.kriptex

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.aditya.filebrowser.Constants
import com.aditya.filebrowser.FileChooser
import com.ivor.kriptex.crypto.AdvancedCrypto
import com.ivor.kriptex.db.Contact
import com.ivor.kriptex.db.Database
import com.ivor.kriptex.db.Message
import com.ivor.kriptex.ui.compose.KriptexTheme
import com.ivor.kriptex.utils.Settings
import com.ivor.kriptex.utils.Util
import com.ivor.kriptex.utils.ZipManager
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import io.realm.Realm
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileReader
import java.io.IOException
import java.util.Properties

class ImportIdComposeActivity : AppCompatActivity() {

    private var selectedFilePath by mutableStateOf<String?>(null)
    private var isImporting by mutableStateOf(false)
    private var importSucceeded by mutableStateOf(false)
    private var showPasswordDialog by mutableStateOf(false)

    private var alias by mutableStateOf(TextFieldValue(""))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            KriptexTheme {
                Surface {
                    ImportIdScreen(
                        selectedFilePath = selectedFilePath,
                        isImporting = isImporting,
                        importSucceeded = importSucceeded,
                        alias = alias,
                        onAliasChange = { if (it.text.length <= 32) alias = it },
                        onSelectFile = { selectZip() },
                        onImport = { showPasswordDialog = true },
                        onStart = { startWithName() },
                    )

                    if (showPasswordDialog) {
                        PasswordDialog(
                            onDismiss = { showPasswordDialog = false },
                            onConfirm = { password ->
                                showPasswordDialog = false
                                startImport(password)
                            },
                        )
                    }
                }
            }
        }
    }

    private fun selectZip() {
        val fileChooserIntent = Intent(applicationContext, FileChooser::class.java)
        fileChooserIntent.putExtra(Constants.SELECTION_MODE, Constants.SELECTION_MODES.SINGLE_SELECTION.ordinal)
        fileChooserIntent.putExtra(Constants.INITIAL_DIRECTORY, Util.EXTERNAL_FOLDER.absolutePath)
        fileChooserIntent.putExtra(Constants.ALLOWED_FILE_EXTENSIONS, "zip")
        startActivityForResult(fileChooserIntent, PICK_FILE_REQUEST)
    }

    private fun startImport(password: String) {
        val path = selectedFilePath ?: return
        if (password.length < 8) return
        ImportID(password).execute(path)
    }

    private fun startWithName() {
        val name = alias.text.trim()
        if (name.isEmpty()) return
        Database.getInstance(this).setName(name)
        Settings.putBoolean(applicationContext, "start_setup_completed", true)
        startActivity(
            Intent(this, MainComposeActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return

        if (requestCode == PICK_FILE_REQUEST && data != null) {
            val file: Uri? = data.data
            selectedFilePath = file?.path
        }
    }

    private inner class ImportID(private val password: String) : AsyncTask<String, Void, Boolean>() {

        override fun onPreExecute() {
            super.onPreExecute()
            isImporting = true
            importSucceeded = false
        }

        override fun doInBackground(vararg strings: String): Boolean {
            val zipManager = ZipManager(applicationContext)

            val inputFile = strings[0]
            val outPutFile = File(filesDir, "kriptex_backup.zip").absolutePath

            try {
                val advancedCrypto = AdvancedCrypto(password)
                advancedCrypto.decryptFile(inputFile, outPutFile)
            } catch (e: Exception) {
                e.printStackTrace()
                return false
            }

            zipManager.unZip(filesDir.absolutePath, outPutFile)

            val settingsFileName = "settings.prop"
            val messagesFileName = "messages.json"
            val contactsFileName = "contacts.json"

            val database = Database.getInstance(applicationContext)

            val props = Properties()
            try {
                props.load(FileInputStream(File(filesDir, settingsFileName)))
                database.put("name", props.getProperty("name"))
                // Single-theme app: ignore any stored light/dark toggle.
            } catch (e: IOException) {
                e.printStackTrace()
            }

            val realm = Realm.getDefaultInstance()
            realm.beginTransaction()
            val gson = Gson()
            try {
                val contactsFile = File(filesDir, contactsFileName)
                var jsonReader = JsonReader(FileReader(contactsFile))
                var jsonElement = JsonParser.parseReader(jsonReader)
                var asJsonArray: JsonArray = jsonElement.asJsonArray
                for (je: JsonElement in asJsonArray) {
                    val contact = gson.fromJson(je, Contact::class.java)
                    realm.copyToRealm(contact)
                }
                jsonReader.close()
                contactsFile.delete()

                val messagesFile = File(filesDir, messagesFileName)
                jsonReader = JsonReader(FileReader(messagesFile))
                jsonElement = JsonParser.parseReader(jsonReader)
                asJsonArray = jsonElement.asJsonArray
                for (je: JsonElement in asJsonArray) {
                    val message = gson.fromJson(je, Message::class.java)
                    realm.copyToRealm(message)
                }
                jsonReader.close()
                messagesFile.delete()

            } catch (e: FileNotFoundException) {
                e.printStackTrace()
            } catch (e: IOException) {
                e.printStackTrace()
            }
            realm.commitTransaction()
            realm.close()

            return true
        }

        override fun onPostExecute(result: Boolean) {
            super.onPostExecute(result)
            isImporting = false
            importSucceeded = result

            if (result) {
                alias = TextFieldValue(Database.getInstance(applicationContext).get("name"))
            }
        }
    }

    private companion object {
        const val PICK_FILE_REQUEST = 100
    }
}

@Composable
private fun ImportIdScreen(
    selectedFilePath: String?,
    isImporting: Boolean,
    importSucceeded: Boolean,
    alias: TextFieldValue,
    onAliasChange: (TextFieldValue) -> Unit,
    onSelectFile: () -> Unit,
    onImport: () -> Unit,
    onStart: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "IMPORT BACKUP",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "decrypt → restore contacts + history",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = onSelectFile,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Text(text = "SELECT .ZIP", style = MaterialTheme.typography.labelLarge)
        }

        if (selectedFilePath != null) {
            Text(
                text = selectedFilePath,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onImport,
                enabled = !isImporting && selectedFilePath != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color(0xFF050507),
                    disabledContainerColor = MaterialTheme.colorScheme.outline,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Text(text = "IMPORT", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(modifier = Modifier.size(12.dp))
            if (isImporting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.size(10.dp))
                Text(
                    text = "working…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (importSucceeded) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = alias,
                onValueChange = onAliasChange,
                singleLine = true,
                label = { Text("ALIAS") },
            )

            Button(
                onClick = onStart,
                enabled = alias.text.trim().isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Text(text = "ENTER", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun PasswordDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var password by androidx.compose.runtime.remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "PASSWORD",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "enter backup password (min 8)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text("PASSWORD") },
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password.trim()) },
                enabled = password.trim().length >= 8,
            ) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL") }
        },
    )
}
