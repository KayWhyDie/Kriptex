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
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import com.google.gson.Gson;
import com.ivor.kriptex.BuildConfig;
import com.ivor.kriptex.crypto.AdvancedCrypto;
import com.ivor.kriptex.crypto.media.MediaAttachmentCrypto;
import com.ivor.kriptex.db.ChatRoom;
import com.ivor.kriptex.db.ChatRoomMember;
import com.ivor.kriptex.db.Contact;
import com.ivor.kriptex.db.FileShare;
import com.ivor.kriptex.db.Message;
import com.ivor.kriptex.db.TorData;
import com.ivor.kriptex.db.TorRequest;
import com.ivor.kriptex.tor.chunked.ChunkedMediaLimits;
import com.ivor.kriptex.tor.chunked.RoundRobinMediaQueue;
import com.ivor.kriptex.utils.Util;
import com.liulishuo.filedownloader.BaseDownloadTask;
import com.liulishuo.filedownloader.FileDownloader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import android.util.Base64;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.realm.Realm;
import io.realm.RealmResults;
import io.realm.Sort;

public class Server {

    private static final String ROOMMSG_PREFIX = "roommsg:";

    public static final int CODE_UNKNOWN = 0;
    public static final int CODE_DATA_RECEIVED = 1;
    public static final int CODE_CONTACT_NOT_FOUND = 2;
    public static final int CODE_WRONG_ADDRESS = 3;
    public static final int CODE_INVALID_SIGNATURE = 4;
    public static final int CODE_WRONG_TIMESTAMP = 5;

    private static Server instance;
    private String socketName;
    private Context mContext;
    private String TAG = "Server";
    private ArrayList<Listener> mListeners = new ArrayList<>();
    private ServiceRegisterListener mServiceRegisterListener;
    private AtomicBoolean mServiceRegistered = new AtomicBoolean(false);
    private LocalServerSocket serverSocket;
    private LocalSocket ls;

    private volatile AtomicBoolean mCheckServiceRegisteredRunning = new AtomicBoolean(false);
    private Thread mServiceRegistration;

    public interface FileDownloadListener {
        void onDownloadProgressChange(String messageId);
    }

    public FileDownloadListener mFileDownloadListener;
    public Map<String, Integer> mDownloadProgress = new HashMap<>();
    public Map<String, BaseDownloadTask> mDownloadTasks = new HashMap<>();

    // --- Phase 3.5: fairness + resource-bounding for chunked downloads ---
    // Active mediaIds participating in the round-robin scheduler.
    private final RoundRobinMediaQueue mChunkedMediaQueue = new RoundRobinMediaQueue();
    // MediaIds with an in-flight manifest/chunk download task.
    private final java.util.HashSet<String> mChunkedInFlightMediaIds = new java.util.HashSet<>();

    public Server(Context c) {
        mContext = c;

        log("start listening");
        try {
            socketName = new File(mContext.getFilesDir(), "socket").getAbsolutePath();
            ls = new LocalSocket();
            ls.bind(new LocalSocketAddress(socketName, LocalSocketAddress.Namespace.FILESYSTEM));
            serverSocket = new LocalServerSocket(ls.getFileDescriptor());
            socketName = "unix:" + socketName;
            log(socketName);

        } catch (Exception ex) {
            throw new Error(ex);
        }
        log("started listening");
        new Thread() {
            @Override
            public void run() {
                while (true) {
                    LocalServerSocket ss = serverSocket;
                    if (ss == null) break;
                    log("waiting for connection");
                    final LocalSocket ls;
                    try {
                        ls = ss.accept();
                        log("accept");
                    } catch (IOException ex) {
                        throw new Error(ex);
                    }
                    if (ls == null) {
                        log("no socket");
                        continue;
                    }
                    log("new connection");
                    new Thread() {
                        @Override
                        public void run() {
                            handle(ls);
                            try {
                                ls.close();
                            } catch (IOException ex) {
                                ex.printStackTrace();
                            }
                        }
                    }.start();
                }
            }
        }.start();
    }

    public void setServiceRegistered(boolean value) {
        mServiceRegistered.set(value);
    }

    public boolean isServiceRegistered() {
        return mServiceRegistered.get();
    }

    public void setCheckServiceRegisteredRunning(boolean value) {
        mCheckServiceRegisteredRunning.set(value);
    }

    public boolean isCheckServiceRegisteredRunning() {
        return mCheckServiceRegisteredRunning.get();
    }

    public void checkServiceRegistered() {
        if (mCheckServiceRegisteredRunning.get()) return;

        setCheckServiceRegisteredRunning(true);
        mServiceRegistration = new Thread() {
            @Override
            public void run() {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ex) {
                    return;
                }
                setServiceRegistered(false);

                Tor tor = Tor.getInstance(mContext);
                for (int i = 0; i < 20 && !tor.isReady(); i++) {
                    log("Tor not ready");
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ex) {
                        return;
                    }
                }
                log("Tor ready");
                final Client client = Client.getInstance(mContext);
                for (int i = 0; i < 20 && !client.testIfServerIsUp(); i++) {
                    log("Hidden server descriptors not yet propagated");
                    if (mServiceRegisterListener != null) mServiceRegisterListener.onChange(false);
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ex) {
                        return;
                    }
                }
                log("Hidden service registered");
                setServiceRegistered(true);
                if (mServiceRegisterListener != null) mServiceRegisterListener.onChange(true);
                client.askForNewMessages();

