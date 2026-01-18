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

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.ivor.kriptex.BuildConfig;
import com.ivor.kriptex.crypto.AdvancedCrypto;
import com.ivor.kriptex.crypto.media.MediaAttachmentCrypto;
import com.ivor.kriptex.db.Contact;
import com.ivor.kriptex.db.Database;
import com.ivor.kriptex.db.FileShare;
import com.ivor.kriptex.db.Message;
import com.ivor.kriptex.db.TorData;
import com.ivor.kriptex.db.TorRequest;
import com.ivor.kriptex.utils.Util;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;

import io.realm.Realm;
import io.realm.RealmResults;

public class Client {

    private static final String TAG = "Client";
    private static Client instance;
    private Tor tor;
    private Database mDatabase;
    private Realm mRealm;

    private Context mContext;
    private AtomicInteger counter = new AtomicInteger();
    private StatusListener statusListener;

    private volatile HashSet<String> mMessageSending = new HashSet<>();

    // Coalesce repeated start requests while a send loop is running for a given address.
    // This is NOT a retry counter; it just signals “run another pass”.
    private volatile AtomicInteger mRescanPendingSends = new AtomicInteger(0);

    private static final int SEND_RETRY_MAX_ATTEMPTS = 3;
    private static final long SEND_RETRY_BASE_DELAY_MS = 2_500L;

    private final AtomicBoolean mSendingPendingFriends = new AtomicBoolean(false);

    public Client(Context c) {
        mContext = c;
        tor = Tor.getInstance(mContext);

        mDatabase = Database.getInstance(mContext);
        mRealm = Realm.getDefaultInstance();
    }

    public static Client getInstance(Context context) {
        if (instance == null)
            instance = new Client(context.getApplicationContext());

//        instance.testPrivatePublicKeyEncryption(UUID.randomUUID().toString());
        return instance;
    }

    private void log(String s) {
        if (!BuildConfig.DEBUG) return;
        Log.i("Client", s);
    }

    private Sock connect(String address) {
        String host = address == null ? "" : address.trim().toLowerCase();
        if (!host.endsWith(".onion")) host = host + ".onion";
        log("connect to " + host);
        return new Sock(host, Tor.getHiddenServicePort());
    }

    private String sendAdd(String receiver, String description) {

        String sender = tor.getID();

        String n = mDatabase.getName();
        if (n == null || n.trim().isEmpty()) n = " ";
        Gson gson = new Gson();

        TorData td = new TorData();
        td.setReceiver(receiver);
        td.setSender(sender);
        td.setDataType(TorData.TYPE_REQUEST);

        TorRequest tr = new TorRequest();
        tr.setSender(sender);
        tr.setReceiver(receiver);
        tr.setSenderName(n);
        tr.setDescription(description);

        td.setData(gson.toJson(tr));
        td.setPubKeySpec(Util.base64encode(tor.getPubKeySpec()));
        td.setSignature(Util.base64encode(tor.sign(("add " + sender + " " + td.getData()).getBytes(StandardCharsets.UTF_8))));

        String content = gson.toJson(td);
        content = Util.base64encode(content.getBytes(StandardCharsets.UTF_8));

        return connect(receiver).queryAndCloseString(
                "add",
                content
        );
    }

    /**
     * check if this {@link Message} is Tx
     *
     * @param message
     * @return
     */
    public boolean isTxMessage(Message message) {
        String sender = message.getSender();
        return sender.equals(Tor.getInstance(mContext).getID());
    }

    /**
     * get {@link Message} for this id
     *
     * @param id
     * @return
     */
    private Message getMessage(String id) {
        Realm realm = Realm.getDefaultInstance();
        Message message = realm.where(Message.class).equalTo("primaryKey", id).findFirst();
        realm.close();
        return message;
    }

    /**
     * resolve quoted {@link Message} id
     *
     * @param message
     * @return
     */
    private Message resolveQuoteMessageId(Message message) {
        if (message.getQuotedMessageId() != null) {
            Message quotedMessage = getMessage(message.getQuotedMessageId());
            if (isTxMessage(quotedMessage)) {
                message.setQuotedMessageId(quotedMessage.getPrimaryKey());
            } else {
                message.setQuotedMessageId(quotedMessage.getRemoteMessageId());
            }
        }
        return message;
    }

