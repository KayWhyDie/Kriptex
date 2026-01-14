package com.ivor.kriptex

import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ivor.kriptex.db.Contact
import com.ivor.kriptex.db.Message
import com.ivor.kriptex.service.VideoTranscodeService
import com.ivor.kriptex.tor.Client
import com.ivor.kriptex.tor.FileServer
import com.ivor.kriptex.tor.Tor
import com.ivor.kriptex.ui.compose.KriptexTheme
import com.ivor.kriptex.utils.MimeTypes
import com.ivor.kriptex.utils.Util
import io.realm.Realm
import io.realm.Sort
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import kotlin.concurrent.thread

class ShareMediaComposeActivity : AppCompatActivity() {

    private val snackbarHostState = SnackbarHostState()

    private var mimeType: String? = null
    private var inputUri: Uri? = null

    private var canSend by mutableStateOf(false)
    private var filePath by mutableStateOf<String?>(null)
    private var fileName by mutableStateOf<String?>(null)
    private var fileSize by mutableStateOf<String?>(null)
    private var filePreviewBitmap by mutableStateOf<Bitmap?>(null)

    private lateinit var contactsRealm: Realm
    private val contacts = mutableStateOf<List<Contact>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent?.action
        mimeType = intent?.type

        if (Intent.ACTION_SEND != action) {
            finish()
            return
        }

