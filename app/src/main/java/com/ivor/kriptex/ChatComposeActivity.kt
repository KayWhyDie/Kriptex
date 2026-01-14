package com.ivor.kriptex

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.appcompat.app.AppCompatActivity
import com.ivor.kriptex.db.Contact
import com.ivor.kriptex.db.Database
import com.ivor.kriptex.db.Message
import com.ivor.kriptex.service.KriptexHostService
import com.ivor.kriptex.tor.Client
import com.ivor.kriptex.tor.FileServer
import com.ivor.kriptex.tor.Server
import com.ivor.kriptex.tor.Tor
import com.ivor.kriptex.ui.compose.KriptexTheme
import com.ivor.kriptex.utils.TimeAgo
import com.ivor.kriptex.utils.Util
import io.realm.Realm
import io.realm.RealmChangeListener
import io.realm.RealmResults
import io.realm.Sort
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class ChatComposeActivity : AppCompatActivity() {

    private lateinit var tor: Tor
    private lateinit var server: Server

    private var address: String = ""
    private var mediaFolder: File? = null

    private var realm: Realm? = null
    private var messagesRealm: Realm? = null

    private var contact: Contact? = null
    private var messages: RealmResults<Message>? = null

    private val messagesVersion: MutableIntState = mutableIntStateOf(0)
    private val headerVersion: MutableIntState = mutableIntStateOf(0)

    private val messagesListener = RealmChangeListener<RealmResults<Message>> {
        messagesVersion.intValue++
    }

    private val torListener = Tor.Listener {
        headerVersion.intValue++
    }

    private val torLogListener = Tor.LogListener {
        headerVersion.intValue++
    }

    private var pendingSnackbarMessage: String? by mutableStateOf(null)

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        handlePickedUri(uri, Message.TYPE_IMAGE)
    }

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        handlePickedUri(uri, Message.TYPE_VIDEO)
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        handlePickedUri(uri, Message.TYPE_FILE)
    }

    private var pendingCameraOutputPath: String? = null
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = pendingCameraOutputPath
        pendingCameraOutputPath = null
        if (ok && path != null && File(path).exists()) {
            startActivity(
                Intent(this, SendMediaComposeActivity::class.java)
                    .putExtra(SendMediaComposeActivity.EXTRA_ADDRESS, address)
                    .putExtra(SendMediaComposeActivity.EXTRA_FILE_PATH, path)
                    .putExtra(SendMediaComposeActivity.EXTRA_FILE_TYPE, Message.TYPE_IMAGE)
            )
        }
    }

    private var pendingVideoOutputPath: String? = null
    private val captureVideo = registerForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        val path = pendingVideoOutputPath
        pendingVideoOutputPath = null
        if (ok && path != null && File(path).exists()) {
            startActivity(
                Intent(this, SendMediaComposeActivity::class.java)
                    .putExtra(SendMediaComposeActivity.EXTRA_ADDRESS, address)
                    .putExtra(SendMediaComposeActivity.EXTRA_FILE_PATH, path)
                    .putExtra(SendMediaComposeActivity.EXTRA_FILE_TYPE, Message.TYPE_VIDEO)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tor = Tor.getInstance(this)
        server = Server.getInstance(this)
        ContextCompat.startForegroundService(this, Intent(this, KriptexHostService::class.java))

        address = intent?.dataString ?: ""
        if (address.contains(":")) {
            address = address.substring(address.indexOf(':') + 1)
        }
        address = address.trim()
        if (address.isBlank()) {
            finish()
            return
        }

        mediaFolder = File(filesDir, address).also { folder ->
            if (!folder.exists()) folder.mkdir()
        }

        realm = Realm.getDefaultInstance()
        contact = realm!!.where(Contact::class.java).equalTo("address", address).findFirst()
        if (contact == null) {
            finish()
            return
        }

        messagesRealm = Realm.getDefaultInstance()
        messages = messagesRealm!!.where(Message::class.java)
            .beginGroup()
            .equalTo("sender", address)
            .or()
            .equalTo("receiver", address)
            .endGroup()
            .and()
            .not()
            .beginsWith("content", "roommsg:")
            .sort("stableId", Sort.ASCENDING)
            .findAll()
        messages!!.addChangeListener(messagesListener)

        setContent {
            KriptexTheme {
                ChatScreen(
                    activity = this,
                    address = address,
                    contact = contact,
                    messages = messages,
                    messagesVersion = messagesVersion.intValue,
                    headerVersion = headerVersion.intValue,
                    myId = tor.getID() ?: "",
                    myAlias = Database.getInstance(this).name,
                    torReady = tor.isReady(),
                    torStatus = torStatusText(),
                    pendingSnackbarMessage = pendingSnackbarMessage,
                    onClearPendingSnackbar = { pendingSnackbarMessage = null },
                    onBack = { finish() },
                    onSendText = { text, quote -> sendText(text, quote) },
                    onPickImage = { pickImage.launch("image/*") },
                    onPickVideo = { pickVideo.launch("video/*") },
                    onPickFile = { pickFile.launch("*/*") },
                    onTakePicture = { launchTakePicture() },
                    onCaptureVideo = { launchCaptureVideo() },
                    onDownloadMessage = { message ->
                        server.downloadFile(message)
                        messagesVersion.intValue++
                    },
                    onOpenAttachment = { message ->
                        openAttachment(message)
                    },
                    onDeleteMessage = { messageId ->
                        deleteMessage(messageId)
                    },
                    onCopyText = { text ->
                        Util.setClipboard(this, text)
                        pendingSnackbarMessage = "Copied"
                    }
                )
            }
        }

        // Entering the chat marks it as read.
        clearContactPending()
    }

    override fun onResume() {
        super.onResume()
        Tor.getInstance(this).addListener(torListener)
        Tor.getInstance(this).addLogListener(torLogListener)
        sendPending()
        clearContactPending()
        ContextCompat.startForegroundService(this, Intent(this, KriptexHostService::class.java))
        headerVersion.intValue++
    }

    override fun onPause() {
        Tor.getInstance(this).removeListener(torListener)
        Tor.getInstance(this).removeLogListener(torLogListener)
        super.onPause()
    }

    override fun onDestroy() {
        try {
            messages?.removeAllChangeListeners()
        } catch (_: Exception) {
            // Best-effort cleanup; avoid crashing on teardown.
        }
        try {
            messagesRealm?.close()
        } catch (_: Exception) {
        }
        try {
            realm?.close()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    fun clearContactPending() {
        val contactId = contact?._id ?: return
        val pending = contact?.pending ?: 0
        if (pending <= 0) return

        realm?.executeTransactionAsync(
            { bgRealm ->
                val bgContact = bgRealm.where(Contact::class.java)
                    .equalTo("_id", contactId)
                    .findFirst()
                if (bgContact != null && bgContact.pending > 0) {
                    bgContact.pending = 0
                }
            },
            {
                // no-op
            },
            { e ->
                Log.e("ChatComposeActivity", "clearContactPending failed", e)
            }
        )
    }

    private fun sendPending() {
        Client.getInstance(this).startSendPendingMessages(address)
    }

    private fun sendText(text: String, quote: QuoteInfo?) {
        val sender = tor.getID()
        if (sender.isNullOrBlank()) {
            sendPending()
            return
        }
        val message = text.trim()
        if (message.isBlank()) return

        Message.addPendingOutgoingMessage(
            sender,
            address,
            message,
            quote?.primaryKey,
            quote?.sender,
            quote?.content
        )
        sendPending()
        messagesVersion.intValue++
    }

    private fun handlePickedUri(uri: Uri?, fallbackType: Int) {
        if (uri == null) return

        val filePath = try {
            Util.getFilePath(this, uri)
        } catch (_: Exception) {
            null
        }

        val path = filePath?.takeIf { File(it).exists() } ?: copyUriToInternalFile(uri)
        if (path == null || !File(path).exists()) {
            pendingSnackbarMessage = "Unable to read selected file"
            return
        }

        startActivity(
            Intent(this, SendMediaComposeActivity::class.java)
                .putExtra(SendMediaComposeActivity.EXTRA_ADDRESS, address)
                .putExtra(SendMediaComposeActivity.EXTRA_FILE_PATH, path)
                .putExtra(SendMediaComposeActivity.EXTRA_FILE_TYPE, fallbackType)
        )
    }

    private fun copyUriToInternalFile(uri: Uri): String? {
        return try {
            val input = contentResolver.openInputStream(uri) ?: return null
            val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString()
            val outFile = File(filesDir, name)
            FileOutputStream(outFile).use { fos ->
                input.use { it.copyTo(fos) }
            }
            outFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    private fun deleteMessage(messageId: String) {
        val r = Realm.getDefaultInstance()
        r.executeTransaction { tx ->
            tx.where(Message::class.java).equalTo("primaryKey", messageId).findFirst()?.deleteFromRealm()
        }
        r.close()
        messagesVersion.intValue++
    }

    private fun launchTakePicture() {
        val outFile = File(cacheDir, "${UUID.randomUUID()}.jpg")
        pendingCameraOutputPath = outFile.absolutePath
        val uri = FileProvider.getUriForFile(
            this,
            applicationContext.packageName + ".provider",
            outFile
        )
        takePicture.launch(uri)
    }

    private fun launchCaptureVideo() {
        val outFile = File(cacheDir, "${UUID.randomUUID()}.mp4")
        pendingVideoOutputPath = outFile.absolutePath
        val uri = FileProvider.getUriForFile(
            this,
            applicationContext.packageName + ".provider",
            outFile
        )
        captureVideo.launch(uri)
    }

    private fun openAttachment(message: Message) {
        val fileShare = message.fileShare ?: return
        val isMine = message.sender == (tor.getID() ?: "")
        val filePath = if (isMine) {
            fileShare.filePath
        } else {
            File(File(filesDir, message.sender), fileShare.filename).absolutePath
        }
        val file = File(filePath)
        if (!file.exists()) {
            pendingSnackbarMessage = "File not present"
            return
        }

        val uri = FileProvider.getUriForFile(
            this,
            applicationContext.packageName + ".provider",
            file
        )

        var mime = FileServer.getMimeType(filePath)
        if (mime.isNullOrBlank()) {
            mime = fileShare.mimeType
        }
        val openIntent = Intent(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .setDataAndType(uri, mime)
        try {
            startActivity(openIntent)
        } catch (_: Exception) {
            pendingSnackbarMessage = "No app to open this file"
        }
    }

    private fun torStatusText(): String {
        val raw = tor.status ?: ""
        var status = raw
        val i = status.indexOf(']')
        if (i >= 0) status = status.substring(i + 1)
        return status.trim()
    }
}

data class QuoteInfo(
    val primaryKey: String,
    val sender: String,
    val content: String?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(
    activity: ChatComposeActivity,
    address: String,
    contact: Contact?,
    messages: RealmResults<Message>?,
    messagesVersion: Int,
    headerVersion: Int,
    myId: String,
    myAlias: String?,
    torReady: Boolean,
    torStatus: String,
    pendingSnackbarMessage: String?,
    onClearPendingSnackbar: () -> Unit,
    onBack: () -> Unit,
    onSendText: (String, QuoteInfo?) -> Unit,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onPickFile: () -> Unit,
    onTakePicture: () -> Unit,
    onCaptureVideo: () -> Unit,
    onDownloadMessage: (Message) -> Unit,
    onOpenAttachment: (Message) -> Unit,
    onDeleteMessage: (String) -> Unit,
    onCopyText: (String) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var composerText by remember { mutableStateOf("") }
    var attachOpen by remember { mutableStateOf(false) }
    var quote by remember { mutableStateOf<QuoteInfo?>(null) }
    var actionsFor by remember { mutableStateOf<Message?>(null) }

    LaunchedEffect(pendingSnackbarMessage) {
        val msg = pendingSnackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        onClearPendingSnackbar()
    }

    val title = remember(headerVersion) {
        val n = contact?.name ?: ""
        if (n.isBlank()) address else n
    }

    val list = remember(messagesVersion) {
        messages?.toList() ?: emptyList()
    }

    LaunchedEffect(messagesVersion) {
        if (list.isNotEmpty()) {
            listState.scrollToItem(list.size - 1)
        }
        // New messages + auto-scroll means the user has effectively seen them.
        activity.clearContactPending()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("<") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!torReady) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(text = "TOR BOOTSTRAP", fontWeight = FontWeight.SemiBold)
                    Text(
                        text = torStatus.takeIf { it.isNotBlank() } ?: "starting…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(list, key = { it.primaryKey }) { msg ->
                    ChatMessageRow(
                        activity = activity,
                        message = msg,
                        isMine = msg.sender == myId,
                        myAlias = myAlias,
                        chatAddress = address,
                        contactAlias = contact?.name,
                        onActions = { actionsFor = msg },
                        onDownload = { onDownloadMessage(msg) },
                        onOpen = { onOpenAttachment(msg) }
                    )
                }
            }

            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

            if (quote != null) {
                QuoteBar(quote = quote!!, onClear = { quote = null })
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    Button(
                        onClick = { attachOpen = !attachOpen },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) { Text("+") }

                    DropdownMenu(expanded = attachOpen, onDismissRequest = { attachOpen = false }) {
                        DropdownMenuItem(text = { Text("TAKE PICTURE") }, onClick = {
                            attachOpen = false
                            onTakePicture()
                        })
                        DropdownMenuItem(text = { Text("RECORD VIDEO") }, onClick = {
                            attachOpen = false
                            onCaptureVideo()
                        })
                        DropdownMenuItem(text = { Text("ATTACH IMAGE") }, onClick = {
                            attachOpen = false
                            onPickImage()
                        })
                        DropdownMenuItem(text = { Text("ATTACH VIDEO") }, onClick = {
                            attachOpen = false
                            onPickVideo()
                        })
                        DropdownMenuItem(text = { Text("ATTACH FILE") }, onClick = {
                            attachOpen = false
                            onPickFile()
                        })
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                TextField(
                    value = composerText,
                    onValueChange = { composerText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("message") },
                    singleLine = false,
                    maxLines = 4
                )

                Spacer(modifier = Modifier.width(10.dp))

                Button(
                    onClick = {
                        onSendText(composerText, quote)
                        composerText = ""
                        quote = null
                    },
                    enabled = composerText.trim().isNotEmpty()
                ) { Text("SEND") }
            }
        }
    }

    if (actionsFor != null) {
        val m = actionsFor!!
        AlertDialog(
            onDismissRequest = { actionsFor = null },
            title = { Text("MESSAGE") },
            text = {
                Column {
                    TextButton(onClick = {
                        actionsFor = null
                        onCopyText(m.content ?: "")
                    }) { Text("COPY") }
                    TextButton(onClick = {
                        actionsFor = null
                        quote = QuoteInfo(m.primaryKey, m.sender, m.content)
                    }) { Text("REPLY") }
                    TextButton(onClick = {
                        actionsFor = null
                        onDeleteMessage(m.primaryKey)
                    }) { Text("DELETE") }
                }
            },
            confirmButton = { TextButton(onClick = { actionsFor = null }) { Text("CLOSE") } }
        )
    }
}

@Composable
private fun QuoteBar(quote: QuoteInfo, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "REPLY", fontWeight = FontWeight.SemiBold)
            Text(
                text = quote.content?.takeIf { it.isNotBlank() } ?: "(attachment)",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
        }
        TextButton(onClick = onClear) { Text("X") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatMessageRow(
    activity: ChatComposeActivity,
    message: Message,
    isMine: Boolean,
    myAlias: String?,
    chatAddress: String,
    contactAlias: String?,
    onActions: () -> Unit,
    onDownload: () -> Unit,
    onOpen: () -> Unit,
) {
    val isChatRoomOther = !isMine && message.sender != chatAddress
    val lineColor = when {
        isMine -> Color.White
        isChatRoomOther -> Color(0xFFB388FF) // purple
        else -> MaterialTheme.colorScheme.primary
    }

    val isAttachment = message.type != Message.TYPE_TEXT
    val isDownloaded = message.fileShare?.isDownloaded ?: true

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .combinedClickable(
                onClick = {
                    if (isAttachment) {
                        // Manual download only (no tap-to-download).
                        if (isDownloaded) onOpen()
                    }
                },
                onLongClick = onActions
            )
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (message.quotedMessageId != null) {
                    Text(
                        text = "↪ ${message.quotedMessageContent ?: "(attachment)"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                val header = when (message.type) {
                    Message.TYPE_TEXT -> null
                    Message.TYPE_IMAGE -> "[IMAGE]"
                    Message.TYPE_VIDEO -> "[VIDEO]"
                    Message.TYPE_AUDIO -> "[AUDIO]"
                    Message.TYPE_FILE -> "[FILE]"
                    else -> "[MSG]"
                }

                val content = message.content?.takeIf { it.isNotBlank() }
                val fileName = message.fileShare?.filename
                val payload = content ?: fileName ?: ""
                val displayPayload = when {
                    header != null && payload.isNotBlank() -> "$header $payload"
                    header != null -> header
                    else -> payload
                }

                val alias = when {
                    isMine -> myAlias?.trim()?.takeIf { it.isNotBlank() } ?: "Anonymous"
                    message.sender == chatAddress -> contactAlias?.takeIf { it.isNotBlank() } ?: "peer"
                    else -> message.sender?.takeIf { it.isNotBlank() }?.take(8) ?: "peer"
                }

                Text(
                    text = "$alias: $displayPayload",
                    color = lineColor,
                    style = MaterialTheme.typography.bodyMedium
                )

                if (isAttachment && !isMine && !isDownloaded) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = onDownload,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Text("DOWNLOAD")
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    // Intentionally no delivery ticks.
                }
            }
        }
    }
}
