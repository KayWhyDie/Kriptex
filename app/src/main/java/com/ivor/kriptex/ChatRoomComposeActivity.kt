package com.ivor.kriptex

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.media.ThumbnailUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import com.ivor.kriptex.db.ChatRoom
import com.ivor.kriptex.db.ChatRoomMember
import com.ivor.kriptex.db.Contact
import com.ivor.kriptex.db.Database
import com.ivor.kriptex.db.Message
import com.ivor.kriptex.utils.Util
import com.ivor.kriptex.service.KriptexHostService
import com.ivor.kriptex.tor.Client
import com.ivor.kriptex.tor.Server
import com.ivor.kriptex.tor.Tor
import com.ivor.kriptex.ui.compose.KriptexTheme
import io.realm.Realm
import io.realm.RealmChangeListener
import io.realm.RealmResults
import io.realm.Sort
import kotlinx.coroutines.launch
import java.util.UUID

class ChatRoomComposeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ROOM_ID = "extra_room_id"
    }

    private lateinit var tor: Tor
    private lateinit var server: Server

    private var roomId: String = ""

    private var realm: Realm? = null
    private var messagesRealm: Realm? = null

    private var room: ChatRoom? = null
    private var members: RealmResults<ChatRoomMember>? = null
    private var messages: RealmResults<Message>? = null

    private val messagesVersion: MutableIntState = mutableIntStateOf(0)

    private val messagesListener = RealmChangeListener<RealmResults<Message>> {
        messagesVersion.intValue++
        if (isResumed) {
            markRoomRead()
        }
    }

    private var isResumed: Boolean = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tor = Tor.getInstance(this)
        server = Server.getInstance(this)
        ContextCompat.startForegroundService(this, Intent(this, KriptexHostService::class.java))

        roomId = intent.getStringExtra(EXTRA_ROOM_ID)?.trim().orEmpty()
        if (roomId.isBlank()) {
            finish()
            return
        }

        realm = Realm.getDefaultInstance()
        room = realm!!.where(ChatRoom::class.java).equalTo("id", roomId).findFirst()

        members = realm!!.where(ChatRoomMember::class.java)
            .equalTo("roomId", roomId)
            .findAll()

        messagesRealm = Realm.getDefaultInstance()
        val prefix = roomContentPrefix(roomId)
        messages = messagesRealm!!.where(Message::class.java)
            .beginGroup()
            .equalTo("roomId", roomId)
            .or()
            .beginsWith("content", prefix)
            .endGroup()
            .sort("stableId", Sort.ASCENDING)
            .findAll()
        messages!!.addChangeListener(messagesListener)

        // Announce our current alias to all known members.
        // This keeps name display stable even if a device joined before alias syncing existed.
        announceJoinToMembers()

        // If the user opened this room, consider current content read.
        markRoomRead()

        setContent {
            KriptexTheme {
                RoomScreen(
                    roomId = roomId,
                    roomName = room?.name,
                    myId = tor.getID() ?: "",
                    myAlias = Database.getInstance(this).name,
                    members = members,
                    messages = messages,
                    messagesVersion = messagesVersion.intValue,
                    pendingSnackbarMessage = pendingSnackbarMessage,
                    onClearPendingSnackbar = { pendingSnackbarMessage = null },
                    onBack = { finish() },
                    onShowInvite = { showRoomInviteQr() },
                    onPickImage = { pickImage.launch("image/*") },
                    onPickVideo = { pickVideo.launch("video/*") },
                    onPickFile = { pickFile.launch("*/*") },
                    onSendText = { text -> sendRoomText(text) },
                    onDownloadMessage = { message ->
                        server.downloadFile(message)
                        messagesVersion.intValue++
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        messages?.removeAllChangeListeners()
        realm?.close()
        messagesRealm?.close()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        markRoomRead()
    }

    override fun onPause() {
        isResumed = false
        // Persist last-read when leaving the room.
        markRoomRead()
        super.onPause()
    }

    private fun markRoomRead() {
        val r = realm ?: return
        val rid = roomId.trim()
        if (rid.isBlank()) return
        val prefix = roomContentPrefix(rid)
        r.executeTransaction { tx ->
            val roomObj = tx.where(ChatRoom::class.java).equalTo("id", rid).findFirst() ?: return@executeTransaction
            val maxStableId = tx.where(Message::class.java)
                .beginGroup()
                .equalTo("roomId", rid)
                .or()
                .beginsWith("content", prefix)
                .endGroup()
                .max("stableId")
                ?.toLong()
                ?: 0L
            if (maxStableId > roomObj.lastReadStableId) {
                roomObj.lastReadStableId = maxStableId
            }
        }
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

        val mime = try {
            contentResolver.getType(uri)
        } catch (_: Exception) {
            null
        }

        sendRoomMedia(path, mimeType = mime, fallbackType = fallbackType)
    }

    private fun copyUriToInternalFile(uri: Uri): String? {
        return try {
            val name = (getUriDisplayName(uri)?.takeIf { it.isNotBlank() }
                ?: ("upload_" + System.currentTimeMillis()))
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")

            val outFile = File(filesDir, name)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            outFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    private fun getUriDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sendRoomMedia(path: String, mimeType: String?, fallbackType: Int) {
        val myId = tor.getID() ?: return

        val memberAddresses = members?.mapNotNull { it.address }?.distinct().orEmpty().filter { it.isNotBlank() }
        if (memberAddresses.isEmpty()) {
            pendingSnackbarMessage = "No members in room"
            return
        }

        val file = File(path)
        if (!file.exists()) {
            pendingSnackbarMessage = "File missing"
            return
        }

        val type = when {
            mimeType?.startsWith("image/") == true -> Message.TYPE_IMAGE
            mimeType?.startsWith("video/") == true -> Message.TYPE_VIDEO
            else -> fallbackType
        }

        val roomMessageId = UUID.randomUUID().toString()
        val payload = encodeRoomPayload(roomId, roomMessageId, null, file.name)

        val thumbnailBytes: ByteArray? = try {
            when (type) {
                Message.TYPE_IMAGE -> {
                    val bmp = Util.lessResolution(file.absolutePath, 1280, 720)
                    val thumb = ThumbnailUtils.extractThumbnail(bmp, Util.THUMBNAIL_SIZE, Util.THUMBNAIL_SIZE)
                    val baos = ByteArrayOutputStream()
                    thumb.compress(Bitmap.CompressFormat.JPEG, 50, baos)
                    bmp.recycle()
                    thumb.recycle()
                    baos.toByteArray()
                }
                Message.TYPE_VIDEO -> {
                    val bmp = ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Video.Thumbnails.MINI_KIND)
                    if (bmp != null) {
                        val thumb = ThumbnailUtils.extractThumbnail(bmp, Util.THUMBNAIL_SIZE, Util.THUMBNAIL_SIZE)
                        val baos = ByteArrayOutputStream()
                        thumb.compress(Bitmap.CompressFormat.JPEG, 50, baos)
                        bmp.recycle()
                        thumb.recycle()
                        baos.toByteArray()
                    } else {
                        null
                    }
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }

        for (addr in memberAddresses) {
            val normalized = addr.trim().lowercase()
            if (normalized.isBlank() || normalized == myId) continue

            var shouldStartKeyExchange = false
            Realm.getDefaultInstance().use { r ->
                r.executeTransaction { tr ->
                    var c = tr.where(Contact::class.java).equalTo("address", normalized).findFirst()
                    if (c == null) {
                        val nextId = (tr.where(Contact::class.java).max("_id")?.toLong() ?: 0L) + 1L
                        c = tr.createObject(Contact::class.java, nextId)
                        c.setName("")
                        c.setDescription("")
                        c.setAddress(normalized)
                        c.setOutgoing(1)
                        c.setIncoming(0)
                        c.setPubKey(null)
                        shouldStartKeyExchange = true
                    } else if (c.getPubKey() == null || c.getPubKey().isEmpty()) {
                        c.setOutgoing(1)
                        shouldStartKeyExchange = true
                    }
                }
            }

            Message.addPendingOutgoingMessage(
                myId,
                normalized,
                payload,
                file.name,
                file.absolutePath,
                mimeType,
                type,
                thumbnailBytes
            )
            Client.getInstance(this).startSendPendingMessages(normalized)

            if (shouldStartKeyExchange) {
                Client.getInstance(this).startSendPendingFriends()
            }
        }

        messagesVersion.intValue++
    }

    private fun showRoomInviteQr() {
        val inviterId = tor.getID() ?: ""
        val roomName = room?.name ?: ""
        val txt = "KriptexRoom $roomId $inviterId $roomName"

        val qr = try {
            Encoder.encode(txt, ErrorCorrectionLevel.M)
        } catch (ex: Exception) {
            pendingSnackbarMessage = getString(R.string.invalid_qr_code)
            return
        }

        val mat = qr.matrix
        val width = mat.width
        val height = mat.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (mat[x, y].toInt() != 0) AndroidColor.BLACK else AndroidColor.WHITE
            }
        }

        var bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        val dm = resources.displayMetrics
        val target = (kotlin.math.min(dm.widthPixels, dm.heightPixels) * 0.75f).toInt().coerceAtLeast(1)
        bitmap = Bitmap.createScaledBitmap(bitmap, target, target, false)

        val view = android.widget.ImageView(this)
        view.setImageBitmap(bitmap)
        view.adjustViewBounds = true
        view.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        val pad = (16 * resources.displayMetrics.density).toInt()
        view.setPadding(pad, pad, pad, pad)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(view)
            .show()
    }

    private fun sendRoomText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val myId = tor.getID() ?: return

        val memberAddresses = members?.mapNotNull { it.address }?.distinct().orEmpty()
        if (memberAddresses.isEmpty()) {
            pendingSnackbarMessage = "No members in room"
            return
        }

        val roomMessageId = UUID.randomUUID().toString()
        val payload = encodeRoomPayload(roomId, roomMessageId, null, trimmed)

        var shouldStartKeyExchange = false

        // Create one pending outgoing message per member.
        for (addr in memberAddresses) {
            val normalized = addr.trim().lowercase()
            if (normalized.isBlank() || normalized == myId) continue

            Realm.getDefaultInstance().use { r ->
                r.executeTransaction { tr ->
                    var c = tr.where(Contact::class.java).equalTo("address", normalized).findFirst()
                    if (c == null) {
                        val nextId = (tr.where(Contact::class.java).max("_id")?.toLong() ?: 0L) + 1L
                        c = tr.createObject(Contact::class.java, nextId)
                        c.setName("")
                        c.setDescription("")
                        c.setAddress(normalized)
                        c.setOutgoing(1)
                        c.setIncoming(0)
                        c.setPubKey(null)
                        shouldStartKeyExchange = true
                    } else if (c.getPubKey() == null || c.getPubKey().isEmpty()) {
                        c.setOutgoing(1)
                        shouldStartKeyExchange = true
                    }
                }
            }

            // Always queue the payload; the send will happen once key exchange completes.
            Message.addPendingOutgoingMessage(myId, normalized, payload, null, null, null)
            Client.getInstance(this).startSendPendingMessages(normalized)
        }

        if (shouldStartKeyExchange) {
            Client.getInstance(this).startSendPendingFriends()
        }

        messagesVersion.intValue++
    }

    private fun announceJoinToMembers() {
        val myId = tor.getID() ?: return
        val alias = Database.getInstance(this).name?.trim().orEmpty()

        val memberAddresses = members?.mapNotNull { it.address }?.distinct().orEmpty()
        if (memberAddresses.isEmpty()) return

        val roomMessageId = UUID.randomUUID().toString()
        val payload = encodeRoomPayload(roomId, roomMessageId, "JOIN", alias)

        var shouldStartKeyExchange = false

        for (addr in memberAddresses) {
            val normalized = addr.trim().lowercase()
            if (normalized.isBlank() || normalized == myId) continue

            Realm.getDefaultInstance().use { r ->
                r.executeTransaction { tr ->
                    var c = tr.where(Contact::class.java).equalTo("address", normalized).findFirst()
                    if (c == null) {
                        val nextId = (tr.where(Contact::class.java).max("_id")?.toLong() ?: 0L) + 1L
                        c = tr.createObject(Contact::class.java, nextId)
                        c.setName("")
                        c.setDescription("")
                        c.setAddress(normalized)
                        c.setOutgoing(1)
                        c.setIncoming(0)
                        c.setPubKey(null)
                        shouldStartKeyExchange = true
                    } else if (c.getPubKey() == null || c.getPubKey().isEmpty()) {
                        c.setOutgoing(1)
                        shouldStartKeyExchange = true
                    }
                }
            }

            Message.addPendingOutgoingMessage(myId, normalized, payload, null, null, null)
            Client.getInstance(this).startSendPendingMessages(normalized)
        }

        if (shouldStartKeyExchange) {
            Client.getInstance(this).startSendPendingFriends()
        }
    }

    private fun roomContentPrefix(roomId: String): String = "roommsg:$roomId:"

    private fun encodeRoomPayload(roomId: String, roomMessageId: String, systemType: String?, text: String): String {
        val typeToken = systemType?.takeIf { it.isNotBlank() } ?: "-"
        val encoded = android.util.Base64.encodeToString(text.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        return "roommsg:$roomId:$roomMessageId:$typeToken:$encoded"
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun RoomScreen(
    roomId: String,
    roomName: String?,
    myId: String,
    myAlias: String?,
    members: RealmResults<ChatRoomMember>?,
    messages: RealmResults<Message>?,
    messagesVersion: Int,
    pendingSnackbarMessage: String?,
    onClearPendingSnackbar: () -> Unit,
    onBack: () -> Unit,
    onShowInvite: () -> Unit,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onPickFile: () -> Unit,
    onSendText: (String) -> Unit,
    onDownloadMessage: (Message) -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(pendingSnackbarMessage) {
        val msg = pendingSnackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        onClearPendingSnackbar()
    }

    var input by remember { mutableStateOf("") }

    val listState = rememberLazyListState()

    val rawList = remember(messagesVersion) { messages?.toList() ?: emptyList() }
    val deduped = remember(messagesVersion) {
        val seen = HashSet<String>()
        rawList.filter { m ->
            val k = extractRoomMessageIdFromContent(m.content) ?: m.primaryKey
            if (k == null) true else seen.add(k)
        }
            .filter { m ->
                val t = extractRoomSystemTypeFromContent(m.content)
                // System messages are transport/state-level; never show them.
                t != "ACK" && t != "JOIN"
            }
    }

    val otherMemberAddresses = remember(messagesVersion) {
        (members?.mapNotNull { it.address } ?: emptyList())
            .filter { it.isNotBlank() && it != myId }
            .distinct()
            .sorted()
    }

    val memberAliasByAddress = remember(messagesVersion) {
        (members?.toList() ?: emptyList())
            .mapNotNull { m ->
                val addr = m.address?.trim()
                if (addr.isNullOrBlank()) null else addr to (m.alias?.trim().orEmpty())
            }
            .toMap()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = (roomName?.takeIf { it.isNotBlank() } ?: "ROOM"), fontWeight = FontWeight.SemiBold)
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("<") }
                },
                actions = {
                    IconButton(onClick = onShowInvite) {
                        Text("QR", color = MaterialTheme.colorScheme.primary)
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
                .imePadding()
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                state = listState
            ) {
                items(deduped) { message ->
                    val isMine = message.sender == myId
                    val lineColor = if (isMine) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.primary
                    }

                    val alias = when {
                        isMine -> myAlias?.trim()?.takeIf { it.isNotBlank() } ?: "Anonymous"
                        else -> {
                            val sender = message.sender ?: ""
                            val roomAlias = memberAliasByAddress[sender]?.takeIf { it.isNotBlank() }
                            if (roomAlias != null) {
                                roomAlias
                            } else {
                            val contactName = Realm.getDefaultInstance().use { r ->
                                r.where(Contact::class.java).equalTo("address", sender).findFirst()?.name
                            }
                            contactName?.takeIf { it.isNotBlank() } ?: sender.take(8)
                            }
                        }
                    }

                    val isAttachment = message.fileShare != null
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        val displayText = extractRoomTextFromContent(roomId, message.content) ?: (message.content ?: "")
                        Text(text = "$alias: $displayText", color = lineColor)

                        if (isAttachment && !isMine && (message.fileShare?.isDownloaded != true)) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Button(
                                onClick = { onDownloadMessage(message) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                )
                            ) { Text("DOWNLOAD") }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                var mediaMenuOpen by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { mediaMenuOpen = true }) {
                        Text("+", color = MaterialTheme.colorScheme.primary)
                    }
                    DropdownMenu(expanded = mediaMenuOpen, onDismissRequest = { mediaMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("IMG", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                mediaMenuOpen = false
                                onPickImage()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("VID", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                mediaMenuOpen = false
                                onPickVideo()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("FILE", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                mediaMenuOpen = false
                                onPickFile()
                            }
                        )
                    }
                }
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val toSend = input
                        input = ""
                        onSendText(toSend)
                        scope.launch {
                            listState.animateScrollToItem((deduped.size - 1).coerceAtLeast(0))
                        }
                    }
                ) {
                    Text("SEND")
                }
            }
        }
    }
}

private fun extractRoomMessageIdFromContent(content: String?): String? {
    if (content == null) return null
    // roommsg:<roomId>:<roomMessageId>:<type>:<base64>
    val tokens = content.split(":", limit = 5)
    if (tokens.size != 5) return null
    if (tokens[0] != "roommsg") return null
    return tokens[2].takeIf { it.isNotBlank() }
}

private fun extractRoomTextFromContent(roomId: String, content: String?): String? {
    if (content == null) return null
    if (!content.startsWith("roommsg:$roomId:")) return null
    val tokens = content.split(":", limit = 5)
    if (tokens.size != 5) return null
    val encoded = tokens[4]
    return try {
        val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
        String(bytes, Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }
}

private fun extractRoomSystemTypeFromContent(content: String?): String? {
    if (content == null) return null
    val tokens = content.split(":", limit = 5)
    if (tokens.size != 5) return null
    if (tokens[0] != "roommsg") return null
    val t = tokens[3].takeIf { it.isNotBlank() } ?: return null
    return if (t == "-") null else t
}
