package com.ivor.kriptex.db;


import android.content.Context;
import android.util.Log;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import io.realm.Realm;
import io.realm.RealmObject;
import io.realm.annotations.Index;
import io.realm.annotations.PrimaryKey;

/**
 * contact / contact request table
 * _id: primary key
 * address: 16 character onion address
 * name: nick-name
 * outgoing: pending outgoing friend request
 * incoming: incoming friend request / someone else wants to add us / will be shown on the requests tab instead of the contacts tab
 * pending: the number of unread messages
 */
public class Contact extends RealmObject {

    private static final String TAG = Contact.class.getSimpleName();

    @PrimaryKey
    private long _id;
    @Index
    private String address;
    @Index
    private String name;
    private int outgoing;
    @Index
    private int incoming;
    private long lastOnlineTime;
    private int pending;
    private long lastMessageTime;
    private String description;
    private byte[] pubKey;

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

        // Generic: a base32-looking prefix of the onion id (>=8 chars) is likely a placeholder.
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

            // Avoid "double-decoding" into something that still looks encoded.
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

    public long get_id() {
        return _id;
    }

    public void set_id(long _id) {
        this._id = _id;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getOutgoing() {
        return outgoing;
    }

    public void setOutgoing(int outgoing) {
        this.outgoing = outgoing;
    }

    public int getIncoming() {
        return incoming;
    }

    public void setIncoming(int incoming) {
        this.incoming = incoming;
    }

    public int getPending() {
        return pending;
    }

    public void setPending(int pending) {
        this.pending = pending;
    }

    public long getLastMessageTime() {
        return lastMessageTime;
    }

    public void setLastMessageTime(long lastMessageTime) {
        this.lastMessageTime = lastMessageTime;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public long getLastOnlineTime() {
        return lastOnlineTime;
    }

    public void setLastOnlineTime(long lastOnlineTime) {
        this.lastOnlineTime = lastOnlineTime;
    }

    public byte[] getPubKey() {
        return pubKey;
    }

    public void setPubKey(byte[] pubKey) {
        this.pubKey = pubKey;
    }

    public static boolean addContact(Context context, String id, String name, String description, byte[] pubKey, boolean outgoing, boolean incoming) {
        if (name == null) name = "";
        if (description == null) description = "";
        if (id == null) id = "";

        name = name.trim();
        description = description.trim();

        // Some older paths accidentally stored base64 tokens as the display name. If the token
        // decodes to a short printable alias, prefer the decoded value.
        String decodedName = decodeBase64IfPrintableShort(name);
        if (decodedName != null) {
            name = decodedName;
        }
        String rawId = id.trim().toLowerCase(Locale.US);
        id = normalizeOnionId(rawId);
        Realm realm = Realm.getDefaultInstance();
        Contact savedContact = realm.where(Contact.class)
                .beginGroup()
                .equalTo("address", id)
                .or()
                .equalTo("address", rawId)
                .endGroup()
                .findFirst();
        if (savedContact == null) {
            realm.beginTransaction();
            Contact contact = realm.createObject(Contact.class, getNextId());
            contact.setName(name);
            contact.setDescription(description);
            contact.setAddress(id);
            contact.setPubKey(pubKey);
            contact.setOutgoing(outgoing ? 1 : 0);
            contact.setIncoming(incoming ? 1 : 0);
            realm.commitTransaction();
        } else {
            boolean hasNewName = !name.isEmpty();
            boolean hasNewDescription = !description.isEmpty();

            String existingName = savedContact.getName();
            String existingDescription = savedContact.getDescription();

            boolean existingNameIsBlank = existingName == null || existingName.trim().isEmpty();
            boolean existingDescIsBlank = existingDescription == null || existingDescription.trim().isEmpty();

            // Some code paths (or older builds) may have populated a placeholder name derived from the onion id.
            // If we later learn a real alias (senderName), allow replacing the placeholder.
            boolean existingNameLooksLikePlaceholder = !existingNameIsBlank && looksLikeOnionPlaceholder(existingName, id);

            // Some older builds mistakenly used key material / encoded blobs as the "name".
            String existingNameTrimmed = existingName == null ? "" : existingName.trim();
            boolean existingNameLooksEncrypted = !existingNameIsBlank && looksLikeKeyMaterialOrEncryptedName(existingNameTrimmed);

            boolean newNameLooksHuman = looksLikeHumanAlias(name);

            boolean shouldUpdateName = hasNewName && newNameLooksHuman && (existingNameIsBlank || existingNameLooksLikePlaceholder || existingNameLooksEncrypted);
            boolean shouldUpdateDescription = hasNewDescription && existingDescIsBlank;

            // Important: Do not overwrite an existing pubKey here. For v3 onion ids, we cannot bind
            // the onion id to the RSA key, so blindly updating would enable key replacement.

            if (shouldUpdateName || shouldUpdateDescription) {
                realm.beginTransaction();
                if (shouldUpdateName) {
                    savedContact.setName(name);
                }
                if (shouldUpdateDescription) {
                    savedContact.setDescription(description);
                }
                realm.commitTransaction();

                if (shouldUpdateName) {
                    Log.d(TAG, "addContact: updated name for " + id + " to '" + name + "'");
                }
            }
        }
        realm.close();
        return savedContact == null;
    }

    public static boolean hasContact(Context context, String id) {
        String rawId = id == null ? "" : id.trim().toLowerCase(Locale.US);
        String normalized = normalizeOnionId(rawId);
        Realm realm = Realm.getDefaultInstance();
        Contact contact = realm.where(Contact.class)
                .beginGroup()
                .equalTo("address", normalized)
                .or()
                .equalTo("address", rawId)
                .endGroup()
                .equalTo("incoming", 0)
                .findFirst();
        realm.close();
        return contact != null;
    }

    public static long getNextId() {
        Realm realm = Realm.getDefaultInstance();
        Number maxId = realm.where(Contact.class).max("_id");
        // If there are no rows, currentId is null, so the next id must be 1
        // If currentId is not null, increment it by 1
        realm.close();
        return (maxId == null) ? 1 : maxId.longValue() + 1;
    }

    public static void acceptContact(Context context, String id) {
        String rawId = id == null ? "" : id.trim().toLowerCase(Locale.US);
        String normalized = normalizeOnionId(rawId);
        Realm realm = Realm.getDefaultInstance();
        Contact contact = realm.where(Contact.class)
                .beginGroup()
                .equalTo("address", normalized)
                .or()
                .equalTo("address", rawId)
                .endGroup()
                .findFirst();
        if (contact != null) {
            realm.beginTransaction();
            contact.setIncoming(0);
            contact.setOutgoing(0);
            realm.commitTransaction();
        }
        realm.close();
    }
}