                try {
                    FileServer.getInstance(mContext, Tor.getFileServerPort(), false).startServer();
                    log("FileServer has been started");
                } catch (IOException e) {
                    e.printStackTrace();
                }
                setCheckServiceRegisteredRunning(false);
            }
        };
        mServiceRegistration.start();
    }

    public static Server getInstance(Context context) {
        if (instance == null) {
            instance = new Server(context.getApplicationContext());
        }
        return instance;
    }

    public void setFileDownloadListener(FileDownloadListener fileDownloadListener) {
        mFileDownloadListener = fileDownloadListener;
    }

    private void log(String s) {
        if (!BuildConfig.DEBUG) return;
        Log.i(TAG, s);
    }

    private static class RoomMsg {
        final String roomId;
        final String roomMessageId;
        final String systemType;
        final String payload;

        RoomMsg(String roomId, String roomMessageId, String systemType, String payload) {
            this.roomId = roomId;
            this.roomMessageId = roomMessageId;
            this.systemType = systemType;
            this.payload = payload;
        }
    }

    private static String normalizeOnionId(String value) {
        if (value == null) return "";
        String s = value.trim().toLowerCase(Locale.US);
        if (s.endsWith(".onion")) s = s.substring(0, s.length() - ".onion".length());
        return s;
    }

    private static boolean isBase32Like(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '2' && c <= '7');
            if (!ok) return false;
        }
        return true;
    }

    private static boolean looksLikeOnionPlaceholder(String existingName, String id) {
        String n = normalizeOnionId(existingName);
        String onion = normalizeOnionId(id);
        if (n.isEmpty() || onion.isEmpty()) return false;

        if (n.equals(onion)) return true;

        int[] prefixes = new int[]{8, 16};
        for (int len : prefixes) {
            if (onion.length() >= len && n.equals(onion.substring(0, len))) return true;
        }

        return n.length() >= 8 && n.length() <= onion.length() && isBase32Like(n) && onion.startsWith(n);
    }

    private static boolean looksLikeHexToken(String value) {
        if (value == null) return false;
        String s = value.trim();
        if (s.length() < 16) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!ok) return false;
        }
        return true;
    }

    private static boolean looksLikeBase64Token(String value) {
        if (value == null) return false;
        String s = value.trim();
        if (s.length() < 8 || s.length() > 256) return false;
        if ((s.length() % 4) != 0) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '+' || c == '/' || c == '=' || c == '-' || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    private static String decodeBase64IfPrintableShort(String token) {
        if (!looksLikeBase64Token(token)) return null;
        try {
            byte[] bytes = Base64.decode(token, Base64.DEFAULT);
            if (bytes == null || bytes.length == 0 || bytes.length > 64) return null;
            String decoded = new String(bytes, StandardCharsets.UTF_8).trim();
            if (decoded.isEmpty() || decoded.length() > 32) return null;
            boolean hasLetter = false;
            for (int i = 0; i < decoded.length(); i++) {
                char c = decoded.charAt(i);
                if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') return null;
                if (Character.isLetter(c)) hasLetter = true;
            }
            if (!hasLetter) return null;
            if (looksLikeBase64Token(decoded) || looksLikeHexToken(decoded)) return null;
            return decoded;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean looksLikeKeyMaterialOrEncryptedName(String value) {
        if (value == null) return false;
        String s = value.trim();
        if (s.isEmpty()) return false;
        if (s.length() >= 40 && looksLikeBase64Token(s)) return true;
        if (s.length() >= 40 && looksLikeHexToken(s)) return true;
        return s.startsWith("AL3") && s.length() >= 40 && looksLikeBase64Token(s);
    }

    private static boolean looksLikeHumanAlias(String value) {
        if (value == null) return false;
        String s = value.trim();
        if (s.isEmpty() || s.length() > 32) return false;
        if (looksLikeBase64Token(s) || looksLikeHexToken(s)) return false;
        boolean hasLetter = false;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) {
                hasLetter = true;
                break;
            }
        }
        return hasLetter;
    }

    private static String normalizeAliasToken(String token) {
        String t = token == null ? "" : token.trim();
        String decoded = decodeBase64IfPrintableShort(t);
        return decoded != null ? decoded : t;
    }

    private static String extractAddressFromPrimaryKey(String primaryKey, String roomId) {
        if (primaryKey == null) return "";
        String rid = roomId == null ? "" : roomId;
        String prefix = rid + ":";
        if (!rid.isEmpty() && primaryKey.startsWith(prefix)) {
            return primaryKey.substring(prefix.length());
        }
        int idx = primaryKey.indexOf(':');
        if (idx >= 0 && idx + 1 < primaryKey.length()) {
            return primaryKey.substring(idx + 1);
        }
        return "";
    }

    private static ArrayList<ChatRoomMember> findRoomMemberCandidates(Realm r, String roomId, String senderNorm) {
        ArrayList<ChatRoomMember> out = new ArrayList<>();
        if (r == null) return out;
        if (roomId == null || roomId.isEmpty()) return out;
        if (senderNorm == null || senderNorm.isEmpty()) return out;

        RealmResults<ChatRoomMember> members = r.where(ChatRoomMember.class)
                .equalTo("roomId", roomId)
                .findAll();
        for (ChatRoomMember m : members) {
            if (m == null) continue;
            String addr = m.getAddress();
            String addrNorm = normalizeOnionId(addr);
            if (senderNorm.equals(addrNorm)) {
                out.add(m);
                continue;
            }

            String pkAddr = extractAddressFromPrimaryKey(m.getPrimaryKey(), roomId);
            String pkAddrNorm = normalizeOnionId(pkAddr);
            if (senderNorm.equals(pkAddrNorm)) {
                out.add(m);
            }
        }
        return out;
    }

    private RoomMsg parseRoomMsg(String content) {
        if (content == null) return null;
        if (!content.startsWith(ROOMMSG_PREFIX)) return null;

        // roommsg:<roomId>:<roomMessageId>:<type>:<base64>
        String[] tokens = content.split(":", 5);
        if (tokens.length != 5) return null;
        if (!"roommsg".equals(tokens[0])) return null;

        String roomId = tokens[1];
        String roomMessageId = tokens[2];
        String typeToken = tokens[3];
        String encoded = tokens[4];
        if (roomId == null || roomId.isEmpty()) return null;

        String payload;
        try {
            payload = new String(Util.base64decode(encoded), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }

        String systemType = (typeToken == null || typeToken.isEmpty() || "-".equals(typeToken)) ? null : typeToken;
        return new RoomMsg(roomId, roomMessageId, systemType, payload);
    }

    public void addListener(Listener l) {
        if (!mListeners.contains(l)) {
            mListeners.add(l);
            if (l != null)
                l.onChange();
        }
    }

    public void removeListener(Listener l) {
        mListeners.remove(l);
    }

    public void setServiceRegisterListener(ServiceRegisterListener srl) {
        mServiceRegisterListener = srl;
    }

    public void stopFileServer() {
        FileServer.getInstance(mContext, Tor.getFileServerPort(), false).stop();
    }

    String handle(String request) throws Exception {
        if (BuildConfig.DEBUG) log("accept");

        FileServer.getInstance(mContext, Tor.getFileServerPort(), false).startServer();

        Tor tor = Tor.getInstance(mContext);
        Notifier notifier = Notifier.getInstance(mContext);

        log("request " + request);

        String[] tokens = request.split(" ");
        if (tokens.length == 0)
            return "";

        log("toks " + tokens.length);
        if ("add".equals(tokens[0]) && tokens.length == 2) {
            String op = tokens[0];
            String content = new String(Util.base64decode(tokens[1]));
            Gson gson = new Gson();
            TorData td = gson.fromJson(content, TorData.class);

            if (!td.getReceiver().equals(tor.getID())) {
                log("message wrong address");
                return "" + CODE_WRONG_ADDRESS;
            }

            String sender = td.getSender();
            String pubKeySpec = td.getPubKeySpec();
            String signature = td.getSignature();
            log("add target ok");
            if (!tor.checkSig(
                    sender,
                    Util.base64decode(pubKeySpec),
                    Util.base64decode(signature),
                    (op + " " + sender + " " + td.getData()).getBytes(StandardCharsets.UTF_8))) {
                log("add invalid signature");
                return "" + CODE_INVALID_SIGNATURE;
            }
            log("add signature ok");
            if (td.getDataType() == TorData.TYPE_REQUEST) {
                String data = td.getData();
                TorRequest tr = gson.fromJson(data, TorRequest.class);
                // Requests page has been removed: accept silently so key exchange still works.
                Contact.addContact(
                        mContext,
                        sender,
                        tr.getSenderName(),
                        tr.getDescription(),
                        Util.base64decode(pubKeySpec),
                        false,
                        false
                );
                for (Listener l : mListeners) {
                    if (l != null) l.onChange();
                }
                log("add ok (silently accepted)");
            } else {
                log("Not a request message");
            }
            return "" + Util.base64encode(tor.getPubKeySpec());
        }
        if ("msg".equals(tokens[0]) && tokens.length == 2) {
            Gson gson = new Gson();
            String content = new String(Util.base64decode(tokens[1]), StandardCharsets.UTF_8);
            log("Content: " + content);
            TorData td = gson.fromJson(content, TorData.class);
            String sender = td.getSender();
            Realm realm = Realm.getDefaultInstance();
            try {
                Contact contact = realm.where(Contact.class).equalTo("address", sender).findFirst();
                if (contact == null) {
                    // Requests page is removed: for chatrooms and general usability,
                    // accept messages from unknown senders if they provide a pubKeySpec.
                    String pubKeySpec = td.getPubKeySpec();
                    if (pubKeySpec == null || pubKeySpec.isEmpty()) {
                        log("Contact not found with " + sender);
                        return "" + CODE_CONTACT_NOT_FOUND;
                    }
                    Contact.addContact(
                            mContext,
                            sender,
                            "",
                            "",
                            Util.base64decode(pubKeySpec),
                            false,
                            false
                    );
                    contact = realm.where(Contact.class).equalTo("address", sender).findFirst();
                }
                byte[] pubKey = contact.getPubKey();
                String signature = td.getSignature();

            if (!td.getReceiver().equals(tor.getID())) {
                log("message wrong address");
                return "" + CODE_WRONG_ADDRESS;
            }

            log("message address ok");
            if (!tor.checkSig(
                    td.getSender(),
                    pubKey,
                    Util.base64decode(signature),
                    ("msg " + td.getData()).getBytes(StandardCharsets.UTF_8))) {
                log("message invalid signature");
                return "" + CODE_INVALID_SIGNATURE;
            }
                log("message signature ok");

            for (Listener l : mListeners) {
                if (l != null) l.onChange();
            }

                log("Trying to decrypt key: " + td.getSecretKey());
                String key = tor.decryptByPrivateKey(td.getSecretKey());
                AdvancedCrypto advancedCrypto = new AdvancedCrypto(key);
                String data = advancedCrypto.decrypt(td.getData());
                log("Decrypted Data: " + data);
                Message message = gson.fromJson(data, Message.class);

                // If this message carries an E2EE attachment, convert the media key to a local-wrapped form
                // immediately (while the per-message key is available). This enables restore/offline decrypt.
                if (message != null && message.getFileShare() != null) {
                    FileShare fs = message.getFileShare();
                    if (fs.getMediaId() != null && !fs.getMediaId().trim().isEmpty()) {
                        byte[] transportWrapped = fs.getEncryptedMediaKey();
                        if (transportWrapped == null || transportWrapped.length == 0) {
                            throw new GeneralSecurityException("E2EE attachment missing encryptedMediaKey");
                        }
                        byte[] mediaKey = MediaAttachmentCrypto.unwrapMediaKeyFromTransport(transportWrapped, key, message, fs);
                        byte[] deviceWrapped = MediaAttachmentCrypto.wrapMediaKeyForDevice(mediaKey, tor);
                        fs.setEncryptedMediaKey(deviceWrapped);
                        if (fs.getMediaAEAD() == null || fs.getMediaAEAD().trim().isEmpty()) {
                            fs.setMediaAEAD(MediaAttachmentCrypto.AEAD_XCHACHA20_POLY1305);
                        }
                    }
                }

                // Chatrooms: keep membership in sync via room system messages.
                if (message != null) {
                    RoomMsg parsed = parseRoomMsg(message.getContent());

                    // Preferred path: roommsg:<roomId>:<roomMessageId>:<type>:<base64>
                    if (parsed != null) {
                        final String roomId = parsed.roomId;
                        final String systemType = parsed.systemType;
                        final String systemPayload = parsed.payload;

                        // Transport-level ACK: mark the matching outgoing message delivered and do not store/notify.
                        if ("ACK".equals(systemType)) {
                            final String myId = tor.getID();
                            final String prefix = "roommsg:" + roomId + ":" + parsed.roomMessageId + ":";
                            realm.executeTransaction(r -> {
                                RealmResults<Message> outs = r.where(Message.class)
                                        .equalTo("sender", myId)
                                        .equalTo("receiver", sender)
                                        .equalTo("pending", 1)
                                        .beginsWith("content", prefix)
                                        .findAll();
                                for (Message m : outs) {
                                    m.setPending(0);
                                }
                            });
                            return "" + CODE_DATA_RECEIVED;
                        }

                        realm.executeTransaction(r -> {
                            ChatRoom room = r.where(ChatRoom.class).equalTo("id", roomId).findFirst();
                            if (room == null) {
                                ChatRoom created = r.createObject(ChatRoom.class, roomId);
                                created.setName("");
                                created.setCreatedAt(System.currentTimeMillis());
                                room = created;
                            }

                            if ("JOIN".equals(systemType)) {
                                final String alias = normalizeAliasToken(systemPayload);
                                final String senderRaw = sender == null ? "" : sender;
                                final String senderRawLower = senderRaw.trim().toLowerCase(Locale.US);
                                final String senderNorm = normalizeOnionId(senderRaw);

                                String pkNorm = ChatRoomMember.makePrimaryKey(roomId, senderNorm);
                                ArrayList<ChatRoomMember> candidates = findRoomMemberCandidates(r, roomId, senderNorm);

                                ChatRoomMember canonical = r.where(ChatRoomMember.class)
                                        .equalTo("primaryKey", pkNorm)
                                        .findFirst();
                                boolean created = false;
                                if (canonical == null) {
                                    canonical = r.createObject(ChatRoomMember.class, pkNorm);
                                    canonical.setRoomId(roomId);
                                    canonical.setAddress(senderNorm);
                                    canonical.setAlias("");
                                    created = true;
                                } else {
                                    if (canonical.getRoomId() == null || canonical.getRoomId().isEmpty()) {
                                        canonical.setRoomId(roomId);
                                    }
                                    String currentAddr = canonical.getAddress();
                                    if (currentAddr == null || !normalizeOnionId(currentAddr).equals(senderNorm)) {
                                        canonical.setAddress(senderNorm);
                                    }
                                }

                                String bestAlias = normalizeAliasToken(canonical.getAlias());
                                boolean bestBlank = bestAlias.isEmpty();
                                boolean bestPlaceholder = !bestBlank && looksLikeOnionPlaceholder(bestAlias, senderNorm);
                                boolean bestEncrypted = !bestBlank && looksLikeKeyMaterialOrEncryptedName(bestAlias);

                                if (bestBlank || bestPlaceholder || bestEncrypted) {
                                    for (ChatRoomMember m : candidates) {
                                        if (m == null) continue;
                                        String otherAlias = normalizeAliasToken(m.getAlias());
                                        if (!otherAlias.isEmpty()
                                                && !looksLikeOnionPlaceholder(otherAlias, senderNorm)
                                                && !looksLikeKeyMaterialOrEncryptedName(otherAlias)) {
                                            bestAlias = otherAlias;
                                            bestBlank = false;
                                            bestPlaceholder = false;
                                            bestEncrypted = false;
                                            break;
                                        }
                                    }
                                }

                                if (!alias.isEmpty() && (bestBlank || bestPlaceholder || bestEncrypted)) {
                                    bestAlias = alias;
                                }

                                if (!bestAlias.equals((canonical.getAlias() == null ? "" : canonical.getAlias()))) {
                                    canonical.setAlias(bestAlias);
                                }

                                int deleted = 0;
                                for (ChatRoomMember m : candidates) {
                                    if (m == null) continue;
                                    String pk = m.getPrimaryKey();
                                    if (pk == null) continue;
                                    if (!pkNorm.equals(pk)) {
                                        m.deleteFromRealm();
                                        deleted++;
                                    }
                                }

                                String senderPrefixJoin = senderNorm.substring(0, Math.min(8, senderNorm.length()));
                                if (created) {
                                    log("room JOIN member created/migrated for " + senderPrefixJoin + " alias='" + alias + "'");
                                }
                                if (deleted > 0) {
                                    log("room JOIN merged " + deleted + " duplicate member row(s) for " + senderPrefixJoin);
                                }
                                if (!alias.isEmpty() && bestAlias.equals(alias)) {
                                    log("room JOIN alias set for " + senderPrefixJoin + " -> '" + alias + "'");
                                }

                                // Also backfill the sender's Contact name for UI fallbacks.
                                if (!alias.isEmpty() && looksLikeHumanAlias(alias)) {
                                    Contact senderContact = r.where(Contact.class)
                                            .beginGroup()
                                            .equalTo("address", senderNorm)
                                            .or()
                                            .equalTo("address", senderRawLower)
                                            .endGroup()
                                            .findFirst();
                                    if (senderContact != null) {
                                        String existing = senderContact.getName();
                                        String existingTrimmed = existing == null ? "" : existing.trim();
                                        boolean existingBlank = existingTrimmed.isEmpty();
                                        boolean existingPlaceholder = !existingBlank && looksLikeOnionPlaceholder(existingTrimmed, senderNorm);
                                        boolean existingEncrypted = !existingBlank && looksLikeKeyMaterialOrEncryptedName(existingTrimmed);
                                        if (existingBlank || existingPlaceholder || existingEncrypted) {
                                            senderContact.setName(alias);
                                            String senderPrefix = senderNorm.substring(0, Math.min(8, senderNorm.length()));
                                            log("room JOIN backfilled contact name for " + senderPrefix + " -> '" + alias + "'");
                                        }
                                    }
                                }
                            }
                        });
                    } else if (message.getRoomId() != null && !message.getRoomId().isEmpty()) {
                        // Secondary path: newer schema fields present.
                        final String roomId = message.getRoomId();
                        final String systemType = message.getRoomSystemType();
                        final String systemPayload = message.getContent();

                        if ("ACK".equals(systemType)) {
                            final String myId = tor.getID();
                            final String prefix = "roommsg:" + roomId + ":" + message.getRoomMessageId() + ":";
                            realm.executeTransaction(r -> {
                                RealmResults<Message> outs = r.where(Message.class)
                                        .equalTo("sender", myId)
                                        .equalTo("receiver", sender)
                                        .equalTo("pending", 1)
                                        .beginsWith("content", prefix)
                                        .findAll();
                                for (Message m : outs) {
                                    m.setPending(0);
                                }
                            });
                            return "" + CODE_DATA_RECEIVED;
                        }

                        realm.executeTransaction(r -> {
                            ChatRoom room = r.where(ChatRoom.class).equalTo("id", roomId).findFirst();
                            if (room == null) {
                                ChatRoom created = r.createObject(ChatRoom.class, roomId);
                                created.setName("");
                                created.setCreatedAt(System.currentTimeMillis());
                                room = created;
                            }

                            if ("JOIN".equals(systemType)) {
                                final String alias = normalizeAliasToken(systemPayload);
                                final String senderRaw = sender == null ? "" : sender;
                                final String senderRawLower = senderRaw.trim().toLowerCase(Locale.US);
                                final String senderNorm = normalizeOnionId(senderRaw);

                                String pkNorm = ChatRoomMember.makePrimaryKey(roomId, senderNorm);
                                ArrayList<ChatRoomMember> candidates = findRoomMemberCandidates(r, roomId, senderNorm);

                                ChatRoomMember canonical = r.where(ChatRoomMember.class)
                                        .equalTo("primaryKey", pkNorm)
                                        .findFirst();
                                boolean created = false;
                                if (canonical == null) {
                                    canonical = r.createObject(ChatRoomMember.class, pkNorm);
                                    canonical.setRoomId(roomId);
                                    canonical.setAddress(senderNorm);
                                    canonical.setAlias("");
                                    created = true;
                                } else {
                                    if (canonical.getRoomId() == null || canonical.getRoomId().isEmpty()) {
                                        canonical.setRoomId(roomId);
                                    }
                                    String currentAddr = canonical.getAddress();
                                    if (currentAddr == null || !normalizeOnionId(currentAddr).equals(senderNorm)) {
                                        canonical.setAddress(senderNorm);
                                    }
                                }

                                String bestAlias = normalizeAliasToken(canonical.getAlias());
                                boolean bestBlank = bestAlias.isEmpty();
                                boolean bestPlaceholder = !bestBlank && looksLikeOnionPlaceholder(bestAlias, senderNorm);
                                boolean bestEncrypted = !bestBlank && looksLikeKeyMaterialOrEncryptedName(bestAlias);

                                if (bestBlank || bestPlaceholder || bestEncrypted) {
                                    for (ChatRoomMember m : candidates) {
                                        if (m == null) continue;
                                        String otherAlias = normalizeAliasToken(m.getAlias());
                                        if (!otherAlias.isEmpty()
                                                && !looksLikeOnionPlaceholder(otherAlias, senderNorm)
                                                && !looksLikeKeyMaterialOrEncryptedName(otherAlias)) {
                                            bestAlias = otherAlias;
                                            bestBlank = false;
                                            bestPlaceholder = false;
                                            bestEncrypted = false;
                                            break;
                                        }
                                    }
                                }

                                if (!alias.isEmpty() && (bestBlank || bestPlaceholder || bestEncrypted)) {
                                    bestAlias = alias;
                                }

                                if (!bestAlias.equals((canonical.getAlias() == null ? "" : canonical.getAlias()))) {
                                    canonical.setAlias(bestAlias);
                                }

                                int deleted = 0;
                                for (ChatRoomMember m : candidates) {
                                    if (m == null) continue;
                                    String pk = m.getPrimaryKey();
                                    if (pk == null) continue;
                                    if (!pkNorm.equals(pk)) {
                                        m.deleteFromRealm();
                                        deleted++;
                                    }
                                }

                                String senderPrefixJoin = senderNorm.substring(0, Math.min(8, senderNorm.length()));
                                if (created) {
                                    log("room JOIN (schema) member created/migrated for " + senderPrefixJoin + " alias='" + alias + "'");
                                }
                                if (deleted > 0) {
                                    log("room JOIN (schema) merged " + deleted + " duplicate member row(s) for " + senderPrefixJoin);
                                }
                                if (!alias.isEmpty() && bestAlias.equals(alias)) {
                                    log("room JOIN (schema) alias set for " + senderPrefixJoin + " -> '" + alias + "'");
                                }

                                if (!alias.isEmpty() && looksLikeHumanAlias(alias)) {
                                    Contact senderContact = r.where(Contact.class)
                                            .beginGroup()
                                            .equalTo("address", senderNorm)
                                            .or()
                                            .equalTo("address", senderRawLower)
                                            .endGroup()
                                            .findFirst();
                                    if (senderContact != null) {
                                        String existing = senderContact.getName();
                                        String existingTrimmed = existing == null ? "" : existing.trim();
                                        boolean existingBlank = existingTrimmed.isEmpty();
                                        boolean existingPlaceholder = !existingBlank && looksLikeOnionPlaceholder(existingTrimmed, senderNorm);
                                        boolean existingEncrypted = !existingBlank && looksLikeKeyMaterialOrEncryptedName(existingTrimmed);
                                        if (existingBlank || existingPlaceholder || existingEncrypted) {
                                            senderContact.setName(alias);
                                            String senderPrefix = senderNorm.substring(0, Math.min(8, senderNorm.length()));
                                            log("room JOIN (schema) backfilled contact name for " + senderPrefix + " -> '" + alias + "'");
                                        }
                                    }
                                }
                            }
                        });
                    }
                }

                // Room system frames like JOIN are state sync; don't store/notify them as chat messages.
                if (message != null) {
                    RoomMsg parsed = parseRoomMsg(message.getContent());
                    if (parsed != null && "JOIN".equals(parsed.systemType)) {
                        log("room JOIN received; not storing/notifying");
                        return "" + CODE_DATA_RECEIVED;
                    }
                    if (message.getRoomId() != null && !message.getRoomId().isEmpty()) {
                        String st = message.getRoomSystemType();
                        if (st != null && "JOIN".equals(st)) {
                            log("room JOIN (schema) received; not storing/notifying");
                            return "" + CODE_DATA_RECEIVED;
                        }
                    }
                }

                boolean stored = false;
                if (message != null) {
                    stored = Message.addUnreadIncomingMessage(mContext, message);
                }

                // For normal room messages (non-system), send an ACK back so the sender can stop retrying.
                if (message != null) {
                    RoomMsg parsed = parseRoomMsg(message.getContent());
                    if (parsed != null) {
                        String systemType = parsed.systemType;
                        if (systemType == null || systemType.isEmpty() || "-".equals(systemType)) {
                            String ack = encodeRoomMsg(parsed.roomId, parsed.roomMessageId, "ACK", "");
                            Message.addPendingOutgoingMessage(tor.getID(), sender, ack, null, null, null);
                            Client.getInstance(mContext).startSendPendingMessages(sender);
                        }
                    }
                }

                if (stored && message != null) {
                    String roomId = message.getRoomId();
                    String chatId = (roomId != null && !roomId.isEmpty()) ? roomId : message.getSender();
                    notifier.onIncomingChatMessage(chatId);
                }
                log("add ok");
                return "" + CODE_DATA_RECEIVED;
            } finally {
                realm.close();
            }
        }
        if ("newmsg".equals(tokens[0]) && tokens.length == 6) {
            String op = tokens[0];
            String receiver = tokens[1];
            String sender = tokens[2];
            String timestr = tokens[3];
            String pubkey = tokens[4];
            String signature = tokens[5];
            if (!receiver.equals(tor.getID())) {
                log("message wrong address");
                return "" + CODE_WRONG_ADDRESS;
            }
            log("message address ok");
            if (Long.parseLong(timestr) > System.currentTimeMillis()) {
                log("wrong timestamp, future");
                return "" + CODE_WRONG_TIMESTAMP;
            }
            if (Long.parseLong(timestr) + 150000 < System.currentTimeMillis()) {
                log("wrong timestamp, timed out");
                return "" + CODE_WRONG_TIMESTAMP;
            }
            if (!tor.checkSig(
                    sender,
                    Util.base64decode(pubkey),
                    Util.base64decode(signature),
                    (op + " " + receiver + " " + sender + " " + timestr).getBytes(StandardCharsets.UTF_8))) {
                log("message invalid signature");
                return "" + CODE_INVALID_SIGNATURE;
            }
            Client.getInstance(mContext).startSendPendingMessages(sender);
            return "" + CODE_DATA_RECEIVED;
        }
        return "" + CODE_UNKNOWN;
    }

    public void downloadFile(Message message) {
        File mediaFileDir = new File(mContext.getFilesDir(), message.getSender());
        File file = new File(mediaFileDir, message.getFileShare().getFilename());
        if (!file.exists()) {
            if (!hasActiveDownloadTaskForMessage(message.getPrimaryKey())) {
                FileShare fs = message.getFileShare();
                boolean isE2ee = fs != null && fs.getMediaId() != null && !fs.getMediaId().trim().isEmpty();
                boolean isChunked = isE2ee && fs.isChunked();
                if (isChunked) {
                    startChunkedDownloadIfNeeded(message.getPrimaryKey(), true);
                    return;
                }

                String url = Message.getDownloadUrl(message);
                String downloadPath;
                boolean isPathDir;
                if (isE2ee) {
                    // Phase 2: download ciphertext to a temp file; decrypt to the final path on completion.
                    File tmpCipher = new File(mediaFileDir, fs.getFilename() + ".enc");
                    downloadPath = tmpCipher.getAbsolutePath();
                    isPathDir = false;
                } else {
                    downloadPath = mediaFileDir.toString();
                    isPathDir = true;
                }

                BaseDownloadTask dt = FileDownloader.getImpl()
                        .create(url)
                        .setPath(downloadPath, isPathDir)
                        .addHeader("password", fs.getPassword())
                        .setCallbackProgressTimes(300)
                        .setAutoRetryTimes(3)
                        .setMinIntervalUpdateSpeed(2000)
                        .setTag(message.getPrimaryKey())
                        .setListener(fileDownloadListener);

                synchronized (mDownloadTasks) {
                    mDownloadTasks.put(message.getPrimaryKey(), dt);
                }
                mDownloadProgress.put(message.getPrimaryKey(), -1);
                int dtid = dt.start();
                Log.d(TAG, "onBindViewHolder: Download started for ID: " + dtid);
            }
        }
    }

    private static String encodeRoomMsg(String roomId, String roomMessageId, String systemType, String text) {
        String typeToken = (systemType == null || systemType.isEmpty()) ? "-" : systemType;
        String payload = text == null ? "" : text;
        String encoded = Base64.encodeToString(payload.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        return "roommsg:" + roomId + ":" + roomMessageId + ":" + typeToken + ":" + encoded;
    }

    public com.liulishuo.filedownloader.FileDownloadListener fileDownloadListener = new com.liulishuo.filedownloader.FileDownloadListener() {
        @Override
        protected void pending(BaseDownloadTask task, int soFarBytes, int totalBytes) {
            Log.d(TAG, "pending: " + task.getId());
            String tag = (String) task.getTag();
            String messageId = extractMessageId(tag);
            if (mFileDownloadListener != null) {
                mFileDownloadListener.onDownloadProgressChange(messageId);
            }
        }

        @Override
        protected void progress(BaseDownloadTask task, int soFarBytes, int totalBytes) {
            String tag = (String) task.getTag();
            String messageId = extractMessageId(tag);

            int progress;
            if (isChunkedTaskTag(tag)) {
                progress = computeChunkedProgressPercent(messageId);
            } else {
                progress = (int) (((double) soFarBytes / (double) totalBytes) * 100.f);
            }
            Log.d(TAG, "progress: " + progress);
            int oldProgress = -1;
            if (mDownloadProgress.containsKey(messageId)) {
                oldProgress = mDownloadProgress.get(messageId);
            }
            mDownloadProgress.put(messageId, progress);
            if (mFileDownloadListener != null && oldProgress != progress) {
                mFileDownloadListener.onDownloadProgressChange(messageId);
            }
        }

        @Override
        protected void completed(BaseDownloadTask task) {
            Log.d(TAG, "completed: " + task.getId());
            String tag = (String) task.getTag();
            String messageId = extractMessageId(tag);
            synchronized (mDownloadTasks) {
                mDownloadTasks.remove(tag);
                // Backward compatibility: legacy tasks use messageId as the key.
                mDownloadTasks.remove(messageId);
            }

            boolean ok = true;
            boolean threw = false;
            try {
                ok = handleDownloadCompleted(tag);
            } catch (Throwable t) {
                ok = false;
                threw = true;
                Log.e(TAG, "download decrypt failed", t);
            }

            if (isChunkedTaskTag(tag)) {
                if (threw) {
                    onChunkedTaskFailed(messageId);
                } else {
                    onChunkedTaskCompleted(messageId, ok);
                }
            }

            if (ok) {
                Message.updateDownloadStatus(messageId, true);
                mDownloadProgress.put(messageId, 100);
            }
            if (mFileDownloadListener != null) {
                mFileDownloadListener.onDownloadProgressChange(messageId);
            }
        }

        @Override
        protected void paused(BaseDownloadTask task, int soFarBytes, int totalBytes) {
            Log.d(TAG, "paused: " + task.getId());
            String tag = (String) task.getTag();
            String messageId = extractMessageId(tag);
            synchronized (mDownloadTasks) {
                mDownloadTasks.remove(tag);
                mDownloadTasks.remove(messageId);
            }
            if (isChunkedTaskTag(tag)) {
                onChunkedTaskFailed(messageId);
            }
            if (mFileDownloadListener != null) {
                mFileDownloadListener.onDownloadProgressChange(messageId);
            }
        }

        @Override
        protected void error(BaseDownloadTask task, Throwable e) {
            Log.d(TAG, "error: " + task.getId() + " " + e.getLocalizedMessage());
            e.printStackTrace();
            String tag = (String) task.getTag();
            String messageId = extractMessageId(tag);
            synchronized (mDownloadTasks) {
                mDownloadTasks.remove(tag);
                mDownloadTasks.remove(messageId);
            }
            if (!isChunkedTaskTag(tag)) {
                mDownloadProgress.remove(messageId);
            }
            markDownloadFailed(messageId);
            if (isChunkedTaskTag(tag)) {
                onChunkedTaskFailed(messageId);
            }
            if (mFileDownloadListener != null) {
                mFileDownloadListener.onDownloadProgressChange(messageId);
            }
        }

        @Override
        protected void warn(BaseDownloadTask task) {
            Log.d(TAG, "warn: " + task.getId());
        }
    };

    private boolean handleDownloadCompleted(String taskTag) throws Exception {
        String messageId = extractMessageId(taskTag);
        if (messageId == null || messageId.trim().isEmpty()) return false;

        if (!isChunkedTaskTag(taskTag)) {
            return handleLegacyDownloadCompleted(messageId);
        }

        return handleChunkedDownloadCompleted(taskTag, messageId);
    }

    private boolean handleChunkedDownloadCompleted(String taskTag, String messageId) throws Exception {

        Realm realm = Realm.getDefaultInstance();
        try {
            Message m = realm.where(Message.class).equalTo("primaryKey", messageId).findFirst();
            if (m == null || m.getFileShare() == null) {
                return false;
            }

            FileShare fs = m.getFileShare();
            if (fs.getMediaId() == null || fs.getMediaId().trim().isEmpty() || !fs.isChunked()) {
                // Not a chunked E2EE attachment.
                return false;
            }

            if (fs.getPlaintextSha256() == null || fs.getPlaintextSha256().length != 32) {
                throw new GeneralSecurityException("missing_plaintext_hash");
            }
            if (fs.getChunkSize() <= 0 || fs.getTotalChunks() <= 0) {
                throw new GeneralSecurityException("missing_chunk_params");
            }

            byte[] mediaKey = MediaAttachmentCrypto.unwrapMediaKeyFromDevice(fs.getEncryptedMediaKey(), Tor.getInstance(mContext));

            String[] parts = taskTag.split("\\|");
            String kind = parts.length >= 2 ? parts[1] : "";

            if ("manifest".equals(kind)) {
                File tmp = MediaAttachmentCrypto.chunkedManifestTempDownloadFile(mContext, fs.getMediaId());
                if (!tmp.exists()) throw new IOException("manifest_tmp_missing");

                // Phase 3.5: idempotent replay/dup defense.
                // If already verified, avoid re-validating attacker-controlled ciphertext.
                if (fs.isManifestVerified()) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    touchChunkedLastAccess(messageId);
                    return false;
                }

                // Phase 3.5: cap validation / bitmap amplification defense.
                ChunkedMediaLimits.validateOrThrow(fs.getFileSize(), fs.getChunkSize(), fs.getTotalChunks());

                // Validate manifest ciphertext and fields.
                MediaAttachmentCrypto.decryptAndValidateManifest(
                        tmp,
                        mediaKey,
                        fs.getMediaId(),
                        fs.getTotalChunks(),
                        fs.getFileSize(),
                        fs.getChunkSize(),
                        fs.getPlaintextSha256());

                File dst = MediaAttachmentCrypto.chunkedManifestFileForServing(mContext, fs.getMediaId());
                //noinspection ResultOfMethodCallIgnored
                dst.getParentFile().mkdirs();
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
                if (!tmp.renameTo(dst)) {
                    moveReplace(tmp, dst);
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }

                realm.executeTransaction(r -> {
                    Message mm = r.where(Message.class).equalTo("primaryKey", messageId).findFirst();
                    if (mm != null && mm.getFileShare() != null) {
                        FileShare fss = mm.getFileShare();
                        fss.setManifestVerified(true);
                        fss.setChunkBitmap(ensureBitmapSize(fss.getChunkBitmap(), fss.getTotalChunks()));
                        fss.setChunkedLastAccessMs(System.currentTimeMillis());
                        fss.setChunkedEvictedAtMs(0);
                        fss.setChunkedEvictReason("");
                    }
                });

                return false;
            }

            if ("chunk".equals(kind) && parts.length >= 3) {
                int chunkIndex = Integer.parseInt(parts[2]);
                File tmp = MediaAttachmentCrypto.chunkedChunkTempDownloadFile(mContext, fs.getMediaId(), chunkIndex);
                if (!tmp.exists()) throw new IOException("chunk_tmp_missing");

                // Phase 3.5: cap validation / bitmap amplification defense.
                ChunkedMediaLimits.validateOrThrow(fs.getFileSize(), fs.getChunkSize(), fs.getTotalChunks());

                // Phase 3.5: idempotent replay/dup defense.
                // If already verified and stored, avoid re-validating attacker-controlled ciphertext.
                byte[] existingBitmap = ensureBitmapSize(fs.getChunkBitmap(), fs.getTotalChunks());
                boolean already = isBitSet(existingBitmap, chunkIndex);
                File existingCipher = MediaAttachmentCrypto.chunkedChunkFileForServing(mContext, fs.getMediaId(), chunkIndex);
                if (already && existingCipher.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    touchChunkedLastAccess(messageId);
                    return false;
                }

                int expectedPlainLen = expectedChunkPlaintextLen(fs.getFileSize(), fs.getChunkSize(), fs.getTotalChunks(), chunkIndex);
                MediaAttachmentCrypto.validateChunkCiphertext(
                        tmp,
                        mediaKey,
                        fs.getMediaId(),
                        chunkIndex,
                        fs.getTotalChunks(),
                        fs.getPlaintextSha256(),
                        expectedPlainLen);

                // Move temp into place unless already verified.
                File dst = MediaAttachmentCrypto.chunkedChunkFileForServing(mContext, fs.getMediaId(), chunkIndex);
                //noinspection ResultOfMethodCallIgnored
                dst.getParentFile().mkdirs();
                if (!already) {
                    //noinspection ResultOfMethodCallIgnored
                    dst.delete();
                    if (!tmp.renameTo(dst)) {
                        moveReplace(tmp, dst);
                        //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                    }
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }

                // Update chunk bitmap (in-memory and in DB).
                byte[] newBitmap = ensureBitmapSize(fs.getChunkBitmap(), fs.getTotalChunks());
                setBit(newBitmap, chunkIndex);
                realm.executeTransaction(r -> {
                    Message mm = r.where(Message.class).equalTo("primaryKey", messageId).findFirst();
                    if (mm != null && mm.getFileShare() != null) {
                        mm.getFileShare().setChunkBitmap(newBitmap);
                        mm.getFileShare().setChunkedLastAccessMs(System.currentTimeMillis());
                        mm.getFileShare().setChunkedEvictedAtMs(0);
                        mm.getFileShare().setChunkedEvictReason("");
                    }
                });

                // If all chunks are present, assemble into final plaintext.
                if (isAllChunksVerified(newBitmap, fs.getTotalChunks())) {
                    boolean assembled = assembleChunkedAttachment(m, fs, mediaKey);
                    if (assembled) {
                        realm.executeTransaction(r -> {
                            Message mm = r.where(Message.class).equalTo("primaryKey", messageId).findFirst();
                            if (mm != null && mm.getFileShare() != null) {
                                mm.getFileShare().setDownloadTried(true);
                                mm.getFileShare().setDownloaded(true);
                                mm.getFileShare().setChunkBitmap(null);
                            }
                        });
                        return true;
                    }
                    return false;
                }

                return false;
            }

            throw new IllegalArgumentException("unknown_chunked_task_tag");
        } catch (RuntimeException e) {
            markDownloadFailed(messageId);
            throw e;
        } catch (Exception e) {
            // Best-effort cleanup and mark failed.
            markDownloadFailed(messageId);
            throw e;
        } finally {
            realm.close();
        }
    }

    private boolean handleLegacyDownloadCompleted(String messageId) throws Exception {
        if (messageId == null || messageId.trim().isEmpty()) return false;

        Realm realm = Realm.getDefaultInstance();
        try {
            Message m = realm.where(Message.class).equalTo("primaryKey", messageId).findFirst();
            if (m == null || m.getFileShare() == null) {
                return false;
            }

            FileShare fs = m.getFileShare();
            boolean isE2ee = fs.getMediaId() != null && !fs.getMediaId().trim().isEmpty();
            if (!isE2ee) {
                return true;
            }

            File mediaFileDir = new File(mContext.getFilesDir(), m.getSender());
            File tmpCipher = new File(mediaFileDir, fs.getFilename() + ".enc");
            File outPlain = new File(mediaFileDir, fs.getFilename());
            File tmpPlain = new File(mediaFileDir, fs.getFilename() + ".dec");

            // Retry-safe fast-path: if plaintext already exists and matches the expected hash,
            // treat as success and clean up any leftover ciphertext temp.
            if (outPlain.exists()) {
                if (fs.getPlaintextSha256() != null && fs.getPlaintextSha256().length == 32) {
                    byte[] actualExisting = MediaAttachmentCrypto.sha256File(outPlain);
                    if (java.util.Arrays.equals(actualExisting, fs.getPlaintextSha256())) {
                        //noinspection ResultOfMethodCallIgnored
                        tmpCipher.delete();
                        realm.executeTransaction(r -> {
                            Message mm = r.where(Message.class).equalTo("primaryKey", messageId).findFirst();
                            if (mm != null && mm.getFileShare() != null) {
                                mm.getFileShare().setDownloadTried(true);
                                mm.getFileShare().setDownloaded(true);
                            }
                        });
                        return true;
                    } else {
                        // Stale/incorrect plaintext: remove and re-decrypt.
                        //noinspection ResultOfMethodCallIgnored
                        outPlain.delete();
                    }
                } else if (fs.isDownloaded()) {
                    // Backward compatibility: Phase 1 attachments didn't bind a plaintext hash.
                    // If previously marked downloaded and file exists, assume it is correct.
                    //noinspection ResultOfMethodCallIgnored
                    tmpCipher.delete();
                    return true;
                }
            }

            // Safety: require ciphertext temp and do not leave partially-decrypted plaintext behind on failure.
            if (!tmpCipher.exists()) {
                throw new IOException("ciphertext temp missing");
            }

            // Ensure no stale temp plaintext remains.
            //noinspection ResultOfMethodCallIgnored
            tmpPlain.delete();

            byte[] mediaKey = MediaAttachmentCrypto.unwrapMediaKeyFromDevice(fs.getEncryptedMediaKey(), Tor.getInstance(mContext));
            byte[] aad;
            if (fs.getPlaintextSha256() != null && fs.getPlaintextSha256().length == 32) {
                aad = MediaAttachmentCrypto.buildAadV2(fs.getMediaId(), fs.getMimeType(), fs.getFileSize(), fs.getPlaintextSha256());
            } else {
                // Backward compatibility: Phase 1 attachments didn't bind a plaintext hash.
                aad = MediaAttachmentCrypto.buildAad(fs.getMediaId(), fs.getMimeType(), fs.getFileSize());
            }

            // Decrypt to a temp file first, then move into place on success.
            MediaAttachmentCrypto.decryptCiphertextToFile(tmpCipher, tmpPlain, mediaKey, aad);

            // Phase 2 hardening: verify plaintext hash if present.
            if (fs.getPlaintextSha256() != null && fs.getPlaintextSha256().length == 32) {
                byte[] actual = MediaAttachmentCrypto.sha256File(tmpPlain);
                if (!java.util.Arrays.equals(actual, fs.getPlaintextSha256())) {
                    //noinspection ResultOfMethodCallIgnored
                    tmpPlain.delete();
                    throw new GeneralSecurityException("plaintext_hash_mismatch");
                }
            }

            // Replace any existing plaintext (best-effort) and move temp into final location.
            //noinspection ResultOfMethodCallIgnored
            outPlain.delete();
            if (!tmpPlain.renameTo(outPlain)) {
                moveReplace(tmpPlain, outPlain);
                //noinspection ResultOfMethodCallIgnored
                tmpPlain.delete();
            }

            // Delete ciphertext temp after successful decrypt.
            //noinspection ResultOfMethodCallIgnored
            tmpCipher.delete();

            realm.executeTransaction(r -> {
                Message mm = r.where(Message.class).equalTo("primaryKey", messageId).findFirst();
                if (mm != null && mm.getFileShare() != null) {
                    mm.getFileShare().setDownloadTried(true);
                    mm.getFileShare().setDownloaded(true);
                }
            });

            return true;
        } finally {
            realm.close();
        }
    }

    private static String extractMessageId(String taskTag) {
        if (taskTag == null) return null;
        int pipe = taskTag.indexOf('|');
        if (pipe <= 0) return taskTag;
        return taskTag.substring(0, pipe);
    }

    private static boolean isChunkedTaskTag(String taskTag) {
        if (taskTag == null) return false;
        String[] parts = taskTag.split("\\|");
        if (parts.length < 2) return false;
        return "manifest".equals(parts[1]) || "chunk".equals(parts[1]);
    }

    private boolean hasActiveDownloadTaskForMessage(String messageId) {
        if (messageId == null || messageId.trim().isEmpty()) return false;
        synchronized (mDownloadTasks) {
            return mDownloadTasks.containsKey(messageId);
        }
    }

    private void startChunkedDownloadIfNeeded(String messageId) {
        startChunkedDownloadIfNeeded(messageId, false);
    }

    private void startChunkedDownloadIfNeeded(String messageId, boolean userInitiated) {
        if (messageId == null || messageId.trim().isEmpty()) return;

        // Phase 3.5: round-robin fairness across mediaIds + bounded concurrency.
        registerChunkedMessageForScheduling(messageId, userInitiated);
        pumpChunkedDownloads();
    }

    private void onChunkedTaskCompleted(String messageId, boolean isFinalSuccess) {
        String mediaId = lookupChunkedMediaIdForMessage(messageId);
        if (mediaId != null) {
            mChunkedInFlightMediaIds.remove(mediaId);
        }

        if (!isFinalSuccess) {
            // Continue this media under fairness, but only after completion processing finished.
            startChunkedDownloadIfNeeded(messageId);
            return;
        }

        if (mediaId != null) {
            mChunkedMediaQueue.remove(mediaId);
        }
        pumpChunkedDownloads();
    }

    private void onChunkedTaskFailed(String messageId) {
        String mediaId = lookupChunkedMediaIdForMessage(messageId);
        if (mediaId != null) {
            mChunkedInFlightMediaIds.remove(mediaId);
            // Avoid immediate retry loops; user action re-enqueues.
            mChunkedMediaQueue.remove(mediaId);
        }
        pumpChunkedDownloads();
    }

    private String lookupChunkedMediaIdForMessage(String messageId) {
        if (messageId == null || messageId.trim().isEmpty()) return null;
        Realm realm = Realm.getDefaultInstance();
        try {
            Message m = realm.where(Message.class).equalTo("primaryKey", messageId).findFirst();
            if (m == null || m.getFileShare() == null) return null;
            if (!m.getFileShare().isChunked()) return null;
            String mediaId = m.getFileShare().getMediaId();
            if (mediaId == null || mediaId.trim().isEmpty()) return null;
            return mediaId;
        } finally {
            realm.close();
        }
    }

    private void registerChunkedMessageForScheduling(String messageId, boolean userInitiated) {
        Realm realm = Realm.getDefaultInstance();
        try {
            Message m = realm.where(Message.class).equalTo("primaryKey", messageId).findFirst();
            if (m == null || m.getFileShare() == null) return;
            FileShare fs = m.getFileShare();
            if (!fs.isChunked()) return;
            String mediaId = fs.getMediaId();
            if (mediaId == null || mediaId.trim().isEmpty()) return;

            // Explicit user retry clears eviction markers (internal scheduling does not).
            if (userInitiated && fs.getChunkedEvictedAtMs() > 0) {
                realm.executeTransaction(r -> {
                    Message mm = r.where(Message.class).equalTo("primaryKey", messageId).findFirst();
                    if (mm != null && mm.getFileShare() != null) {
                        FileShare fss = mm.getFileShare();
                        fss.setChunkedEvictedAtMs(0);
                        fss.setChunkedEvictReason("");
                    }
                });
            }
            mChunkedMediaQueue.offer(mediaId);
            touchChunkedLastAccess(messageId);
        } finally {
            realm.close();
        }
    }

    private void pumpChunkedDownloads() {
        Realm realm = Realm.getDefaultInstance();
        try {
            enforceChunkedIncompleteEntriesLimit(realm);

            while (mChunkedInFlightMediaIds.size() < ChunkedMediaLimits.MAX_CONCURRENT_CHUNKED_TASKS) {
                String mediaId = mChunkedMediaQueue.next(mChunkedInFlightMediaIds);
                if (mediaId == null) return;

                Message m = realm.where(Message.class)
                        .equalTo("fileShare.mediaId", mediaId)
                        .equalTo("fileShare.chunked", true)
                        .equalTo("fileShare.isDownloaded", false)
                        .findFirst();
                if (m == null || m.getFileShare() == null) {
                    mChunkedMediaQueue.remove(mediaId);
                    continue;
                }

                FileShare fs = m.getFileShare();
                String messageId = m.getPrimaryKey();
                String sender = m.getSender();

                // Phase 3.5: do not auto-resume evicted partial downloads.
                if (fs.getChunkedEvictedAtMs() > 0) {
                    mChunkedMediaQueue.remove(mediaId);
                    continue;
                }

                // If this message already has an active task, don't start another.
                if (hasActiveDownloadTaskForMessage(messageId)) {
                    continue;
                }

                try {
                    if (fs.getPlaintextSha256() == null || fs.getPlaintextSha256().length != 32) {
                        throw new IllegalArgumentException("missing_plaintext_hash");
                    }
                    ChunkedMediaLimits.validateOrThrow(fs.getFileSize(), fs.getChunkSize(), fs.getTotalChunks());
                } catch (RuntimeException cap) {
                    Log.w(TAG, "chunked caps reject: " + cap.getMessage());
                    markChunkedDownloadRejected(messageId, mediaId, cap.getMessage());
                    mChunkedMediaQueue.remove(mediaId);
                    continue;
                }

                String tag;
                String url;
                String path;

                if (!fs.isManifestVerified()) {
                    tag = messageId + "|manifest";
                    url = "https://" + sender + ".onion:" + Tor.getFileServerPort() + "/media/" + mediaId + "/manifest";
                    path = MediaAttachmentCrypto.chunkedManifestTempDownloadFile(mContext, mediaId).getAbsolutePath();
                } else {
                    byte[] bitmap = ensureBitmapSize(fs.getChunkBitmap(), fs.getTotalChunks());
                    int next = findNextMissingChunkIndex(bitmap, fs.getTotalChunks());
                    if (next < 0) {
                        // No more chunks to fetch; attempt to assemble offline.
                        try {
                            byte[] mediaKey = MediaAttachmentCrypto.unwrapMediaKeyFromDevice(fs.getEncryptedMediaKey(), Tor.getInstance(mContext));
                            if (assembleChunkedAttachment(m, fs, mediaKey)) {
                                Message.updateDownloadStatus(messageId, true);
                                mDownloadProgress.put(messageId, 100);
                                mChunkedMediaQueue.remove(mediaId);
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "chunked offline assemble failed", t);
                        }
                        continue;
                    }
                    tag = messageId + "|chunk|" + next;
                    url = "https://" + sender + ".onion:" + Tor.getFileServerPort() + "/media/" + mediaId + "/" + next;
                    path = MediaAttachmentCrypto.chunkedChunkTempDownloadFile(mContext, mediaId, next).getAbsolutePath();
                }

                BaseDownloadTask dt = FileDownloader.getImpl()
                        .create(url)
                        .setPath(path, false)
                        .setCallbackProgressTimes(300)
                        .setAutoRetryTimes(3)
                        .setMinIntervalUpdateSpeed(2000)
                        .setTag(tag)
                        .setListener(fileDownloadListener);

                synchronized (mDownloadTasks) {
                    mDownloadTasks.put(tag, dt);
                    mDownloadTasks.put(messageId, dt);
                }

                mChunkedInFlightMediaIds.add(mediaId);
                touchChunkedLastAccess(messageId);

                if (!mDownloadProgress.containsKey(messageId)) {
                    mDownloadProgress.put(messageId, computeChunkedProgressPercent(messageId));
                }
                dt.start();
            }
        } finally {
            realm.close();
        }
    }

    private void enforceChunkedIncompleteEntriesLimit(Realm realm) {
        if (realm == null) return;

        RealmResults<FileShare> results = realm.where(FileShare.class)
                .equalTo("chunked", true)
                .equalTo("isDownloaded", false)
                .findAll()
                .sort("chunkedLastAccessMs", Sort.ASCENDING);

        int over = results.size() - ChunkedMediaLimits.MAX_INCOMPLETE_ENTRIES;
        if (over <= 0) return;

        long now = System.currentTimeMillis();

        for (int i = 0; i < results.size() && over > 0; i++) {
            FileShare fs = results.get(i);
            if (fs == null) continue;
            String mediaId = fs.getMediaId();
            if (mediaId == null || mediaId.trim().isEmpty()) continue;
            if (mChunkedInFlightMediaIds.contains(mediaId)) continue;

            // Best-effort evict ciphertext artifacts and reset DB state for all rows sharing this mediaId.
            evictChunkedArtifacts(mediaId, fs.getTotalChunks());

            final long evictedAt = now;
            final String reason = "lru_evicted";
            realm.executeTransaction(r -> {
                RealmResults<Message> msgs = r.where(Message.class)
                        .equalTo("fileShare.mediaId", mediaId)
                        .equalTo("fileShare.chunked", true)
                        .findAll();
                for (Message mm : msgs) {
                    if (mm == null || mm.getFileShare() == null) continue;
                    FileShare fss = mm.getFileShare();
                    fss.setManifestVerified(false);
                    fss.setChunkBitmap(null);
                    fss.setDownloadTried(true);
                    fss.setDownloaded(false);
                    fss.setChunkedEvictedAtMs(evictedAt);
                    fss.setChunkedEvictReason(reason);
                    fss.setChunkedLastAccessMs(evictedAt);
                }
            });

            mChunkedMediaQueue.remove(mediaId);
            over--;
        }
    }

    private void markChunkedDownloadRejected(String messageId, String mediaId, String reason) {
        if (messageId == null || messageId.trim().isEmpty()) return;
        if (mediaId == null || mediaId.trim().isEmpty()) return;
        evictChunkedArtifacts(mediaId, -1);

        Realm realm = Realm.getDefaultInstance();
        try {
            long now = System.currentTimeMillis();
            final String r = (reason == null || reason.trim().isEmpty()) ? "rejected" : reason;
            realm.executeTransaction(tx -> {
                Message mm = tx.where(Message.class).equalTo("primaryKey", messageId).findFirst();
                if (mm != null && mm.getFileShare() != null) {
                    FileShare fs = mm.getFileShare();
                    fs.setManifestVerified(false);
                    fs.setChunkBitmap(null);
                    fs.setDownloadTried(true);
                    fs.setDownloaded(false);
                    fs.setChunkedEvictedAtMs(now);
                    fs.setChunkedEvictReason(r);
                    fs.setChunkedLastAccessMs(now);
                }
            });
        } finally {
            realm.close();
        }
    }

    private void touchChunkedLastAccess(String messageId) {
        if (messageId == null || messageId.trim().isEmpty()) return;
        Realm realm = Realm.getDefaultInstance();
        try {
            long now = System.currentTimeMillis();
            realm.executeTransaction(r -> {
                Message mm = r.where(Message.class).equalTo("primaryKey", messageId).findFirst();
                if (mm != null && mm.getFileShare() != null && mm.getFileShare().isChunked()) {
                    mm.getFileShare().setChunkedLastAccessMs(now);
                }
            });
        } finally {
            realm.close();
        }
    }

    private void evictChunkedArtifacts(String mediaId, int totalChunksHint) {
        if (mediaId == null || mediaId.trim().isEmpty()) return;

        //noinspection ResultOfMethodCallIgnored
        MediaAttachmentCrypto.chunkedManifestFileForServing(mContext, mediaId).delete();
        //noinspection ResultOfMethodCallIgnored
        MediaAttachmentCrypto.chunkedManifestTempDownloadFile(mContext, mediaId).delete();

        int limit = totalChunksHint;
        if (limit < 0) {
            // If unknown, use our global cap.
            limit = ChunkedMediaLimits.MAX_TOTAL_CHUNKS;
        }
        for (int i = 0; i < limit; i++) {
            //noinspection ResultOfMethodCallIgnored
            MediaAttachmentCrypto.chunkedChunkFileForServing(mContext, mediaId, i).delete();
            //noinspection ResultOfMethodCallIgnored
            MediaAttachmentCrypto.chunkedChunkTempDownloadFile(mContext, mediaId, i).delete();
        }
    }

    private int computeChunkedProgressPercent(String messageId) {
        if (messageId == null || messageId.trim().isEmpty()) return -1;
        Realm realm = Realm.getDefaultInstance();
        try {
            Message m = realm.where(Message.class).equalTo("primaryKey", messageId).findFirst();
            if (m == null || m.getFileShare() == null) return -1;
            FileShare fs = m.getFileShare();
            if (!fs.isChunked() || fs.getTotalChunks() <= 0) return -1;
            int total = 1 + fs.getTotalChunks();
            int done = (fs.isManifestVerified() ? 1 : 0) + countVerifiedChunks(fs.getChunkBitmap(), fs.getTotalChunks());
            int pct = (int) Math.floor(((double) done / (double) total) * 100.0);
            if (pct < 0) pct = 0;
            if (pct > 99) pct = 99;
            return pct;
        } finally {
            realm.close();
        }
    }

    private static byte[] ensureBitmapSize(byte[] existing, int totalChunks) {
        if (totalChunks <= 0) return new byte[0];
        int need = (totalChunks + 7) / 8;
        if (existing != null && existing.length == need) return existing;
        byte[] out = new byte[need];
        if (existing != null) {
            System.arraycopy(existing, 0, out, 0, Math.min(existing.length, out.length));
        }
        return out;
    }

    private static boolean isBitSet(byte[] bitmap, int index) {
        if (bitmap == null || index < 0) return false;
        int byteIndex = index / 8;
        int bitIndex = index % 8;
        if (byteIndex < 0 || byteIndex >= bitmap.length) return false;
        return (bitmap[byteIndex] & (1 << bitIndex)) != 0;
    }

    private static void setBit(byte[] bitmap, int index) {
        if (bitmap == null || index < 0) return;
        int byteIndex = index / 8;
        int bitIndex = index % 8;
        if (byteIndex < 0 || byteIndex >= bitmap.length) return;
        bitmap[byteIndex] = (byte) (bitmap[byteIndex] | (1 << bitIndex));
    }

    private static int countVerifiedChunks(byte[] bitmap, int totalChunks) {
        if (totalChunks <= 0) return 0;
        if (bitmap == null || bitmap.length == 0) return 0;
        int count = 0;
        for (int i = 0; i < totalChunks; i++) {
            if (isBitSet(bitmap, i)) count++;
        }
        return count;
    }

    private static int findNextMissingChunkIndex(byte[] bitmap, int totalChunks) {
        if (totalChunks <= 0) return -1;
        byte[] bm = ensureBitmapSize(bitmap, totalChunks);
        for (int i = 0; i < totalChunks; i++) {
            if (!isBitSet(bm, i)) return i;
        }
        return -1;
    }

    private static boolean isAllChunksVerified(byte[] bitmap, int totalChunks) {
        return countVerifiedChunks(bitmap, totalChunks) == totalChunks;
    }

    private static int expectedChunkPlaintextLen(long totalPlaintextSize, int chunkSize, int totalChunks, int chunkIndex) {
        if (chunkSize <= 0) throw new IllegalArgumentException("chunkSize <= 0");
        if (totalPlaintextSize < 0) throw new IllegalArgumentException("totalPlaintextSize < 0");
        if (totalChunks <= 0) throw new IllegalArgumentException("totalChunks <= 0");
        if (chunkIndex < 0 || chunkIndex >= totalChunks) throw new IllegalArgumentException("chunkIndex out of range");
        long start = (long) chunkIndex * (long) chunkSize;
        long remaining = totalPlaintextSize - start;
        if (remaining < 0) throw new IllegalArgumentException("chunkIndex beyond plaintext size");
        return (int) Math.min((long) chunkSize, remaining);
    }

    private boolean assembleChunkedAttachment(Message message, FileShare fs, byte[] mediaKey32) throws Exception {
        if (message == null || fs == null) return false;
        if (!fs.isChunked()) return false;
        if (fs.getMediaId() == null || fs.getMediaId().trim().isEmpty()) return false;
        if (fs.getPlaintextSha256() == null || fs.getPlaintextSha256().length != 32) return false;
        if (fs.getTotalChunks() <= 0 || fs.getChunkSize() <= 0) return false;

        byte[] bitmap = ensureBitmapSize(fs.getChunkBitmap(), fs.getTotalChunks());
        if (!isAllChunksVerified(bitmap, fs.getTotalChunks())) return false;

        File mediaFileDir = new File(mContext.getFilesDir(), message.getSender());
        //noinspection ResultOfMethodCallIgnored
        mediaFileDir.mkdirs();
        File outPlain = new File(mediaFileDir, fs.getFilename());
        File tmpPlain = new File(mediaFileDir, fs.getFilename() + ".dec");

        // Ensure no stale temp plaintext remains.
        //noinspection ResultOfMethodCallIgnored
        tmpPlain.delete();

        try (java.io.OutputStream os = new java.io.BufferedOutputStream(new java.io.FileOutputStream(tmpPlain, false))) {
            for (int i = 0; i < fs.getTotalChunks(); i++) {
                File chunkFile = MediaAttachmentCrypto.chunkedChunkFileForServing(mContext, fs.getMediaId(), i);
                if (!chunkFile.exists()) {
                    throw new IOException("missing_chunk_file_" + i);
                }
                MediaAttachmentCrypto.decryptChunkToStream(
                        chunkFile,
                        os,
                        mediaKey32,
                        fs.getMediaId(),
                        i,
                        fs.getTotalChunks(),
                        fs.getPlaintextSha256());
            }
            os.flush();
        }

        byte[] actual = MediaAttachmentCrypto.sha256File(tmpPlain);
        if (!java.util.Arrays.equals(actual, fs.getPlaintextSha256())) {
            //noinspection ResultOfMethodCallIgnored
            tmpPlain.delete();
            throw new GeneralSecurityException("plaintext_hash_mismatch");
        }

        // Replace any existing plaintext and move temp into final location.
        //noinspection ResultOfMethodCallIgnored
        outPlain.delete();
        if (!tmpPlain.renameTo(outPlain)) {
            moveReplace(tmpPlain, outPlain);
            //noinspection ResultOfMethodCallIgnored
            tmpPlain.delete();
        }

        // Best-effort cleanup of chunked ciphertext artifacts after success.
        //noinspection ResultOfMethodCallIgnored
        MediaAttachmentCrypto.chunkedManifestFileForServing(mContext, fs.getMediaId()).delete();
        //noinspection ResultOfMethodCallIgnored
        MediaAttachmentCrypto.chunkedManifestTempDownloadFile(mContext, fs.getMediaId()).delete();
        for (int i = 0; i < fs.getTotalChunks(); i++) {
            //noinspection ResultOfMethodCallIgnored
            MediaAttachmentCrypto.chunkedChunkFileForServing(mContext, fs.getMediaId(), i).delete();
            //noinspection ResultOfMethodCallIgnored
            MediaAttachmentCrypto.chunkedChunkTempDownloadFile(mContext, fs.getMediaId(), i).delete();
        }

        return true;
    }

    private void markDownloadFailed(String messageId) {
        if (messageId == null || messageId.trim().isEmpty()) return;
        Realm realm = Realm.getDefaultInstance();
        try {
            realm.executeTransaction(r -> {
                Message mm = r.where(Message.class).equalTo("primaryKey", messageId).findFirst();
                if (mm != null && mm.getFileShare() != null) {
                    FileShare fs = mm.getFileShare();
                    fs.setDownloadTried(true);
                    fs.setDownloaded(false);

                    // Phase 3.5: if chunked, delete ciphertext artifacts and reset state.
                    if (fs.isChunked() && fs.getMediaId() != null && !fs.getMediaId().trim().isEmpty()) {
                        String mediaId = fs.getMediaId();
                        int totalChunks = fs.getTotalChunks();
                        evictChunkedArtifacts(mediaId, totalChunks);
                        fs.setManifestVerified(false);
                        fs.setChunkBitmap(null);
                        fs.setChunkedEvictReason("failed");
                    }

                    // Try to delete any leftover temp/final file.
                    File mediaFileDir = new File(mContext.getFilesDir(), mm.getSender());
                    File tmpCipher = new File(mediaFileDir, fs.getFilename() + ".enc");
                    File outPlain = new File(mediaFileDir, fs.getFilename());
                    File tmpPlain = new File(mediaFileDir, fs.getFilename() + ".dec");
                    //noinspection ResultOfMethodCallIgnored
                    tmpCipher.delete();
                    //noinspection ResultOfMethodCallIgnored
                    outPlain.delete();
                    //noinspection ResultOfMethodCallIgnored
                    tmpPlain.delete();
                }
            });
        } finally {
            realm.close();
        }
    }

    private static void moveReplace(File src, File dst) throws IOException {
        if (src == null || dst == null) return;
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (java.io.InputStream in = new java.io.FileInputStream(src);
             java.io.OutputStream out = new java.io.FileOutputStream(dst, false)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
            out.flush();
        }
    }

    private void handle(InputStream is, OutputStream os) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(is));
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(os));
        while (true) {
            String request = r.readLine();
            if (request == null) break;
            request = request.trim();
            if (request.equals("")) break;
            String response = "";
            try {
                response = handle(request);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            w.write(response + "\r\n");
            w.flush();
        }
        r.close();
        w.close();
    }

    private void handle(LocalSocket s) {
        InputStream is = null;
        OutputStream os = null;
        try {
            is = s.getInputStream();
        } catch (IOException ex) {
        }
        try {
            os = s.getOutputStream();
        } catch (IOException ex) {
        }
        if (is != null && os != null) {
            try {
                handle(is, os);
            } catch (Throwable ex) {
                ex.printStackTrace();
            }
        }
        if (is != null) {
            try {
                is.close();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        if (os != null) {
            try {
                os.close();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    public String getSocketName() {
        return socketName;
    }

    public interface Listener {
        void onChange();
    }

    public interface ServiceRegisterListener {
        void onChange(boolean registered);
    }
}