        inputUri = intent.getParcelableExtra(Intent.EXTRA_STREAM)
        if (inputUri == null || mimeType == null) {
            Toast.makeText(this, "Shared file is not present", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (mimeType == "text/plain") {
            Toast.makeText(this, "Text cannot be shared", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        contactsRealm = Realm.getDefaultInstance()
        loadContacts()
        copyIntoInternalStorage(inputUri!!, mimeType!!)

        setContent {
            KriptexTheme {
                ShareMediaScreen(
                    title = "SHARE",
                    fileName = fileName,
                    fileMime = mimeType,
                    fileSize = fileSize,
                    canSend = canSend,
                    preview = filePreviewBitmap,
                    contacts = contacts.value,
                    snackbarHostState = snackbarHostState,
                    onBack = { finish() },
                    onSend = { message, selected -> sendToSelected(message, selected) },
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::contactsRealm.isInitialized && !contactsRealm.isClosed) {
            contactsRealm.close()
        }
    }

    private fun loadContacts() {
        val results = contactsRealm.where(Contact::class.java)
            .equalTo("incoming", 0 as Int)
            .findAll()
            .sort("lastMessageTime", Sort.DESCENDING)
        contacts.value = contactsRealm.copyFromRealm(results)
    }

    private fun copyIntoInternalStorage(uri: Uri, declaredMimeType: String) {
        thread {
            try {
                val resolver = contentResolver
                val displayName = queryDisplayName(resolver, uri)
                val ext = MimeTypes.getDefaultExt(declaredMimeType)
                val safeName = when {
                    !displayName.isNullOrBlank() -> displayName
                    !ext.isNullOrBlank() -> "${UUID.randomUUID()}.$ext"
                    else -> UUID.randomUUID().toString()
                }

                val outFile = File(filesDir, safeName)
                resolver.openInputStream(uri).use { input ->
                    if (input == null) throw IllegalStateException("Unable to open shared content")
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                        output.flush()
                    }
                }

                val finalMime = declaredMimeType.ifBlank { FileServer.getMimeType(outFile.absolutePath) ?: declaredMimeType }

                val preview = createPreviewBitmap(outFile.absolutePath, finalMime)
                runOnUiThread {
                    mimeType = finalMime
                    filePath = outFile.absolutePath
                    fileName = outFile.name
                    fileSize = Util.humanReadableByteCountBin(outFile.length())
                    filePreviewBitmap = preview
                    canSend = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    canSend = false
                    Toast.makeText(this, "Unable find media for sending", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun createPreviewBitmap(path: String, mime: String): Bitmap? {
        return try {
            when {
                mime.startsWith("image") -> {
                    BitmapFactory.decodeFile(path)
                }
                mime.startsWith("video") -> {
                    ThumbnailUtils.createVideoThumbnail(path, MediaStore.Video.Thumbnails.MINI_KIND)
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sendToSelected(description: String, selectedAddresses: Set<String>) {
        if (!canSend) {
            Toast.makeText(this, "Unable find media for sending", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val currentPath = filePath
        val currentMime = mimeType
        if (currentPath.isNullOrBlank() || currentMime.isNullOrBlank()) {
            Toast.makeText(this, "Unable find media for sending", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (selectedAddresses.isEmpty()) {
            Toast.makeText(this, "Please select some contact", Toast.LENGTH_SHORT).show()
            return
        }

        val sender = Tor.getInstance(this).getID()
        if (sender.isNullOrBlank()) {
            Toast.makeText(this, "Tor not ready", Toast.LENGTH_SHORT).show()
            return
        }

        val msg = description.trim()
        val type = FileServer.getMessageType(currentMime)

        when (type) {
            Message.TYPE_IMAGE -> {
                resizeAndSendImage(sender, msg, currentPath, currentMime, selectedAddresses)
            }
            Message.TYPE_VIDEO -> {
                sendVideo(sender, msg, currentPath, currentMime, selectedAddresses)
            }
            else -> {
                for (a in selectedAddresses) {
                    sendMessage(sender, a, msg, currentPath, currentMime, type, null)
                }
                finish()
            }
        }
    }

    private fun resizeAndSendImage(
        sender: String,
        message: String,
        inputPath: String,
        mime: String,
        recipients: Set<String>,
    ) {
        thread {
            try {
                val inputFile = File(inputPath)
                val outFile = File(filesDir, inputFile.name)

                val resized = Util.lessResolution(inputFile.absolutePath, 1280, 720)
                val thumbnailBytes: ByteArray

                FileOutputStream(outFile).use { out ->
                    resized.compress(Bitmap.CompressFormat.JPEG, 75, out)
                    out.flush()
                }

                val thumb = Bitmap.createScaledBitmap(resized, 64, 64, false)
                val baos = java.io.ByteArrayOutputStream()
                thumb.compress(Bitmap.CompressFormat.JPEG, 100, baos)
                thumbnailBytes = baos.toByteArray()

                resized.recycle()
                thumb.recycle()

                runOnUiThread {
                    for (a in recipients) {
                        sendMessage(sender, a, message, outFile.absolutePath, mime, Message.TYPE_IMAGE, thumbnailBytes)
                    }
                    finish()
                }
            } catch (_: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Failed to resize image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun sendVideo(
        sender: String,
        message: String,
        inputPath: String,
        mime: String,
        recipients: Set<String>,
    ) {
        val thumbBmp = try {
            ThumbnailUtils.createVideoThumbnail(inputPath, MediaStore.Video.Thumbnails.MINI_KIND)
        } catch (_: Exception) {
            null
        }

        val thumbnailPath = if (thumbBmp != null) {
            val tiny = Bitmap.createScaledBitmap(thumbBmp, 64, 64, false)
            val name = UUID.randomUUID().toString()
            val p = Util.writeBitmapCache(this, tiny, name)
            tiny.recycle()
            thumbBmp.recycle()
            p
        } else {
            null
        }

        val addresses = recipients.toTypedArray()
        if (checkSizeIsLarge(inputPath)) {
            VideoTranscodeService.startVideoTranscode(
                this,
                inputPath,
                File(filesDir, "${UUID.randomUUID()}.mp4").absolutePath,
                addresses,
                message,
                mime,
                Message.TYPE_VIDEO,
                thumbnailPath
            )
        } else {
            val thumbBytes = if (!thumbnailPath.isNullOrBlank()) {
                try {
                    Util.readSmallFile(applicationContext, thumbnailPath)
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            for (a in recipients) {
                sendMessage(sender, a, message, inputPath, mime, Message.TYPE_VIDEO, thumbBytes)
            }
        }

        finish()
    }

    private fun checkSizeIsLarge(filepath: String): Boolean {
        val file = File(filepath)
        val fileSizeInMB = (file.length() / 1024) / 1024
        return fileSizeInMB > 5
    }

    private fun sendMessage(
        sender: String,
        receiver: String,
        message: String,
        filePath: String,
        mimeType: String,
        type: Int,
        thumbnail: ByteArray?,
    ) {
        Message.addPendingOutgoingMessage(
            sender,
            receiver,
            message,
            File(filePath).name,
            filePath,
            mimeType,
            type,
            thumbnail
        )
        Client.getInstance(this).startSendPendingMessages(receiver)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareMediaScreen(
    title: String,
    fileName: String?,
    fileMime: String?,
    fileSize: String?,
    canSend: Boolean,
    preview: Bitmap?,
    contacts: List<Contact>,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onSend: (String, Set<String>) -> Unit,
) {
    var description by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }

    val filtered = remember(contacts, searchText) {
        val q = searchText.trim()
        if (q.isBlank()) contacts else contacts.filter { c ->
            val name = c.name ?: ""
            val addr = c.address ?: ""
            name.contains(q, true) || addr.contains(q, true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searchOpen) {
                        TextField(
                            value = searchText,
                            onValueChange = { searchText = it },
                            placeholder = { Text("search") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    } else {
                        Text(title)
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("<") } },
                actions = {
                    if (searchOpen) {
                        TextButton(onClick = {
                            searchOpen = false
                            searchText = ""
                        }) { Text("CLOSE") }
                    } else {
                        TextButton(onClick = { searchOpen = true }) { Text("SEARCH") }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
        ) {
            Text(
                text = "PAYLOAD",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center
                ) {
                    if (preview != null) {
                        androidx.compose.foundation.Image(
                            bitmap = preview.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(text = "FILE")
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = fileName ?: "(unknown)",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${fileMime ?: ""}  ${fileSize ?: ""}".trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )
                }

                Text(
                    text = if (canSend) "READY" else "BAD",
                    color = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            TextField(
                value = description,
                onValueChange = { description = it },
                placeholder = { Text("description") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 4
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TARGETS (${selected.size})",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { onSend(description, selected) },
                    enabled = canSend && selected.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("SEND")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filtered, key = { it._id }) { c ->
                    val addr = c.address ?: ""
                    val isSelected = selected.contains(addr)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable {
                                selected = if (isSelected) selected - addr else selected + addr
                            }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isSelected) "[X]" else "[ ]",
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = c.name?.takeIf { it.isNotBlank() } ?: addr,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = addr,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
