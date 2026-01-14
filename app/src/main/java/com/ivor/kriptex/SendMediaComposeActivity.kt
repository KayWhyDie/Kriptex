package com.ivor.kriptex

import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide
import com.ivor.kriptex.db.Message
import com.ivor.kriptex.service.VideoTranscodeService
import com.ivor.kriptex.tor.Client
import com.ivor.kriptex.tor.FileServer
import com.ivor.kriptex.tor.Tor
import com.ivor.kriptex.ui.compose.KriptexTheme
import com.ivor.kriptex.utils.Util
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class SendMediaComposeActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_FILE_TYPE = "file_type"
    }

    private var address: String = ""
    private var filePath: String = ""
    private var attachFileType: Int = Message.TYPE_IMAGE

    private var mimeType: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val extra = intent.extras
        if (extra != null) {
            address = extra.getString(EXTRA_ADDRESS, "")
            filePath = extra.getString(EXTRA_FILE_PATH, "")
            attachFileType = extra.getInt(EXTRA_FILE_TYPE, Message.TYPE_IMAGE)
        }

        if (address.isBlank() || filePath.isBlank() || !File(filePath).exists()) {
            Toast.makeText(this, "File not found: $filePath", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        mimeType = FileServer.getMimeType(filePath)
        val inferred = mimeType?.let { FileServer.getMessageType(it) }
        if (inferred != null) {
            attachFileType = inferred
        }

        setContent {
            KriptexTheme {
                SendMediaScreen(
                    onBack = { finish() },
                    filePath = filePath,
                    type = attachFileType,
                    mimeType = mimeType,
                    onSend = { description ->
                        doSend(description)
                    }
                )
            }
        }
    }

    private fun doSend(description: String) {
        val sender = Tor.getInstance(this).getID()
        if (sender.isNullOrBlank()) {
            Toast.makeText(this, "Unable to get sender", Toast.LENGTH_SHORT).show()
            return
        }

        val msg = description.trim()
        val type = attachFileType

        if (type == Message.TYPE_IMAGE) {
            ResizeImageTask(sender, msg, type).execute(filePath)
            return
        }

        if (type == Message.TYPE_VIDEO) {
            val thumb = buildThumbnailFromCurrentPreview(filePath)
            val thumbFilePath = if (thumb != null) {
                Util.writeBitmapCache(this, thumb, UUID.randomUUID().toString())
            } else {
                null
            }

            VideoTranscodeService.startVideoTranscode(
                this,
                filePath,
                File(filesDir, UUID.randomUUID().toString() + ".mp4").absolutePath,
                arrayOf(address),
                msg,
                mimeType,
                type,
                thumbFilePath
            )
            finish()
            return
        }

        sendMessage(sender, msg, filePath, mimeType, type, null)
        finish()
    }

    private fun buildThumbnailFromCurrentPreview(filePath: String): Bitmap? {
        return try {
            val bmp = ThumbnailUtils.createVideoThumbnail(filePath, MediaStore.Video.Thumbnails.MINI_KIND)
            if (bmp != null) {
                ThumbnailUtils.extractThumbnail(bmp, Util.THUMBNAIL_SIZE, Util.THUMBNAIL_SIZE)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sendMessage(
        sender: String,
        message: String,
        filePath: String,
        mimeType: String?,
        type: Int,
        thumbnail: ByteArray?
    ) {
        Message.addPendingOutgoingMessage(
            sender,
            address,
            message,
            File(filePath).name,
            filePath,
            mimeType,
            type,
            thumbnail
        )
        Client.getInstance(this).startSendPendingMessages(address)
    }

    private inner class ResizeImageTask(
        private val sender: String,
        private val message: String,
        private val type: Int
    ) : AsyncTask<String, Void, String?>() {

        private var thumbnailBytes: ByteArray? = null

        override fun onPreExecute() {
            Toast.makeText(this@SendMediaComposeActivity, "Resizing image", Toast.LENGTH_SHORT).show()
        }

        override fun doInBackground(vararg params: String): String? {
            return try {
                val inputFile = File(params[0])
                val outFile = File(filesDir, inputFile.name)
                val out = Util.lessResolution(inputFile.absolutePath, 1280, 720)
                FileOutputStream(outFile).use { fos ->
                    out.compress(Bitmap.CompressFormat.JPEG, 75, fos)
                }

                val thumbBitmap = ThumbnailUtils.extractThumbnail(out, Util.THUMBNAIL_SIZE, Util.THUMBNAIL_SIZE)
                val baos = ByteArrayOutputStream()
                thumbBitmap.compress(Bitmap.CompressFormat.JPEG, 50, baos)
                thumbnailBytes = baos.toByteArray()

                out.recycle()
                outFile.absolutePath
            } catch (_: Exception) {
                null
            }
        }

        override fun onPostExecute(result: String?) {
            if (result != null) {
                Toast.makeText(this@SendMediaComposeActivity, "Image resized, sending…", Toast.LENGTH_SHORT).show()
                sendMessage(sender, message, result, "image/jpeg", type, thumbnailBytes)
            }
            finish()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun SendMediaScreen(
    onBack: () -> Unit,
    filePath: String,
    type: Int,
    mimeType: String?,
    onSend: (String) -> Unit,
) {
    var description by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SEND") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("<") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(10.dp)
            ) {
                Text(text = File(filePath).name)
                Text(
                    text = (mimeType ?: "") + "  •  " + type,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            if (type == Message.TYPE_IMAGE || type == Message.TYPE_VIDEO) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .background(MaterialTheme.colorScheme.surface),
                    factory = { ctx ->
                        ImageView(ctx).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            Glide.with(ctx).load(Uri.fromFile(File(filePath))).into(this)
                        }
                    }
                )
            } else {
                Spacer(modifier = Modifier.height(1.dp))
            }

            TextField(
                value = description,
                onValueChange = { description = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("description (optional)") },
                maxLines = 3
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = { onSend(description) }) {
                    Text("SEND")
                }
            }
        }
    }
}
