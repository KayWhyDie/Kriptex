package com.ivor.kriptex.service;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.daasuu.mp4compose.FillMode;
import com.daasuu.mp4compose.composer.Mp4Composer;
import com.ivor.kriptex.R;
import com.ivor.kriptex.crypto.media.MediaAttachmentCrypto;
import com.ivor.kriptex.db.Message;
import com.ivor.kriptex.tor.Client;
import com.ivor.kriptex.tor.Tor;
import com.ivor.kriptex.utils.Util;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * An {@link IntentService} subclass for handling asynchronous task requests in
 * a service on a separate handler thread.
 * <p>
 * TODO: Customize class - update intent actions, extra parameters and static
 * helper methods.
 */
public class VideoTranscodeService extends IntentService {

    public static final int VIDEO_WIDTH = 480;
    public static final int VIDEO_HEIGHT = 360;
    public static final int VIDEO_BITRATE = 360;

    // TODO: Rename parameters
    private static final String EXTRA_SRC_FILE = "com.ivor.kriptex.service.extra.SRC_FILE";
    private static final String EXTRA_DEST_FILE = "com.ivor.kriptex.service.extra.DEST_FILE";
    private static final String EXTRA_RECEIVER = "com.ivor.kriptex.service.extra.RECEIVER";
    private static final String EXTRA_MESSAGE = "com.ivor.kriptex.service.extra.MESSAGE";
    private static final String EXTRA_MIME_TYPE = "com.ivor.kriptex.service.extra.MIME_TYPE";
    private static final String EXTRA_ATTACH_FILE_TYPE = "com.ivor.kriptex.service.extra.ATTACH_FILE_TYPE";
    private static final String EXTRA_THUMB_FILE_PATH = "com.ivor.kriptex.service.extra.THUMB_FILE_PATH";
    private static final String EXTRA_THUMB_BYTES = "com.ivor.kriptex.service.extra.THUMB_BYTES";
    private static final int NOTIFICATION_ID = 9;

    public VideoTranscodeService() {
        super("VideoTranscodeService");
    }

    /**
     * Starts this service to perform action Foo with the given parameters. If
     * the service is already performing a task this action will be queued.
     *
     * @see IntentService
     */
    // TODO: Customize helper method
    public static void startVideoTranscode(Context context, String srcFile, String desFile,
                                           String[] receiver,
                                           String message,
                                           String mimeType,
                                           int fileType,
                                           String thumbFilePath) {
        Intent intent = new Intent(context, VideoTranscodeService.class);
        intent.putExtra(EXTRA_SRC_FILE, srcFile);
        intent.putExtra(EXTRA_DEST_FILE, desFile);
        intent.putExtra(EXTRA_RECEIVER, receiver);
        intent.putExtra(EXTRA_MESSAGE, message);
        intent.putExtra(EXTRA_MIME_TYPE, mimeType);
        intent.putExtra(EXTRA_ATTACH_FILE_TYPE, fileType);
        intent.putExtra(EXTRA_THUMB_FILE_PATH, thumbFilePath);
        context.startService(intent);
    }

    /**
     * Variant that avoids writing a thumbnail file to disk; thumbnail bytes are sent as-is.
     */
    public static void startVideoTranscodeWithThumbBytes(Context context, String srcFile, String desFile,
                                                         String[] receiver,
                                                         String message,
                                                         String mimeType,
                                                         int fileType,
                                                         byte[] thumbBytes) {
        Intent intent = new Intent(context, VideoTranscodeService.class);
        intent.putExtra(EXTRA_SRC_FILE, srcFile);
        intent.putExtra(EXTRA_DEST_FILE, desFile);
        intent.putExtra(EXTRA_RECEIVER, receiver);
        intent.putExtra(EXTRA_MESSAGE, message);
        intent.putExtra(EXTRA_MIME_TYPE, mimeType);
        intent.putExtra(EXTRA_ATTACH_FILE_TYPE, fileType);
        intent.putExtra(EXTRA_THUMB_BYTES, thumbBytes);
        context.startService(intent);
    }

    private int getInt(String key, Intent intent) {
        Bundle extra = intent.getExtras();
        if (extra != null && extra.containsKey(key)) {
            return extra.getInt(key);
        }
        return -1;
    }

    private String getString(String key, Intent intent) {
        Bundle extra = intent.getExtras();
        if (extra != null && extra.containsKey(key)) {
            return extra.getString(key);
        }
        return null;
    }

    private String[] getStringArray(String key, Intent intent) {
        Bundle extra = intent.getExtras();
        if (extra != null && extra.containsKey(key)) {
            return extra.getStringArray(key);
        }
        return null;
    }

