package com.ivor.kriptex.db;

import io.realm.RealmObject;
import io.realm.annotations.PrimaryKey;

/**
 * fileshare table, it contains all the information of file which will be shared
 * _id primary key
 * message_id this is id of the message which was used for file sharing
 * filename name of the file being shared
 * filepath path of the file being shared
 * should_serve file will be shared only once, after that it won't be accessible
 */

public class FileShare extends RealmObject {

    @PrimaryKey
    private long _id;
    private String filename;
    private String filePath;
    private boolean isServed;
    private boolean isDownloadTried;
    private String mimeType;
    private String password;
    private long fileSize;
    private boolean isDownloaded;
    private byte[] thumbnail;

    // --- E2EE media attachments (application-layer) ---
    // When mediaId is non-null, the served/downloaded bytes on disk are ciphertext:
    // [nonce(24) || encrypted_bytes || tag(16)] using XChaCha20-Poly1305.
    private String mediaId;
    // Future-proofing: allow mediaId→blob mapping indirection (preparing for chunked storage).
    // For now, this is set to mediaId and FileServer serves the corresponding blob file.
    private String mediaBlobId;
    private byte[] encryptedMediaKey;
    private String mediaAEAD = "XCHACHA20_POLY1305";
    private long ciphertextSize;

    // --- Phase 3 chunked E2EE media delivery ---
    // When true, this attachment is delivered as an encrypted manifest + encrypted chunks.
    private boolean chunked;
    private int chunkSize;
    private int totalChunks;
    private boolean manifestVerified;
    // Bitset of verified ciphertext chunks downloaded (LSB-first). Length = ceil(totalChunks/8).
    private byte[] chunkBitmap;

    // --- Phase 3.5: resource-bounding / eviction metadata ---
    // Last observed activity for this chunked download (used for LRU eviction of incomplete entries).
    private long chunkedLastAccessMs;
    // Non-zero when a partially-downloaded chunked entry was evicted (restore-safe; prevents resurrection).
    private long chunkedEvictedAtMs;
    // Debug-only reason string for eviction/cap rejection (no secrets).
    private String chunkedEvictReason;

    // Production hardening: hash of the final plaintext bytes (SHA-256, 32 bytes).
    private byte[] plaintextSha256;

    // Production hardening: bounded-use authorization semantics for the password header.
    // - serveRequestCount increments per served HTTP request.
    // - maxServeRequests bounds replay; defaults applied by writers/servers for older rows.
    private int serveRequestCount;
    private int maxServeRequests;

    public long get_id() {
        return _id;
    }

    public void set_id(long _id) {
        this._id = _id;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public boolean isServed() {
        return isServed;
    }

    public void setServed(boolean served) {
        isServed = served;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public boolean isDownloadTried() {
        return isDownloadTried;
    }

    public void setDownloadTried(boolean downloadTried) {
        isDownloadTried = downloadTried;
    }

    public byte[] getThumbnail() {
        return thumbnail;
    }

    public void setThumbnail(byte[] thumbnail) {
        this.thumbnail = thumbnail;
    }

    public boolean isDownloaded() {
        return isDownloaded;
    }

    public void setDownloaded(boolean downloaded) {
        isDownloaded = downloaded;
    }

    public String getMediaId() {
        return mediaId;
    }

    public void setMediaId(String mediaId) {
        this.mediaId = mediaId;
    }

    public String getMediaBlobId() {
        return mediaBlobId;
    }

    public void setMediaBlobId(String mediaBlobId) {
        this.mediaBlobId = mediaBlobId;
    }

    public byte[] getEncryptedMediaKey() {
        return encryptedMediaKey;
    }

    public void setEncryptedMediaKey(byte[] encryptedMediaKey) {
        this.encryptedMediaKey = encryptedMediaKey;
    }

    public String getMediaAEAD() {
        return mediaAEAD;
    }

    public void setMediaAEAD(String mediaAEAD) {
        this.mediaAEAD = mediaAEAD;
    }

    public long getCiphertextSize() {
        return ciphertextSize;
    }

    public void setCiphertextSize(long ciphertextSize) {
        this.ciphertextSize = ciphertextSize;
    }

    public boolean isChunked() {
        return chunked;
    }

    public void setChunked(boolean chunked) {
        this.chunked = chunked;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public void setTotalChunks(int totalChunks) {
        this.totalChunks = totalChunks;
    }

    public boolean isManifestVerified() {
        return manifestVerified;
    }

    public void setManifestVerified(boolean manifestVerified) {
        this.manifestVerified = manifestVerified;
    }

    public byte[] getChunkBitmap() {
        return chunkBitmap;
    }

    public void setChunkBitmap(byte[] chunkBitmap) {
        this.chunkBitmap = chunkBitmap;
    }

    public long getChunkedLastAccessMs() {
        return chunkedLastAccessMs;
    }

    public void setChunkedLastAccessMs(long chunkedLastAccessMs) {
        this.chunkedLastAccessMs = chunkedLastAccessMs;
    }

    public long getChunkedEvictedAtMs() {
        return chunkedEvictedAtMs;
    }

    public void setChunkedEvictedAtMs(long chunkedEvictedAtMs) {
        this.chunkedEvictedAtMs = chunkedEvictedAtMs;
    }

    public String getChunkedEvictReason() {
        return chunkedEvictReason;
    }

    public void setChunkedEvictReason(String chunkedEvictReason) {
        this.chunkedEvictReason = chunkedEvictReason;
    }

    public byte[] getPlaintextSha256() {
        return plaintextSha256;
    }

    public void setPlaintextSha256(byte[] plaintextSha256) {
        this.plaintextSha256 = plaintextSha256;
    }

    public int getServeRequestCount() {
        return serveRequestCount;
    }

    public void setServeRequestCount(int serveRequestCount) {
        this.serveRequestCount = serveRequestCount;
    }

    public int getMaxServeRequests() {
        return maxServeRequests;
    }

    public void setMaxServeRequests(int maxServeRequests) {
        this.maxServeRequests = maxServeRequests;
    }
}
