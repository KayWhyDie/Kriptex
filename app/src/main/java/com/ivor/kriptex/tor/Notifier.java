/*
 * Chat.onion - P2P Instant Messenger
 *
 * http://play.google.com/store/apps/details?id=onion.chat
 * http://onionapps.github.io/Chat.onion/
 * http://github.com/onionApps/Chat.onion
 *
 * Author: http://github.com/onionApps - http://jkrnk73uid7p5thz.onion - bitcoin:1kGXfWx8PHZEVriCNkbP5hzD15HS4AyKf
 */

package com.ivor.kriptex.tor;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.TaskStackBuilder;

import com.ivor.kriptex.MainActivity;
import com.ivor.kriptex.R;
import com.ivor.kriptex.db.Database;
import com.ivor.kriptex.utils.VisibleChatTracker;
import com.ivor.kriptex.utils.Settings;
import com.ivor.kriptex.utils.Util;

import java.io.File;

public class Notifier {

    private static Notifier instance;
    private Context context;
    private int activities = 0;

    private static final String MESSAGE_CHANNEL_ID = "kriptex_message_01";// The id of the channel.

    private Notifier(Context context) {
        context = context.getApplicationContext();
        this.context = context;
    }

    public static Notifier getInstance(Context context) {
        context = context.getApplicationContext();
        if (instance == null) {
            instance = new Notifier(context);
        }
        return instance;
    }

    private void log(String s) {
        Log.i("Notifier", s);
    }

    public synchronized void onMessage() {
        log("onMessage");
        // Legacy entry-point (older code paths). Prefer onIncomingChatMessage(chatId).
        if (activities <= 0) {
            Database.getInstance(context).addNotification();
            update();
        }
    }

    public synchronized void onIncomingChatMessage(String chatId) {
        if (chatId == null || chatId.trim().isEmpty()) {
            // Fallback: keep old global behavior.
            onMessage();
            return;
        }

        if (!Settings.getPrefs(context).getBoolean("notify", true)) return;
        if (VisibleChatTracker.isChatVisible(chatId)) return;

        int count = getChatNotificationCount(chatId) + 1;
        setChatNotificationCount(chatId, count);
        showChatNotification(chatId, count);
    }

    public synchronized void onChatVisible(String chatId) {
        if (chatId == null || chatId.trim().isEmpty()) return;
        setChatNotificationCount(chatId, 0);
        cancelChatNotification(chatId);
    }

    public synchronized void onResumeActivity() {
        // Legacy lifecycle hook from pre-Compose Activities.
        // Keep it for compatibility, but do not globally suppress notifications.
        activities++;
    }

    public synchronized void onPauseActivity() {
        activities--;
    }

    private String chatNotifKey(String chatId) {
        return "chat_notif_count_" + chatId.trim();
    }

    private int getChatNotificationCount(String chatId) {
        return Settings.getPrefs(context).getInt(chatNotifKey(chatId), 0);
    }

    private void setChatNotificationCount(String chatId, int count) {
        if (count < 0) count = 0;
        Settings.getPrefs(context).edit().putInt(chatNotifKey(chatId), count).apply();
    }

    private int notificationIdForChat(String chatId) {
        int h = chatId.trim().hashCode();
        int positive = h & 0x7fffffff;
        // Keep IDs in a small, non-conflicting range.
        return 10000 + (positive % 50000);
    }

    private void cancelChatNotification(String chatId) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(notificationIdForChat(chatId));
    }

    private void showChatNotification(String chatId, int count) {
        String title = context.getResources().getString(R.string.app_name);
        String body = context.getResources().getQuantityString(R.plurals.notification_new_messages, count, count);

        Intent intent;
        // Current app navigation is rooms-first; open MainActivity as a safe default.
        intent = new Intent(context, MainActivity.class);

        showNotification(context, title, body, intent, notificationIdForChat(chatId));
    }

    private void update() {
        log("update");
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        int messageId = 5;
        int requestId = 6;
        int messages = Database.getInstance(context).getNotifications();
        if (messages <= 0 || !Settings.getPrefs(context).getBoolean("notify", true)) {
            log("cancel");
            notificationManager.cancel(messageId);
            notificationManager.cancel(requestId);
        } else {
            log("notify");
            showNotification(context,
                    context.getResources().getString(R.string.app_name),
                    context.getResources().getQuantityString(R.plurals.notification_new_messages, messages, messages),
                    new Intent(context, MainActivity.class));
        }
    }

    public void showNotification(Context context, String title, String body, Intent intent) {
        showNotification(context, title, body, intent, 5);
    }

    public void showNotification(Context context, String title, String body, Intent intent, int notificationId) {
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        String channelId = "kriptex_message_01";
        String channelName = "Kriptex Message";
        int importance = NotificationManager.IMPORTANCE_HIGH;

        String notificationTone = Settings.getPrefs(context).getString("ringtone", "DEFAULT_SOUND");

        log("Notification tone : " + notificationTone);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {

//            NotificationChannel existingChannel = notificationManager.getNotificationChannel(channelId);
//            if (existingChannel != null) {
//                notificationManager.deleteNotificationChannel(channelId);
//            }

            NotificationChannel mChannel = new NotificationChannel(
                    channelId, channelName, importance);

            if (Settings.getPrefs(context).getBoolean("sound", true)) {
                AudioAttributes att = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();

                mChannel.setSound(Uri.parse(notificationTone), att);
            }
            notificationManager.createNotificationChannel(mChannel);
        }

        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_stat_chat)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setContentTitle(title)
                .setContentText(body);


        if (Settings.getPrefs(context).getBoolean("sound", true)) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
                mBuilder.setSound(Uri.parse(notificationTone));
        }

        TaskStackBuilder stackBuilder = TaskStackBuilder.create(context);
        stackBuilder.addNextIntent(intent);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent resultPendingIntent = stackBuilder.getPendingIntent(
                0,
            pendingFlags
        );
        mBuilder.setContentIntent(resultPendingIntent);

        notificationManager.notify(notificationId, mBuilder.build());
    }

}
