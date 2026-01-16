package com.ivor.kriptex

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import com.ivor.kriptex.db.ChatRoom
import com.ivor.kriptex.db.ChatRoomMember
import com.ivor.kriptex.db.Contact
import com.ivor.kriptex.db.Database
import com.ivor.kriptex.db.Message
import com.ivor.kriptex.service.KriptexHostService
import com.ivor.kriptex.tor.Client
import com.ivor.kriptex.tor.Server
import com.ivor.kriptex.tor.Tor
import com.ivor.kriptex.ui.DeleteContact
import com.ivor.kriptex.ui.compose.KriptexTheme
import com.ivor.kriptex.utils.TimeAgo
import io.realm.Case
import io.realm.Realm
import io.realm.RealmChangeListener
import io.realm.RealmResults
import io.realm.Sort
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.foundation.ExperimentalFoundationApi

class MainComposeActivity : ComponentActivity() {

    private lateinit var tor: Tor

    private var realm: Realm? = null
    private var rooms: RealmResults<ChatRoom>? = null

    private val contactsVersion: MutableIntState = mutableIntStateOf(0)

    private val contactsListener = RealmChangeListener<RealmResults<ChatRoom>> {
        contactsVersion.intValue++
    }

    private val serverListener = Server.Listener {
        // Used by legacy MainActivity to refresh title/subtitle.
        // Here we just force recomposition by bumping version state.
        contactsVersion.intValue++
    }

    private val torListener = Tor.Listener {
        // Tor ID and status can change while bootstrapping.
        contactsVersion.intValue++
    }

