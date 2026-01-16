package com.ivor.kriptex

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.media.ThumbnailUtils
import android.util.Log
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import com.ivor.kriptex.db.ChatRoom
import com.ivor.kriptex.db.ChatRoomMember
import com.ivor.kriptex.db.Contact
import com.ivor.kriptex.db.Database
import com.ivor.kriptex.db.Message
import com.ivor.kriptex.utils.Util
import com.ivor.kriptex.utils.VisibleChatTracker
import com.ivor.kriptex.service.KriptexHostService
import com.ivor.kriptex.tor.Client
import com.ivor.kriptex.tor.Notifier
import com.ivor.kriptex.tor.Server
import com.ivor.kriptex.tor.Tor
import com.ivor.kriptex.ui.compose.KriptexTheme
import io.realm.Realm
import io.realm.RealmChangeListener
import io.realm.RealmResults
import io.realm.Sort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
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
    private val membersVersion: MutableIntState = mutableIntStateOf(0)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var markRoomReadPosted: Boolean = false
    private var markRoomReadInProgress: Boolean = false

    private val messagesListener = RealmChangeListener<RealmResults<Message>> {
        messagesVersion.intValue++
        if (isResumed) {
            scheduleMarkRoomRead()
        }
    }

    private var lastJoinAnnounceElapsedMs: Long = 0L

    private val membersListener = RealmChangeListener<RealmResults<ChatRoomMember>> {
        membersVersion.intValue++
        // Members are often discovered/created asynchronously (e.g., upon receiving a JOIN).
        // Re-announce once we learn about members so they get our alias too.
        maybeAnnounceJoinToMembers()
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
        members!!.addChangeListener(membersListener)

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
                    membersVersion = membersVersion.intValue,
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
        mainHandler.removeCallbacksAndMessages(null)
        messages?.removeAllChangeListeners()
        members?.removeAllChangeListeners()
        realm?.close()
        realm = null
        messagesRealm?.close()
        messagesRealm = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        scheduleMarkRoomRead()
    }

    override fun onPause() {
        isResumed = false
        // Persist last-read when leaving the room.
        scheduleMarkRoomRead(force = true)
        super.onPause()
    }

    private fun scheduleMarkRoomRead(force: Boolean = false) {
        if (!force && !isResumed) return
        if (markRoomReadPosted) return

        markRoomReadPosted = true
        mainHandler.post {
            markRoomReadPosted = false
            markRoomRead()
        }
    }

    private fun markRoomRead() {
        val r = realm ?: return
        val rid = roomId.trim()
        if (rid.isBlank()) return

        // Realm change listeners can be fired while a transaction is starting.
        // Avoid re-entrant writes which crash with: "The Realm is already in a write transaction".
        if (markRoomReadInProgress) return
        try {
            if (r.isInTransaction) return
        } catch (_: IllegalStateException) {
            // Realm may be closed/detached during teardown.
            return
        }

        val prefix = roomContentPrefix(rid)

        markRoomReadInProgress = true
        try {
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
        } catch (_: IllegalStateException) {
            // Ignore transient "already in a write transaction" edge cases.
        } finally {
            markRoomReadInProgress = false
        }
    }

    private fun maybeAnnounceJoinToMembers() {
        val now = android.os.SystemClock.elapsedRealtime()
        // Realm can deliver multiple callbacks in quick succession; keep JOIN traffic minimal.
        if (now - lastJoinAnnounceElapsedMs < 1_500L) return
        lastJoinAnnounceElapsedMs = now
        announceJoinToMembers()
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
            val normalized = addr.trim().lowercase(Locale.US)
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
            val normalized = addr.trim().lowercase(Locale.US)
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
            val normalized = addr.trim().lowercase(Locale.US)
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
    membersVersion: Int,
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
        val debugLoggedFallbackKeys = remember { HashSet<String>() }
        val debugTag = "ChatRoomUI"
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

    val otherMemberAddresses = remember(membersVersion) {
        val my = normalizeOnionId(myId)
            (members?.mapNotNull {
                val address = normalizeOnionId(it.address)
                if (address.isNotBlank()) return@mapNotNull address
                val pk = it.primaryKey?.trim().orEmpty()
                val prefix = roomId + ":"
                if (pk.startsWith(prefix) && pk.length > prefix.length) normalizeOnionId(pk.substring(prefix.length)) else null
            } ?: emptyList())
            .filter { it != my }
            .distinct()
            .sorted()
    }

    val memberAliasByAddress = remember(membersVersion) {
        (members?.toList() ?: emptyList())
            .mapNotNull { m ->
                    val addr = normalizeOnionId(m.address).ifBlank {
                        val pk = m.primaryKey?.trim().orEmpty()
                        val prefix = roomId + ":"
                        if (pk.startsWith(prefix) && pk.length > prefix.length) normalizeOnionId(pk.substring(prefix.length)) else ""
                    }
                if (addr.isBlank()) null else addr to (m.alias?.trim().orEmpty())
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
        val view = LocalView.current
        var imeBottom by remember { mutableIntStateOf(0) }

        DisposableEffect(view) {
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
                imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                insets
            }
            onDispose {
                ViewCompat.setOnApplyWindowInsetsListener(view, null)
            }
        }

        val isAtBottom by remember {
            derivedStateOf {
                val total = listState.layoutInfo.totalItemsCount
                if (total <= 0) return@derivedStateOf true
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible >= total - 1
            }
        }

        LaunchedEffect(imeBottom, deduped.size) {
            if (imeBottom > 0 && isAtBottom) {
                listState.scrollToItem((deduped.size - 1).coerceAtLeast(0))
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                state = listState
            ) {
                items(deduped) { message ->
                    val isMine = normalizeOnionId(message.sender) == normalizeOnionId(myId)
                    val lineColor = if (isMine) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.primary
                    }


    fun lookupMemberAlias(roomId: String, senderRaw: String, senderNorm: String): String? {
        if (senderNorm.isBlank()) return null
        return Realm.getDefaultInstance().use { r ->
            val pkNorm = ChatRoomMember.makePrimaryKey(roomId, senderNorm)
            val pkRaw = ChatRoomMember.makePrimaryKey(roomId, senderRaw.trim())
            val member = r.where(ChatRoomMember::class.java)
                .beginGroup()
                .equalTo("primaryKey", pkNorm)
                .or()
                .equalTo("primaryKey", pkRaw)
                .endGroup()
                .findFirst()

            val alias = member?.alias?.trim().orEmpty()
            if (BuildConfig.DEBUG && member != null && alias.isBlank()) {
                val pk = member.primaryKey ?: ""
                val addr = member.address ?: ""
                Log.w(
                    debugTag,
                    "member found but alias blank: roomId=$roomId senderRaw='${senderRaw}' senderNorm='${senderNorm}' pk='${pk}' addr='${addr}'"
                )
            }

            alias.takeIf { it.isNotBlank() }
        }
    }

                    val alias = when {
                        isMine -> myAlias?.trim()?.takeIf { it.isNotBlank() } ?: "Anonymous"
                        else -> {
                            val senderRaw = message.sender ?: ""
                            val sender = normalizeOnionId(senderRaw)
                            val roomAliasRaw = memberAliasByAddress[sender]
                            val roomAlias = roomAliasRaw
                                ?.let { normalizeAliasToken(it) }
                                ?.takeIf { it.isNotBlank() && !looksLikeOnionPlaceholder(it, sender) && !looksLikeKeyMaterialOrEncryptedName(it) }
                            if (roomAlias != null) {
                                roomAlias
                            } else {
                                val memberAlias = lookupMemberAlias(roomId, senderRaw, sender)
                                    ?.let { normalizeAliasToken(it) }
                                    ?.takeIf { it.isNotBlank() && !looksLikeOnionPlaceholder(it, sender) && !looksLikeKeyMaterialOrEncryptedName(it) }
                                if (memberAlias != null) {
                                    memberAlias
                                } else {
                                val contactName = Realm.getDefaultInstance().use { r ->
                                    val senderLookupA = sender
                                    val senderLookupB = senderRaw.trim().lowercase(Locale.US)
                                    r.where(Contact::class.java)
                                        .beginGroup()
                                        .equalTo("address", senderLookupA)
                                        .or()
                                        .equalTo("address", senderLookupB)
                                        .endGroup()
                                        .findFirst()
                                        ?.name
                                }
                                val contactResolved = contactName
                                    ?.let { normalizeAliasToken(it) }
                                    ?.takeIf { it.isNotBlank() && !looksLikeOnionPlaceholder(it, sender) && !looksLikeKeyMaterialOrEncryptedName(it) }

                                val resolved = contactResolved ?: sender.take(8)
                                if (BuildConfig.DEBUG) {
                                    val key = roomId + ":" + sender
                                    if (debugLoggedFallbackKeys.add(key)) {
                                        Log.w(
                                            debugTag,
                                            "name fallback: roomId=$roomId senderRaw='${senderRaw}' senderNorm='${sender}' " +
                                                "membersSize=${members?.size ?: -1} membersVer=$membersVersion " +
                                                "mapHit=${memberAliasByAddress.containsKey(sender)} mapAliasBlank=${roomAliasRaw.isNullOrBlank()} " +
                                                "contactHit=${!contactName.isNullOrBlank()} contactIgnored=${contactName != null && contactResolved == null}"
                                        )
                                    }
                                }
                                resolved
                                }
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
                    .imePadding()
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

                        if (BuildConfig.DEBUG) {
                            DropdownMenuItem(
                                text = { Text("DEBUG: Dump aliases") },
                                onClick = {
                                    mediaMenuOpen = false
                                    scope.launch {
                                        val report = withContext(Dispatchers.IO) {
                                            debugDumpRoomAliases(roomId)
                                        }
                                        Log.i(debugTag, report)
                                        snackbarHostState.showSnackbar("Dumped aliases to logcat")
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("DEBUG: Self-heal aliases") },
                                onClick = {
                                    mediaMenuOpen = false
                                    scope.launch {
                                        val report = withContext(Dispatchers.IO) {
                                            debugHealRoomAliases(roomId)
                                        }
                                        Log.w(debugTag, report)
                                        snackbarHostState.showSnackbar("Self-heal done (see logcat)")
                                    }
                                }
                            )
                        }
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

private fun normalizeOnionId(value: String?): String {
    val s = value?.trim()?.lowercase(Locale.US).orEmpty()
    return if (s.endsWith(".onion")) s.dropLast(".onion".length) else s
}

private fun isBase32Like(value: String): Boolean {
    if (value.isEmpty()) return false
    for (c in value) {
        val ok = (c in 'a'..'z') || (c in '2'..'7')
        if (!ok) return false
    }
    return true
}

private fun looksLikeOnionPlaceholder(existingName: String?, id: String): Boolean {
    val n = normalizeOnionId(existingName)
    val onion = normalizeOnionId(id)
    if (n.isBlank() || onion.isBlank()) return false
    if (n == onion) return true

    val prefixes = intArrayOf(8, 16)
    for (len in prefixes) {
        if (onion.length >= len && n == onion.substring(0, len)) return true
    }

    return n.length >= 8 && n.length <= onion.length && isBase32Like(n) && onion.startsWith(n)
}

private fun looksLikeHexToken(value: String): Boolean {
    val s = value.trim()
    if (s.length < 16) return false
    for (c in s) {
        val ok = (c in '0'..'9') || (c in 'a'..'f') || (c in 'A'..'F')
        if (!ok) return false
    }
    return true
}

private fun looksLikeBase64Token(value: String): Boolean {
    val s = value.trim()
    if (s.length < 8 || s.length > 256) return false
    if (s.length % 4 != 0) return false
    for (c in s) {
        val ok = (c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') || c == '+' || c == '/' || c == '=' || c == '-' || c == '_'
        if (!ok) return false
    }
    return true
}

private fun decodeBase64IfPrintableShort(token: String): String? {
    if (!looksLikeBase64Token(token)) return null
    return try {
        val bytes = android.util.Base64.decode(token, android.util.Base64.DEFAULT)
        if (bytes.isEmpty() || bytes.size > 64) return null
        val decoded = bytes.toString(Charsets.UTF_8).trim()
        if (decoded.isEmpty() || decoded.length > 32) return null

        var hasLetter = false
        for (c in decoded) {
            if (c.code < 0x20 && c != '\n' && c != '\r' && c != '\t') return null
            if (c.isLetter()) hasLetter = true
        }
        if (!hasLetter) return null
        if (looksLikeBase64Token(decoded) || looksLikeHexToken(decoded)) return null
        decoded
    } catch (_: Exception) {
        null
    }
}

private fun looksLikeKeyMaterialOrEncryptedName(value: String?): Boolean {
    val s = value?.trim().orEmpty()
    if (s.isBlank()) return false
    if (s.length >= 40 && looksLikeBase64Token(s)) return true
    if (s.length >= 40 && looksLikeHexToken(s)) return true
    return s.startsWith("AL3") && s.length >= 40 && looksLikeBase64Token(s)
}

private fun looksLikeHumanAlias(value: String?): Boolean {
    val s = value?.trim().orEmpty()
    if (s.isBlank() || s.length > 32) return false
    if (looksLikeBase64Token(s) || looksLikeHexToken(s)) return false
    return s.any { it.isLetter() }
}

private fun normalizeAliasToken(token: String?): String {
    val t = token?.trim().orEmpty()
    return decodeBase64IfPrintableShort(t) ?: t
}

private fun debugDumpRoomAliases(roomId: String): String {
    val sb = StringBuilder()
    Realm.getDefaultInstance().use { r ->
        val members = r.where(ChatRoomMember::class.java)
            .equalTo("roomId", roomId)
            .findAll()
            .toList()
            .filterNotNull()

        sb.append("Room alias dump: roomId=").append(roomId)
            .append(" members=").append(members.size)
            .append('\n')

        for (m in members) {
            val pk = m.primaryKey?.trim().orEmpty()
            val addr = normalizeOnionId(m.address)
            val aliasRaw = m.alias?.trim().orEmpty()
            val aliasNorm = normalizeAliasToken(aliasRaw)
            val enc = looksLikeKeyMaterialOrEncryptedName(aliasNorm)
            sb.append("- member pk='").append(pk)
                .append("' addr='").append(addr)
                .append("' aliasRaw='").append(aliasRaw)
                .append("' aliasNorm='").append(aliasNorm)
                .append("' encrypted=").append(enc)
                .append('\n')

            val c = r.where(Contact::class.java)
                .beginGroup()
                .equalTo("address", addr)
                .or()
                .equalTo("address", addr.lowercase(Locale.US))
                .endGroup()
                .findFirst()
            if (c != null) {
                val cnRaw = c.name?.trim().orEmpty()
                val cn = normalizeAliasToken(cnRaw)
                sb.append("  contact nameRaw='").append(cnRaw)
                    .append("' nameNorm='").append(cn)
                    .append("' encrypted=").append(looksLikeKeyMaterialOrEncryptedName(cn))
                    .append('\n')
            }
        }
    }
    return sb.toString()
}

private fun debugHealRoomAliases(roomId: String): String {
    var created = 0
    var deleted = 0
    var memberAliasUpdates = 0
    var contactNameUpdates = 0

    Realm.getDefaultInstance().use { r ->
        val members = r.where(ChatRoomMember::class.java)
            .equalTo("roomId", roomId)
            .findAll()
            .toList()
            .filterNotNull()

        // Group by normalized sender address (derived from address or PK).
        val groups = members.groupBy { m ->
            val addr = normalizeOnionId(m.address)
            if (addr.isNotBlank()) return@groupBy addr
            val pk = m.primaryKey?.trim().orEmpty()
            val prefix = "$roomId:"
            if (pk.startsWith(prefix) && pk.length > prefix.length) normalizeOnionId(pk.substring(prefix.length)) else ""
        }.filterKeys { it.isNotBlank() }

        r.executeTransaction { tr ->
            for ((senderNorm, rows) in groups) {
                val canonicalPk = ChatRoomMember.makePrimaryKey(roomId, senderNorm)
                var canonical = tr.where(ChatRoomMember::class.java)
                    .equalTo("primaryKey", canonicalPk)
                    .findFirst()
                if (canonical == null) {
                    canonical = tr.createObject(ChatRoomMember::class.java, canonicalPk)
                    canonical!!.roomId = roomId
                    canonical!!.address = senderNorm
                    canonical!!.alias = ""
                    created++
                } else {
                    if (canonical!!.roomId.isNullOrBlank()) canonical!!.roomId = roomId
                    if (normalizeOnionId(canonical!!.address) != senderNorm) canonical!!.address = senderNorm
                }

                val canonicalMember = canonical!!

                fun isBadName(s: String): Boolean {
                    val t = s.trim()
                    return t.isBlank() || looksLikeOnionPlaceholder(t, senderNorm) || looksLikeKeyMaterialOrEncryptedName(t)
                }

                var bestAlias = normalizeAliasToken(canonicalMember.alias)
                if (isBadName(bestAlias)) bestAlias = ""

                for (m in rows.filterNotNull()) {
                    val candidate = normalizeAliasToken(m.alias)
                    if (!isBadName(candidate) && looksLikeHumanAlias(candidate)) {
                        bestAlias = candidate
                        break
                    }
                }

                if (bestAlias.isNotBlank() && bestAlias != canonicalMember.alias) {
                    canonicalMember.alias = bestAlias
                    memberAliasUpdates++
                }

                for (m in rows.filterNotNull()) {
                    val pk = m.primaryKey?.trim().orEmpty()
                    if (pk.isNotBlank() && pk != canonicalPk) {
                        m.deleteFromRealm()
                        deleted++
                    }
                }

                if (bestAlias.isNotBlank() && looksLikeHumanAlias(bestAlias)) {
                    val c = tr.where(Contact::class.java)
                        .beginGroup()
                        .equalTo("address", senderNorm)
                        .or()
                        .equalTo("address", senderNorm.lowercase(Locale.US))
                        .endGroup()
                        .findFirst()
                    if (c != null) {
                        val existing = normalizeAliasToken(c.name)
                        val existingBad = existing.isBlank() || looksLikeOnionPlaceholder(existing, senderNorm) || looksLikeKeyMaterialOrEncryptedName(existing)
                        if (existingBad) {
                            c.name = bestAlias
                            contactNameUpdates++
                        }
                    }
                }
            }
        }
    }

    return "Self-heal aliases: roomId=$roomId created=$created deleted=$deleted memberAliasUpdates=$memberAliasUpdates contactNameUpdates=$contactNameUpdates"
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
