package com.ivor.kriptex;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.ivor.kriptex.db.Message;
import com.ivor.kriptex.crypto.media.MediaAttachmentCrypto;
import com.ivor.kriptex.tor.Client;
import com.ivor.kriptex.tor.FileServer;
import com.ivor.kriptex.tor.Tor;
import com.ivor.kriptex.service.VideoTranscodeService;
import com.ivor.kriptex.utils.Util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.UUID;

//import com.ivor.kriptex.transformation.VideoRequestHandler;
//import com.squareup.picasso.Picasso;

public class SendMediaActivity extends AppCompatActivity {

    public static final String EXTRA_ADDRESS = "address";
    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_FILE_TYPE = "file_type";

    private ImageView imvwImage;
    private VideoView videoView;
    private EditText txtDescription;

    private String mAddress;
    private String mFilePath;
    private int mAttachFileType;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_send_media);

        imvwImage = findViewById(R.id.imageView);
//        videoView = findViewById(R.id.videoView);
        txtDescription = findViewById(R.id.txtDescription);

        Bundle extra = getIntent().getExtras();
        if (extra != null) {
            mAddress = extra.getString(EXTRA_ADDRESS);
            mFilePath = extra.getString(EXTRA_FILE_PATH);
            mAttachFileType = extra.getInt(EXTRA_FILE_TYPE);
        }

        if (!new File(mFilePath).exists()) {
            Toast.makeText(this, "File not found : " + mFilePath, Toast.LENGTH_SHORT).show();
            finish();
        }


        String mimeType = FileServer.getMimeType(mFilePath);
        mAttachFileType = mimeType != null ? FileServer.getMessageType(mimeType) : mAttachFileType;

        if (mAttachFileType == Message.TYPE_VIDEO || mAttachFileType == Message.TYPE_IMAGE) {
            Glide.with(this).load(Uri.fromFile(new File(mFilePath))).into(imvwImage);
        } else {
            imvwImage.setImageResource(Util.getResourceForFileType(mFilePath));
        }

        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(view1 -> {
            String sender = Tor.getInstance(this).getID();
            if (sender == null || sender.trim().equals("")) {
                Toast.makeText(this, "Unable to get sender", Toast.LENGTH_SHORT).show();
                return;
            }

            String message = txtDescription.getText().toString();
            message = message.trim();

            if (mAttachFileType == Message.TYPE_VIDEO && checkSizeIsLarge(mFilePath)) {
                // Preserve legacy behavior for large videos: transcode first (existing service), then send.
                byte[] thumbBytes = maybeBuildThumbnailBytes(imvwImage, mAttachFileType);
                VideoTranscodeService.startVideoTranscodeWithThumbBytes(
                        this,
                        mFilePath,
                        new File(getFilesDir(), UUID.randomUUID().toString() + ".mp4").getAbsolutePath(),
                        new String[]{mAddress},
                        message,
                        mimeType,
                        mAttachFileType,
                        thumbBytes);
                finish();
                return;
            }

            // Application-layer E2EE: encrypt the served bytes before queuing the message.
            // UI continues to reference the original path for local viewing.
            new EncryptAndQueueAttachmentTask(sender, message, mimeType, mAttachFileType).execute(mFilePath);
        });
    }

    private static boolean checkSizeIsLarge(String filepath) {
        File file = new File(filepath);
        long fileSizeInMB = (file.length() / 1024) / 1024;
        return fileSizeInMB > 5;
    }

    private static byte[] maybeBuildThumbnailBytes(ImageView imvwImage, int type) {
        if (type != Message.TYPE_IMAGE && type != Message.TYPE_VIDEO) return null;
        try {
            Bitmap bitmap = ((BitmapDrawable) imvwImage.getDrawable()).getBitmap();
            if (bitmap == null) return null;
            Bitmap thumb = ThumbnailUtils.extractThumbnail(bitmap, Util.THUMBNAIL_SIZE, Util.THUMBNAIL_SIZE);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            thumb.compress(Bitmap.CompressFormat.JPEG, 50, baos);
            thumb.recycle();
            return baos.toByteArray();
        } catch (Throwable ignored) {
            return null;
        }
    }

    void log(String s) {
        Log.d("SendMediaActivity", s);
    }


    class EncryptAndQueueAttachmentTask extends AsyncTask<String, Void, Boolean> {

        private final String sender;
        private final String message;
        private final String mimeType;
        private final int type;

        private String error;

        EncryptAndQueueAttachmentTask(String sender, String message, String mimeType, int type) {
            this.sender = sender;
            this.message = message;
            this.mimeType = mimeType;
            this.type = type;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            Toast.makeText(SendMediaActivity.this, "Encrypting attachment...", Toast.LENGTH_SHORT).show();
        }

        @Override
        protected Boolean doInBackground(String... strings) {
            try {
                File inputFile = new File(strings[0]);
                if (!inputFile.exists()) {
                    error = "File not found";
                    return false;
                }

                // Phase 2: preserve legacy image resize semantics without writing plaintext resized files.
                final String finalMime;
                final byte[] plaintextSha256;
                final long plaintextSize;
                final byte[] resizedPlainBytes;
                final byte[] thumbnail;

                if (type == Message.TYPE_IMAGE) {
                    Bitmap resized = Util.lessResolution(inputFile.getAbsolutePath(), 1280, 720);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    resized.compress(Bitmap.CompressFormat.JPEG, 75, baos);
                    resizedPlainBytes = baos.toByteArray();
                    plaintextSize = resizedPlainBytes.length;
                    finalMime = "image/jpeg";
                    plaintextSha256 = MediaAttachmentCrypto.sha256Bytes(resizedPlainBytes);

                    Bitmap thumb = ThumbnailUtils.extractThumbnail(resized, Util.THUMBNAIL_SIZE, Util.THUMBNAIL_SIZE);
                    ByteArrayOutputStream thumbOut = new ByteArrayOutputStream();
                    thumb.compress(Bitmap.CompressFormat.JPEG, 50, thumbOut);
                    thumbnail = thumbOut.toByteArray();

                    resized.recycle();
                    thumb.recycle();
                } else {
                    resizedPlainBytes = null;
                    plaintextSize = inputFile.length();
                    finalMime = (mimeType != null && !mimeType.trim().isEmpty()) ? mimeType : (FileServer.getMimeType(inputFile.getAbsolutePath()));
                    plaintextSha256 = MediaAttachmentCrypto.sha256File(inputFile);
                    thumbnail = maybeBuildThumbnailBytes(imvwImage, type);
                }

                String mediaId = MediaAttachmentCrypto.randomMediaIdUuid();
                byte[] mediaKey = MediaAttachmentCrypto.randomMediaKey32();

                int chunkSize = MediaAttachmentCrypto.CHUNK_SIZE_DEFAULT_BYTES;
                MediaAttachmentCrypto.ChunkedEncryptionResult enc;
                if (resizedPlainBytes != null) {
                    enc = MediaAttachmentCrypto.encryptBytesToChunkedCiphertexts(
                            SendMediaActivity.this,
                            resizedPlainBytes,
                            mediaId,
                            mediaKey,
                            chunkSize,
                            plaintextSha256);
                } else {
                    enc = MediaAttachmentCrypto.encryptFileToChunkedCiphertexts(
                            SendMediaActivity.this,
                            inputFile,
                            mediaId,
                            mediaKey,
                            chunkSize,
                            plaintextSize,
                            plaintextSha256);
                }
                byte[] wrappedForDevice = MediaAttachmentCrypto.wrapMediaKeyForDevice(mediaKey, Tor.getInstance(SendMediaActivity.this));

                Message.addPendingOutgoingChunkedMessage(
                        sender,
                        mAddress,
                        message,
                        inputFile.getName(),
                        inputFile.getAbsolutePath(),
                        finalMime,
                        type != -1 ? type : mAttachFileType,
                        thumbnail,
                        mediaId,
                        wrappedForDevice,
                        MediaAttachmentCrypto.AEAD_XCHACHA20_POLY1305,
                        enc.totalCiphertextBytes,
                        plaintextSize,
                        plaintextSha256,
                        enc.chunkSize,
                        enc.totalChunks);

                return true;
            } catch (IOException | GeneralSecurityException e) {
                error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                return false;
            } catch (Throwable t) {
                error = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                return false;
            }
        }

        @Override
        protected void onPostExecute(Boolean ok) {
            super.onPostExecute(ok);
            if (ok) {
                Client.getInstance(SendMediaActivity.this).startSendPendingMessages(mAddress);
                finish();
            } else {
                Toast.makeText(SendMediaActivity.this, "Attachment encryption failed: " + error, Toast.LENGTH_LONG).show();
            }
        }
    }
}