    private val torLogListener = Tor.LogListener {
        contactsVersion.intValue++
    }

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchEmbeddedQrScanner()
        } else {
            // Compose UI will show snackbar through state.
            pendingSnackbarMessage = "Camera permission denied"
        }
    }

    private val qrScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val scanResult = IntentIntegrator.parseActivityResult(IntentIntegrator.REQUEST_CODE, result.resultCode, result.data)
        if (scanResult != null) {
            val contents = scanResult.contents
            if (contents != null) {
                handleQrPayload(contents)
            }
        }
    }

    // One-way bridge for event -> snackbar.
    private var pendingSnackbarMessage: String? by mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tor = Tor.getInstance(this)
        ContextCompat.startForegroundService(this, Intent(this, KriptexHostService::class.java))

        realm = Realm.getDefaultInstance()
        rooms = realm!!.where(ChatRoom::class.java)
            .findAll()
            .sort("createdAt", Sort.DESCENDING)
        rooms!!.addChangeListener(contactsListener)

        checkBatteryOptimization()

        setContent {
            KriptexTheme {
                MainScreen(
                    activity = this,
                    contactsVersion = contactsVersion.intValue,
                    rooms = rooms,
                    onClearPendingSnackbar = { pendingSnackbarMessage = null },
                    pendingSnackbarMessage = pendingSnackbarMessage,
                    onOpenSettings = { startActivity(Intent(this, SettingsComposeActivity::class.java)) },
                    onOpenRoom = { roomId -> openRoom(roomId) },
                    onJoinRoom = { scanQr() },
                    onCreateRoom = { createRoom() },
                    torReady = tor.isReady(),
                    torStatus = torStatusText(),
                    myId = tor.getID(),
                    myAlias = Database.getInstance(this).name
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Tor.getInstance(this).addListener(torListener)
        Tor.getInstance(this).addLogListener(torLogListener)
        Server.getInstance(this).addListener(serverListener)

        sendPending()
        ContextCompat.startForegroundService(this, Intent(this, KriptexHostService::class.java))

        contactsVersion.intValue++
    }

    override fun onPause() {
        Tor.getInstance(this).removeListener(torListener)
        Tor.getInstance(this).removeLogListener(torLogListener)
        Server.getInstance(this).removeListener(serverListener)
        super.onPause()
    }

    override fun onDestroy() {
        rooms?.removeAllChangeListeners()
        realm?.close()
        super.onDestroy()
    }

    private fun sendPending() {
        Client.getInstance(this).startSendPendingFriends()
    }

    private fun torStatusText(): String {
        val raw = tor.status ?: ""
        var status = raw
        val i = status.indexOf(']')
        if (i >= 0) status = status.substring(i + 1)
        status = status.trim()
        val prefix = "Bootstrapped"
        return if (status.contains("%") && status.length > prefix.length && status.startsWith(prefix)) {
            status.substring(prefix.length).trim()
        } else {
            status
        }
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val packageName = packageName
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                try {
                    startActivity(intent)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun scanQr() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        launchEmbeddedQrScanner()
    }

    private fun launchEmbeddedQrScanner() {
        val integrator = IntentIntegrator(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setPrompt(getString(R.string.scan_qr))
        integrator.setBeepEnabled(false)
        integrator.setBarcodeImageEnabled(false)
        integrator.setOrientationLocked(false)
        val intent = integrator.createScanIntent()
        qrScanLauncher.launch(intent)
    }

    private fun handleQrPayload(payload: String?) {
        try {
            if (payload.isNullOrBlank()) {
                pendingSnackbarMessage = getString(R.string.invalid_qr_code)
                return
            }

            val invite = parseKriptexRoomInvite(payload)
            if (invite == null) {
                pendingSnackbarMessage = "Scan a room invite QR"
                return
            }

            joinRoom(invite)
        } catch (_: Exception) {
            pendingSnackbarMessage = getString(R.string.invalid_qr_code)
        }
    }

    private data class ParsedQr(val id: String, val name: String)

    private data class ParsedRoomInvite(val roomId: String, val inviterId: String, val roomName: String)

    private fun parseKriptexRoomInvite(raw: String?): ParsedRoomInvite? {
        if (raw == null) return null

        val normalized = raw.trim()
        if (normalized.isEmpty()) return null

        val tokens = normalized.split(Regex("\\s+"), limit = 4)
        if (tokens.size >= 3 && tokens[0].equals("KriptexRoom", ignoreCase = true)) {
            val roomId = tokens[1].trim()
            var inviterId = tokens[2].trim().lowercase(Locale.US)
            if (inviterId.endsWith(".onion")) inviterId = inviterId.substring(0, inviterId.length - ".onion".length)
            val roomName = if (tokens.size >= 4) tokens[3].trim() else ""
            if (roomId.isNotBlank() && inviterId.isNotBlank()) {
                return ParsedRoomInvite(roomId, inviterId, roomName)
            }
        }
        return null
    }

    private fun parseKriptexQr(raw: String?): ParsedQr? {
        if (raw == null) return null

        var normalized = raw.trim()
        if (normalized.isEmpty()) return null
        normalized = normalized.replace('\uFEFF', ' ')

        val lowered = normalized.lowercase(Locale.US)

        // Primary expected format: "Kriptex <id> <name...>"
        val tokens = normalized.trim().split(Regex("\\s+"), limit = 3)
        if (tokens.size >= 2) {
            val app = tokens[0].trim()
            if (app.equals("Kriptex", ignoreCase = true)) {
                var id = tokens[1].trim().lowercase(Locale.US)
                if (id.endsWith(".onion")) id = id.substring(0, id.length - ".onion".length)
                id = id.replace(Regex("[^a-z2-7]"), "")
                if (id.length == 56 || id.length == 16) {
                    val name = if (tokens.size >= 3) tokens[2].trim() else ""
                    return ParsedQr(id, name)
                }
            }
        }

        // Fallback: find an onion id anywhere in the payload (prefer v3).
        val v3 = Pattern.compile("([a-z2-7]{56})").matcher(lowered)
        if (v3.find()) {
            val id = v3.group(1) ?: return null
            return ParsedQr(id, "")
        }

        val v2 = Pattern.compile("([a-z2-7]{16})").matcher(lowered)
        if (v2.find()) {
            val id = v2.group(1) ?: return null
            return ParsedQr(id, "")
        }

        return null
    }

    private fun addContact(id: String, alias: String, description: String) {
        var id1 = id.trim().lowercase(Locale.US)
        if (id1.endsWith(".onion")) id1 = id1.substring(0, id1.length - ".onion".length)
        id1 = id1.replace(Regex("[^a-z2-7]"), "")

        if (id1.length != 56) {
            pendingSnackbarMessage = getString(R.string.invalid_id)
            return
        }
        if (id1 == tor.getID()) {
            pendingSnackbarMessage = getString(R.string.cannot_add_self)
            return
        }

        val ok = Contact.addContact(
            this,
            id1,
            alias.trim(),
            description.trim(),
            null,
            true,
            false
        )

        if (!ok) {
            pendingSnackbarMessage = getString(R.string.contact_already_present)
            return
        }

        pendingSnackbarMessage = getString(R.string.contact_added)
        sendPending()
        contactsVersion.intValue++
    }

    private fun joinRoom(invite: ParsedRoomInvite) {
        val myId = tor.getID() ?: ""
        if (myId.isBlank()) {
            pendingSnackbarMessage = "Tor not ready"
            return
        }

        fun normalizeOnionId(value: String?): String {
            val raw = (value ?: "").trim().lowercase(Locale.US)
            return if (raw.endsWith(".onion")) raw.removeSuffix(".onion") else raw
        }

        val inviterNorm = normalizeOnionId(invite.inviterId)

        // Ensure we have the inviter as a contact so encryption works.
        if (!Contact.hasContact(this, inviterNorm)) {
            // Don't name the inviter contact as the room name.
            // We'll learn/display the inviter's alias via friend exchange or room membership.
            addContact(inviterNorm, "", "")
        }

        val realm = Realm.getDefaultInstance()
        try {
            realm.executeTransaction { tx ->
                val existingRoom = tx.where(ChatRoom::class.java).equalTo("id", invite.roomId).findFirst()
                if (existingRoom == null) {
                    val r = tx.createObject(ChatRoom::class.java, invite.roomId)
                    r.name = invite.roomName
                    r.createdAt = System.currentTimeMillis()
                }

                val inviterRawLower = invite.inviterId.trim().lowercase(Locale.US)
                val memberPkNorm = ChatRoomMember.makePrimaryKey(invite.roomId, inviterNorm)
                val memberPkRaw = ChatRoomMember.makePrimaryKey(invite.roomId, inviterRawLower)
                val existingMember = tx.where(ChatRoomMember::class.java)
                    .beginGroup()
                    .equalTo("primaryKey", memberPkNorm)
                    .or()
                    .equalTo("primaryKey", memberPkRaw)
                    .endGroup()
                    .findFirst()

                if (existingMember == null) {
                    val m = tx.createObject(ChatRoomMember::class.java, memberPkNorm)
                    m.roomId = invite.roomId
                    m.address = inviterNorm
                    m.alias = ""
                } else {
                    if (existingMember.roomId.isNullOrBlank()) {
                        existingMember.roomId = invite.roomId
                    }
                    val currentAddr = existingMember.address ?: ""
                    if (normalizeOnionId(currentAddr) != inviterNorm) {
                        existingMember.address = inviterNorm
                    }
                }
            }
        } finally {
            realm.close()
        }

        // Send a JOIN system message to the inviter (will be delivered once pubkey exchange completes).
        // Payload carries our alias (not the room name) so it never shows as a weird "reply".
        val roomMessageId = UUID.randomUUID().toString()
        val myAlias = Database.getInstance(this).name?.trim().orEmpty()
        val joinPayload = encodeRoomPayload(invite.roomId, roomMessageId, "JOIN", myAlias)
        Message.addPendingOutgoingMessage(myId, invite.inviterId, joinPayload, null, null, null)
        Client.getInstance(this).startSendPendingMessages(invite.inviterId)

        openRoom(invite.roomId)
    }

    private fun createRoom() {
        val newRoomId = UUID.randomUUID().toString()
        val generatedName = generateRoomNameFromNamebase(newRoomId)
        val realm = Realm.getDefaultInstance()
        try {
            realm.executeTransaction { tx ->
                val r = tx.createObject(ChatRoom::class.java, newRoomId)
                r.name = generatedName
                r.createdAt = System.currentTimeMillis()
            }
        } finally {
            realm.close()
        }
        contactsVersion.intValue++
        openRoom(newRoomId)
    }

    private fun generateRoomNameFromNamebase(roomId: String): String {
        val shortId = roomId.take(8)

        val lines = try {
            assets.open("namebase.txt").bufferedReader().use { it.readLines() }
        } catch (_: Exception) {
            emptyList()
        }

        if (lines.size < 2) return "room_$shortId"

        fun parseCsv(line: String): List<String> = line
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val a1 = parseCsv(lines[0])
        val a2 = parseCsv(lines[1])
        if (a1.isEmpty() || a2.isEmpty()) return "room_$shortId"

        val w1 = a1.random()
        val w2 = a2.random()
        return "${w1}_${w2}-$shortId"
    }

    private fun encodeRoomPayload(roomId: String, roomMessageId: String, systemType: String?, text: String): String {
        val typeToken = systemType?.takeIf { it.isNotBlank() } ?: "-"
        val encoded = android.util.Base64.encodeToString(text.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        return "roommsg:$roomId:$roomMessageId:$typeToken:$encoded"
    }

    private fun openRoom(roomId: String) {
        startActivity(
            Intent(this, ChatRoomComposeActivity::class.java)
                .putExtra(ChatRoomComposeActivity.EXTRA_ROOM_ID, roomId)
        )
    }

    internal fun deleteRoom(roomId: String) {
        val realm = Realm.getDefaultInstance()
        try {
            realm.executeTransaction { tx ->
                tx.where(ChatRoomMember::class.java)
                    .equalTo("roomId", roomId)
                    .findAll()
                    .deleteAllFromRealm()

                val prefix = "roommsg:$roomId:"
                tx.where(Message::class.java)
                    .beginGroup()
                    .equalTo("roomId", roomId)
                    .or()
                    .beginsWith("content", prefix)
                    .endGroup()
                    .findAll()
                    .deleteAllFromRealm()

                tx.where(ChatRoom::class.java)
                    .equalTo("id", roomId)
                    .findAll()
                    .deleteAllFromRealm()
            }
        } finally {
            realm.close()
        }

        contactsVersion.intValue++
        pendingSnackbarMessage = "Chatroom deleted"
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        pendingSnackbarMessage = getString(R.string.copied_to_clipboard)
    }

    private fun showQrCompose() {
        val name = Database.getInstance(this).name ?: ""
        val txt = "Kriptex ${tor.getID()} $name"

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
                pixels[offset + x] = if (mat[x, y].toInt() != 0) Color.BLACK else Color.WHITE
            }
        }
        var bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        // Scale to a large, readable size based on the device screen.
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    activity: MainComposeActivity,
    contactsVersion: Int,
    rooms: RealmResults<ChatRoom>?,
    pendingSnackbarMessage: String?,
    onClearPendingSnackbar: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRoom: (String) -> Unit,
    onJoinRoom: () -> Unit,
    onCreateRoom: () -> Unit,
    torReady: Boolean,
    torStatus: String,
    myId: String,
    myAlias: String?
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var createRoomConfirmOpen by remember { mutableStateOf(false) }
    var deleteRoomConfirmOpen by remember { mutableStateOf(false) }
    var pendingDeleteRoomId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pendingSnackbarMessage) {
        val msg = pendingSnackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        onClearPendingSnackbar()
    }

    val roomList = remember(contactsVersion) { rooms?.toList() ?: emptyList() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = (myAlias?.trim().takeUnless { it.isNullOrEmpty() } ?: "Anonymous"))
                        Text(
                            text = myId,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Text(text = "…")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "STATUS",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Tor: ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val torValue = if (torReady) "Ready" else (torStatus.takeIf { it.isNotBlank() } ?: "Not Ready")
                        Text(
                            text = torValue,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (torReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Encryption: ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Enabled",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    @Composable
                    fun statusTbaRow(label: String) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "$label: ",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "TBA",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }

                    statusTbaRow("Packet Meta Info Hider")
                    statusTbaRow("IPv4 Spoof")
                    statusTbaRow("MAC Spoof")
                    statusTbaRow("App Spoof")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!torReady) {
                TorBanner(status = torStatus)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { createRoomConfirmOpen = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(text = "+")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "CREATE ROOM")
                }

                Button(
                    onClick = onJoinRoom,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Text(text = "JOIN (SCAN)")
                }
            }

            if (roomList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "No rooms")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(roomList, key = { it.id }) { room ->
                        RoomRow(
                            version = contactsVersion,
                            room = room,
                            onOpen = { onOpenRoom(room.id) },
                            onRequestDelete = {
                                pendingDeleteRoomId = room.id
                                deleteRoomConfirmOpen = true
                            }
                        )
                        Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    }
                }
            }
        }

        if (createRoomConfirmOpen) {
            AlertDialog(
                onDismissRequest = { createRoomConfirmOpen = false },
                title = { Text("Create chatroom") },
                text = { Text("Create a new chatroom now?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            createRoomConfirmOpen = false
                            onCreateRoom()
                        }
                    ) { Text("CREATE") }
                },
                dismissButton = {
                    TextButton(onClick = { createRoomConfirmOpen = false }) { Text("CANCEL") }
                }
            )
        }

        if (deleteRoomConfirmOpen) {
            AlertDialog(
                onDismissRequest = {
                    deleteRoomConfirmOpen = false
                    pendingDeleteRoomId = null
                },
                title = { Text("Delete chatroom") },
                text = { Text("Delete this chatroom and all its messages?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val rid = pendingDeleteRoomId
                            deleteRoomConfirmOpen = false
                            pendingDeleteRoomId = null
                            if (!rid.isNullOrBlank()) {
                                activity.deleteRoom(rid)
                            }
                        }
                    ) { Text("DELETE", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            deleteRoomConfirmOpen = false
                            pendingDeleteRoomId = null
                        }
                    ) { Text("CANCEL") }
                }
            )
        }

    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun RoomRow(
    version: Int,
    room: ChatRoom,
    onOpen: () -> Unit,
    onRequestDelete: () -> Unit
) {
    val name = room.name?.takeIf { it.isNotBlank() } ?: "ROOM"
    val unread = remember(version, room.id, room.lastReadStableId) {
        val prefix = "roommsg:${room.id}:"
        val realm = Realm.getDefaultInstance()
        try {
            val maxStableId = realm.where(Message::class.java)
                .beginGroup()
                .equalTo("roomId", room.id)
                .or()
                .beginsWith("content", prefix)
                .endGroup()
                .max("stableId")
                ?.toLong()
                ?: 0L
            maxStableId > room.lastReadStableId
        } finally {
            realm.close()
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onRequestDelete)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = room.id.take(12),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
        if (unread) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(modifier = Modifier.width(10.dp))
        }
        Text(text = ">", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}



@Composable
private fun TorBanner(status: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(text = "TOR BOOTSTRAP", fontWeight = FontWeight.SemiBold)
        Text(
            text = status.takeIf { it.isNotBlank() } ?: "starting…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ContactRow(
    activity: MainComposeActivity,
    contact: Contact,
    onOpenChat: (String) -> Unit,
    onPendingRequestClick: () -> Unit,
    onCopyId: () -> Unit,
    onChangeAlias: (String) -> Unit,
    onDelete: () -> Unit
) {
    var contextMenuOpen by remember(contact._id) { mutableStateOf(false) }
    val name = contact.name?.takeIf { it.isNotBlank() } ?: "Anonymous"
    val address = contact.address ?: ""

    data class LastMessageInfo(val stableId: Long, val type: Int, val content: String?)

    val lastMessageInfo = remember(contact._id, contact.lastMessageTime) {
        // Mirrors ContactsAdapter.getLastMessage, but copies fields to avoid Realm lifecycle issues.
        val tor = Tor.getInstance(activity)
        val myId = tor.getID().orEmpty()
        val otherId = contact.address.orEmpty()
        if (myId.isBlank() || otherId.isBlank()) return@remember null
        val realm = Realm.getDefaultInstance()
        try {
            val msg = realm.where(Message::class.java)
                .beginGroup()
                .equalTo("sender", myId)
                .equalTo("receiver", otherId)
                .endGroup()
                .or()
                .beginGroup()
                .equalTo("sender", otherId)
                .equalTo("receiver", myId)
                .endGroup()
                .sort("stableId", Sort.DESCENDING)
                .findFirst()

            if (msg == null) null else LastMessageInfo(msg.stableId, msg.type, msg.content)
        } finally {
            realm.close()
        }
    }

    val preview = remember(lastMessageInfo?.stableId) {
        if (lastMessageInfo == null) address else {
            val content = lastMessageInfo.content
            if (!content.isNullOrBlank()) content
            else when (lastMessageInfo.type) {
                Message.TYPE_IMAGE -> "Photo"
                Message.TYPE_VIDEO -> "Video"
                Message.TYPE_AUDIO -> "Audio"
                Message.TYPE_FILE -> "File"
                else -> ""
            }
        }
    }

    val time = remember(contact.lastMessageTime) {
        TimeAgo.getTimeAgo(contact.lastMessageTime, activity) ?: ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (address.isBlank()) return@combinedClickable
                    if (contact.pubKey == null) {
                        onPendingRequestClick()
                    } else {
                        onOpenChat(address)
                    }
                },
                onLongClick = { contextMenuOpen = true }
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Text(text = name.take(1).uppercase(Locale.US))
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(8.dp))
                val unread = contact.pending ?: 0
                if (unread > 0) {
                    Box(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (unread > 99) "99" else unread.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = time,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }

    if (contextMenuOpen) {
        ContactContextDialog(
            name = name,
            address = address,
            onDismiss = { contextMenuOpen = false },
            onCopyId = {
                contextMenuOpen = false
                onCopyId()
            },
            onChangeAlias = { newAlias ->
                contextMenuOpen = false
                onChangeAlias(newAlias)
            },
            onDelete = {
                contextMenuOpen = false
                onDelete()
            }
        )
    }
}

@Composable
private fun ConnectDialog(
    myId: String,
    onDismiss: () -> Unit,
    onShowQr: () -> Unit,
    onScanQr: () -> Unit,
    onEnterId: () -> Unit,
    onCopyMyId: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("CONNECT") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(text = "YOUR ID", style = MaterialTheme.typography.bodySmall)
                Text(text = myId, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onShowQr, modifier = Modifier.fillMaxWidth()) { Text("SHOW QR") }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onScanQr, modifier = Modifier.fillMaxWidth()) { Text("SCAN QR") }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onEnterId, modifier = Modifier.fillMaxWidth()) { Text("ENTER ID") }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onCopyMyId, modifier = Modifier.fillMaxWidth()) { Text("COPY MY ID") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("CLOSE") } }
    )
}