    /**
     * send {@link Message} on the {@link Sock}
     *
     * @param sock
     * @param message
     * @return
     */
    private boolean sendMsg(Sock sock, Message message, Contact contact) throws Exception {
        if (sock.isClosed()) {
            return false;
        }
        if (contact == null || contact.getPubKey() == null || contact.getPubKey().length == 0) {
            // Can't send without the recipient RSA pubkey; key exchange will be handled elsewhere.
            return false;
        }

        // Work on a detached copy to avoid mutating Realm-managed objects.
        Gson gson = new Gson();
        Message sendMessage = gson.fromJson(gson.toJson(message), Message.class);
        resolveQuoteMessageId(sendMessage); // resolve quoted message id

        TorData td = new TorData();
        td.setSender(sendMessage.getSender());
        td.setReceiver(sendMessage.getReceiver());
        td.setDataType(TorData.TYPE_MESSAGE);
        String key = UUID.randomUUID().toString();

        if (sendMessage.getFileShare() != null) {
            // Prevent leaking local file paths; receiver downloads separately.
            sendMessage.getFileShare().setFilePath("");
            sendMessage.getFileShare().setDownloaded(false);

            // If this is an E2EE attachment, re-wrap the media key under the per-message key.
            FileShare fs = sendMessage.getFileShare();
            if (fs.getMediaId() != null && !fs.getMediaId().trim().isEmpty()) {
                byte[] deviceWrapped = fs.getEncryptedMediaKey();
                if (deviceWrapped == null || deviceWrapped.length == 0) {
                    throw new IllegalStateException("E2EE attachment missing encryptedMediaKey");
                }
                byte[] mediaKey = MediaAttachmentCrypto.unwrapMediaKeyFromDevice(deviceWrapped, tor);
                byte[] transportWrapped = MediaAttachmentCrypto.wrapMediaKeyForTransport(mediaKey, key, sendMessage, fs);
                fs.setEncryptedMediaKey(transportWrapped);
                if (fs.getMediaAEAD() == null || fs.getMediaAEAD().trim().isEmpty()) {
                    fs.setMediaAEAD(MediaAttachmentCrypto.AEAD_XCHACHA20_POLY1305);
                }
            }
        }

        AdvancedCrypto advancedCrypto = new AdvancedCrypto(key);
        String content = advancedCrypto.encrypt(gson.toJson(sendMessage));
        td.setData(content);
        String encryptedKey = tor.encryptByPublicKey(key, contact.getPubKey());
        log("Encrypted key: " + encryptedKey);
        td.setSecretKey(encryptedKey);
        td.setSignature(Util.base64encode(tor.sign(("msg " + content).getBytes(StandardCharsets.UTF_8))));

        String jsonContent = gson.toJson(td);
        jsonContent = Util.base64encode(jsonContent.getBytes(StandardCharsets.UTF_8));
        String sender = tor.getID();
        if (sendMessage.getReceiver().equals(sender)) return false;

        return sock.queryBool(
                "msg",
                jsonContent
        );
    }

    public void startSendPendingFriends() {
        if (!mSendingPendingFriends.compareAndSet(false, true)) {
            log("start send pending friends: already running");
            return;
        }
        log("start send pending friends");
        start(() -> {
            try {
                doSendPendingFriends();
            } finally {
                mSendingPendingFriends.set(false);
            }
        });
    }

    public void testPrivatePublicKeyEncryption(String data) {
        log("Data: " + data);
        try {
            String encrypted = tor.encryptByPublicKey(data);
            log("Encrypted: " + encrypted);
            String decrypted = tor.decryptByPrivateKey(encrypted);
            log("Decrypted: " + decrypted);
            log("local successful: " + data.equals(decrypted));
        } catch (NoSuchPaddingException e) {
            e.printStackTrace();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        } catch (BadPaddingException e) {
            e.printStackTrace();
        } catch (IllegalBlockSizeException e) {
            e.printStackTrace();
        } catch (InvalidKeyException e) {
            e.printStackTrace();
        } catch (NoSuchProviderException e) {
            e.printStackTrace();
        }
    }

    public void doSendPendingFriends() {
        log("do send pending friends");

        // IMPORTANT: don't keep a Realm write transaction open while doing Tor network I/O.
        // That can block Realm notifications / UI updates and causes confusing “pending” behavior.
        List<String> outgoingAddresses = new ArrayList<>();
        List<String> outgoingDescriptions = new ArrayList<>();
        Realm snapshotRealm = Realm.getDefaultInstance();
        try {
            RealmResults<Contact> contacts = snapshotRealm.where(Contact.class).equalTo("outgoing", 1).findAll();
            for (Contact c : contacts) {
                outgoingAddresses.add(c.getAddress());
                outgoingDescriptions.add(c.getDescription());
            }
        } finally {
            snapshotRealm.close();
        }

        for (int i = 0; i < outgoingAddresses.size(); i++) {
            String address = outgoingAddresses.get(i);
            String description = outgoingDescriptions.get(i);
            log("try to send friend request: " + address);

            String reply;
            try {
                reply = sendAdd(address, description);
            } catch (Exception e) {
                e.printStackTrace();
                continue;
            }

            if (reply == null || reply.isEmpty()) {
                continue;
            }

            // Short numeric replies are error codes from Server (e.g. 4 = invalid signature).
            if (reply.length() <= 2) {
                log("friend request failed (code=" + reply + ")");
                continue;
            }

            Realm updateRealm = Realm.getDefaultInstance();
            try {
                final String finalReply = reply;
                updateRealm.executeTransaction(r -> {
                    Contact c = r.where(Contact.class).equalTo("address", address).findFirst();
                    if (c == null) return;
                    c.setPubKey(Util.base64decode(finalReply));
                    c.setOutgoing(0);
                    c.setIncoming(0);
                });
            } finally {
                updateRealm.close();
            }

            // Now that we have a pubkey, try sending any queued messages.
            startSendPendingMessages(address);

            log("friend request sent");
        }
    }