    public boolean isTranscodeNecessary(String file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(file);
        int width = Integer.valueOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
        int height = Integer.valueOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
        int bitrate = Integer.valueOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));

        log("Bitrate of the file: " + bitrate);

        return bitrate > (VIDEO_WIDTH * VIDEO_HEIGHT * 30 * 0.15);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent != null) {
            String[] receivers = getStringArray(EXTRA_RECEIVER, intent);
            String message = getString(EXTRA_MESSAGE, intent);
            String mimeType = getString(EXTRA_MIME_TYPE, intent);
            int attachFileType = getInt(EXTRA_ATTACH_FILE_TYPE, intent);
            String thumbnailFilePath = getString(EXTRA_THUMB_FILE_PATH, intent);
            byte[] thumbnailBytes = intent.getByteArrayExtra(EXTRA_THUMB_BYTES);

            final String srcMp4Path = intent.getStringExtra(EXTRA_SRC_FILE);
            final String destMp4Path = intent.getStringExtra(EXTRA_DEST_FILE);

            final byte[] thumb = thumbnailBytes != null ? thumbnailBytes : readThumbBestEffort(thumbnailFilePath);

            if (isTranscodeNecessary(srcMp4Path)) {
                new Mp4Composer(srcMp4Path, destMp4Path)
                        .size(VIDEO_WIDTH, VIDEO_HEIGHT)
                        .fillMode(FillMode.PRESERVE_ASPECT_FIT)
                        .listener(new Mp4Composer.Listener() {
                            @Override
                            public void onProgress(double progress) {
                                log("onProgress = " + progress);
                                showNotification("Video Compression", (int) (progress * 100));
                            }

                            @Override
                            public void onCompleted() {
                                log("onCompleted()");
                                cancelNotification();

                                try {
                                    sendE2eeToAll(receivers, message, srcMp4Path, destMp4Path, mimeType, attachFileType, thumb);
                                } catch (Throwable t) {
                                    t.printStackTrace();
                                }
                            }

                            @Override
                            public void onCanceled() {
                                log("onCanceled");
                                cancelNotification();
                            }

                            @Override
                            public void onFailed(Exception exception) {
                                exception.printStackTrace();
                                log("onFailed()" + exception.getMessage());
                                cancelNotification();
                            }
                        })
                        .start();
            } else {
                try {
                    sendE2eeToAll(receivers, message, srcMp4Path, null, mimeType, attachFileType, thumb);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        }
    }

    private byte[] readThumbBestEffort(String thumbnailFilePath) {
        if (thumbnailFilePath == null || thumbnailFilePath.trim().isEmpty()) return null;
        try {
            return Util.readSmallFile(getApplicationContext(), thumbnailFilePath);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Phase 2 E2EE: encrypt video bytes for serving, bind plaintext SHA-256 into AAD, and queue messages.
     *
     * <p>Note: when a transcode output path is provided, it is used as the source bytes for encryption,
     * then deleted after ciphertext is produced (best-effort).</p>
     */
    private void sendE2eeToAll(String[] receivers,
                              String message,
                              String displayFilePath,
                              String transcodeOutputPath,
                              String mimeType,
                              int type,
                              byte[] thumbnail) throws IOException, GeneralSecurityException {
        if (receivers == null || receivers.length == 0) return;

        String sender = Tor.getInstance(this).getID();
        if (sender == null || sender.trim().isEmpty()) return;

        File plainFile = new File(transcodeOutputPath != null ? transcodeOutputPath : displayFilePath);
        if (!plainFile.exists()) throw new IOException("video_source_missing");

        String finalMime = (mimeType == null || mimeType.trim().isEmpty()) ? "video/mp4" : mimeType;
        long plaintextSize = plainFile.length();
        byte[] plaintextSha256 = MediaAttachmentCrypto.sha256File(plainFile);

        String mediaId = MediaAttachmentCrypto.randomMediaIdUuid();
        byte[] mediaKey = MediaAttachmentCrypto.randomMediaKey32();

        int chunkSize = MediaAttachmentCrypto.CHUNK_SIZE_DEFAULT_BYTES;
        MediaAttachmentCrypto.ChunkedEncryptionResult enc = MediaAttachmentCrypto.encryptFileToChunkedCiphertexts(
            getApplicationContext(),
            plainFile,
            mediaId,
            mediaKey,
            chunkSize,
            plaintextSize,
            plaintextSha256);
        byte[] wrappedForDevice = MediaAttachmentCrypto.wrapMediaKeyForDevice(mediaKey, Tor.getInstance(getApplicationContext()));

        // Best-effort: remove plaintext transcode output after ciphertext is written.
        if (transcodeOutputPath != null) {
            //noinspection ResultOfMethodCallIgnored
            new File(transcodeOutputPath).delete();
        }

        for (String r : receivers) {
            if (r == null || r.trim().isEmpty()) continue;

            // Preserve legacy naming: if a transcode output existed, keep its filename.
            String filename = transcodeOutputPath != null ? new File(transcodeOutputPath).getName() : new File(displayFilePath).getName();

                Message.addPendingOutgoingChunkedMessage(
                    sender,
                    r,
                    message,
                    filename,
                    displayFilePath,
                    finalMime,
                    type,
                    thumbnail,
                    mediaId,
                    wrappedForDevice,
                    MediaAttachmentCrypto.AEAD_XCHACHA20_POLY1305,
                    enc.totalCiphertextBytes,
                    plaintextSize,
                    plaintextSha256,
                    enc.chunkSize,
                    enc.totalChunks);

            Client.getInstance(this).startSendPendingMessages(r);
        }
    }

    private void showNotification(String title, int progress) {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        String channelId = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? createNotificationChannel(notificationManager) : "";
        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, channelId);

        Notification notification = notificationBuilder
                .setOngoing(true)
                .setSmallIcon(R.drawable.ic_video)
                .setContentText(title)
                .setContentTitle("Kriptex Tor")
                .setOnlyAlertOnce(true)
                .setProgress(100, progress, progress <= 0)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();

        notificationManager.notify(NOTIFICATION_ID, notification);
    }


    @RequiresApi(Build.VERSION_CODES.O)
    private String createNotificationChannel(NotificationManager notificationManager) {
        String channelId = "kriptex_transcode_video_01";
        String channelName = "Transcode Video";
        NotificationChannel channel = new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT);
        // omitted the LED color
        channel.setImportance(NotificationManager.IMPORTANCE_DEFAULT);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        notificationManager.createNotificationChannel(channel);
        return channelId;
    }

    // Legacy plaintext sender kept intentionally removed from the video pipeline.

    private void cancelNotification() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(NOTIFICATION_ID);
    }

    private void log(String s) {
        Log.d("VideoTranscodeService", s);
    }
}