@Composable
private fun AddContactDialog(
    initialId: String,
    initialAlias: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit
) {
    var id by remember { mutableStateOf(initialId) }
    var alias by remember { mutableStateOf(initialAlias) }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ADD CONTACT") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text("ID (.onion optional)") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next)
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("Alias") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                    minLines = 2
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(id, alias, description) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL") }
        }
    )
}

@Composable
private fun ContactContextDialog(
    name: String,
    address: String,
    onDismiss: () -> Unit,
    onCopyId: () -> Unit,
    onChangeAlias: (String) -> Unit,
    onDelete: () -> Unit
) {
    var changeOpen by remember { mutableStateOf(false) }

    if (!changeOpen) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(name) },
            text = {
                Column {
                    Text(text = address, style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(onClick = { changeOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("CHANGE ALIAS") }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onCopyId, modifier = Modifier.fillMaxWidth()) { Text("COPY ID") }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onDelete,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("DELETE") }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("CLOSE") } }
        )
    } else {
        var alias by remember { mutableStateOf(name) }
        AlertDialog(
            onDismissRequest = { changeOpen = false },
            title = { Text("CHANGE ALIAS") },
            text = {
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("Alias") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = { onChangeAlias(alias.trim()); changeOpen = false }) { Text("APPLY") }
            },
            dismissButton = {
                TextButton(onClick = { changeOpen = false }) { Text("CANCEL") }
            }
        )
    }
}