    public void doSendAllPendingMessages() {
        log("do send all pending messages");
        Realm realm = Realm.getDefaultInstance();
        RealmResults<Contact> contacts = realm.where(Contact.class).equalTo("incoming", 0).findAll();
        for (Contact c : contacts) {
            try {
                doSendPendingMessages(c);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        realm.close();
    }

    private void doSendPendingMessages(Contact contact) throws Exception {
        log("do send pending messages");
        Realm realm = Realm.getDefaultInstance();
        Sock sock = null;
        try {
            Contact live = realm.where(Contact.class).equalTo("address", contact.getAddress()).findFirst();
            if (live == null) return;

            if (live.getPubKey() == null || live.getPubKey().length == 0) {
                // If the contact exists but has no key yet, trigger key exchange and retry later.
                realm.executeTransaction(r -> live.setOutgoing(1));
                startSendPendingFriends();
                return;
            }

            RealmResults<Message> messages = realm.where(Message.class)
                    .equalTo("pending", 1)
                    .equalTo("receiver", live.getAddress())
                    .findAll();

            sock = connect(live.getAddress());
            for (Message m : messages) {
                log("try to send message: " + m.getPrimaryKey());
                if (sendMsg(sock, realm.copyFromRealm(m), live)) {
                    if (m.isValid()) {
                        realm.beginTransaction();
                        m.setPending(0);
                        live.setLastMessageTime(m.getTime());
                        realm.commitTransaction();
                    }
                    log("message sent");
                }
            }
        } finally {
            try {
                if (sock != null) sock.close();
            } catch (Exception ignored) {
            }
            realm.close();
        }
    }

    /**
     * try to send message of the address
     *
     * @param address
     */
    public void startSendPendingMessages(final String address) {
        // Always signal that there's work to do.
        mRescanPendingSends.incrementAndGet();

        if (mMessageSending.contains(address)) {
            return;
        }

        mMessageSending.add(address);
        log("start send pending messages");
        start(() -> {
            Realm realm = Realm.getDefaultInstance();
            try {
                Contact contact = realm.where(Contact.class).equalTo("address", address).findFirst();
                if (contact == null) return;

                // Keep doing passes as long as callers keep enqueueing work.
                while (mRescanPendingSends.getAndSet(0) > 0) {
                    boolean sentSomething = false;
                    int attempt = 0;
                    while (true) {
                        try {
                            doSendPendingMessages(contact);
                            sentSomething = true;
                            break;
                        } catch (Exception e) {
                            e.printStackTrace();
                            attempt++;
                            if (attempt > SEND_RETRY_MAX_ATTEMPTS) {
                                break;
                            }
                            try {
                                long delay = SEND_RETRY_BASE_DELAY_MS * attempt;
                                Thread.sleep(delay);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }

                    // If we failed to send (or likely have more pending after partial send), schedule another pass.
                    if (!sentSomething) {
                        mRescanPendingSends.incrementAndGet();
                    }
                }
            } finally {
                realm.close();
                mMessageSending.remove(address);
            }
        });
    }

    public boolean isBusy() {
        return counter.get() > 0;
    }

    private void start(final Runnable runnable) {
        new Thread() {
            @Override
            public void run() {
                {
                    int n = counter.incrementAndGet();
                    StatusListener l = statusListener;
                    if (l != null) l.onStatusChange(n > 0);
                }
                try {
                    runnable.run();
                } finally {
                    int n = counter.decrementAndGet();
                    StatusListener l = statusListener;
                    if (l != null) l.onStatusChange(n > 0);
                }
            }
        }.start();
    }

    public void setStatusListener(StatusListener statusListener) {
        this.statusListener = statusListener;
        if (statusListener != null) {
            statusListener.onStatusChange(counter.get() > 0);
        }
    }

    public interface StatusListener {
        void onStatusChange(boolean loading);
    }


    public boolean testIfServerIsUp() {
        Sock sock = connect(tor.getID());
        log("Socket opened: " + !sock.isClosed());
        boolean ret = !sock.isClosed();
        sock.close();
        return ret;
    }

    public void doAskForNewMessages(String receiver) {
        String sender = tor.getID();
        log("ask for new msg");
        String cmd = "newmsg " + receiver + " " + sender + " " + System.currentTimeMillis() / 60000 * 60000;
        connect(receiver).queryAndClose(
                cmd,
                Util.base64encode(tor.getPubKeySpec()),
                Util.base64encode(tor.sign(cmd.getBytes(StandardCharsets.UTF_8)))
        );
    }

    public void startAskForNewMessages(final String receiver) {
        start(() -> doAskForNewMessages(receiver));
    }

    public void askForNewMessages() {
        Realm realm = Realm.getDefaultInstance();
        final RealmResults<Contact> contacts = realm.where(Contact.class).equalTo("incoming", 0).findAll();
        for (Contact c : contacts) {
            String receiver = c.getAddress();
            doAskForNewMessages(receiver);
        }
        realm.close();
    }
}
