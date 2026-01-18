package com.ivor.kriptex.tor;


import android.content.Context;
import android.util.Log;
import android.webkit.MimeTypeMap;

import com.ivor.kriptex.crypto.CryptoUtils;
import com.ivor.kriptex.crypto.media.MediaAttachmentCrypto;
import com.ivor.kriptex.db.FileShare;
import com.ivor.kriptex.db.Message;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Map;

import javax.net.ssl.KeyManagerFactory;

import fi.iki.elonen.NanoHTTPD;
import io.realm.Realm;
import io.realm.Sort;

public class FileServer extends NanoHTTPD {

    public static final int FILE_NOT_FOUND = 4;
    public static final int FILE_NOT_SERVABLE = 5;

    private static final String TAG = "FileServer";

    private Context mContext;

    private static FileServer mInstance;

    public static FileServer getInstance(Context context, int port, boolean forceNew) {
        if (mInstance == null) {
            mInstance = new FileServer(context, port);
        }
        if (forceNew) {
            if (mInstance != null) {
                mInstance.stop();
                mInstance = null;
                mInstance = new FileServer(context, port);
            }
        }
        return mInstance;
    }

    private FileServer(Context context, int port) {
        super(port);
        mContext = context;
        try {
            makeServerSecure();
        } catch (KeyStoreException | UnrecoverableKeyException | NoSuchAlgorithmException | CertificateException | IOException e) {
            e.printStackTrace();
        }
    }

    public void startServer() throws IOException {
        if (!isAlive()) {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        }
    }

    private void makeServerSecure() throws KeyStoreException, IOException, CertificateException, NoSuchAlgorithmException, UnrecoverableKeyException {
        File keyStore = new File(mContext.getFilesDir(), "keystore");
        Tor tor = Tor.getInstance(mContext);

        // Use a per-install keystore backed by the app-owned RSA key.
        // This avoids relying on a static asset keystore that may not load on newer Android builds.
        String password = tor.getID();
        if (password == null) password = "kriptex";

        if (!keyStore.exists()) {
            Log.d(TAG, "testFileServer: Creating keystore");
            CryptoUtils.createKeyStore(keyStore.getAbsolutePath(), password, new KeyPair(tor.getPublicKey(), tor.getPrivateKey()));
        }

        KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
        try (InputStream keystoreStream = new FileInputStream(keyStore)) {
            keystore.load(keystoreStream, password.toCharArray());
        } catch (IOException | CertificateException e) {
            // Corrupt / wrong password: recreate.
            Log.w(TAG, "Keystore load failed; recreating", e);
            //noinspection ResultOfMethodCallIgnored
            keyStore.delete();
            CryptoUtils.createKeyStore(keyStore.getAbsolutePath(), password, new KeyPair(tor.getPublicKey(), tor.getPrivateKey()));
            try (InputStream keystoreStream = new FileInputStream(keyStore)) {
                keystore.load(keystoreStream, password.toCharArray());
            }
        }

        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keystore, password.toCharArray());
        makeSecure(NanoHTTPD.makeSSLSocketFactory(keystore, keyManagerFactory), null);
    }

    @Override
    public Response serve(IHTTPSession session) {
        Log.d(TAG, "serve: Got file request: " + session.getUri());

        Response response;

//        if(mActivity.isServeFile()) {

        File file;
        Map<String, String> headers = session.getHeaders();
        String fn = session.getUri().substring(1);
        if (fn.startsWith("media/")) {
            // Phase 3: chunked dumb blob host. Authorization is implicit via possession of the media key.
            // Routes:
            // - /media/{mediaId}/manifest
            // - /media/{mediaId}/{chunkIndex}
            String[] parts = fn.split("/");
            if (parts.length != 3) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/*", "" + FILE_NOT_FOUND);
            }
            String mediaId = parts[1];
            String leaf = parts[2];
            if (!isUuid(mediaId)) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/*", "" + FILE_NOT_FOUND);
            }

            if ("manifest".equals(leaf)) {
                file = MediaAttachmentCrypto.chunkedManifestFileForServing(mContext, mediaId);
            } else {
                int idx;
                try {
                    idx = Integer.parseInt(leaf);
                } catch (NumberFormatException e) {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/*", "" + FILE_NOT_FOUND);
                }
                if (idx < 0) {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/*", "" + FILE_NOT_FOUND);
                }
                file = MediaAttachmentCrypto.chunkedChunkFileForServing(mContext, mediaId, idx);
            }

            if (!file.exists()) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/*", "" + FILE_NOT_FOUND);
            }
            String mimeType = "application/octet-stream";
            return serveFile(session.getUri(), session.getHeaders(), file, mimeType);
        }

        Realm realm = Realm.getDefaultInstance();
        String password = headers.get("password");
        Log.d(TAG, "serve: Trying to find file: " + fn);
        FileShare fileShare = realm.where(FileShare.class)
                .beginGroup()
                .equalTo("filename", fn)
                .and()
                .equalTo("password", password)
                .endGroup()
                .sort("_id", Sort.DESCENDING).findFirst();

        if (fileShare != null && !fileShare.isServed()) {
            // Chunked attachments must be served via /media/... endpoints (no password auth).
            if (fileShare.isChunked()) {
                Log.d(TAG, "serve: Chunked attachment requested via legacy route: " + fn);
                realm.close();
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/*", "" + FILE_NOT_SERVABLE);
            }
            int configuredMax = fileShare.getMaxServeRequests();
            int effectiveMax = configuredMax > 0 ? configuredMax : (fileShare.getMediaId() != null && !fileShare.getMediaId().trim().isEmpty() ? 64 : 32);
            if (fileShare.getServeRequestCount() >= effectiveMax) {
                Log.d(TAG, "serve: File password replay limit reached: " + fn);
                realm.close();
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/*", "" + FILE_NOT_SERVABLE);
            }

            // Legacy: serve plaintext filePath.
            // E2EE: serve raw ciphertext from app-owned media dir keyed by mediaId.
            if (fileShare.getMediaId() != null && !fileShare.getMediaId().trim().isEmpty()) {
                String blobId = fileShare.getMediaBlobId();
                if (blobId == null || blobId.trim().isEmpty()) blobId = fileShare.getMediaId();
                file = MediaAttachmentCrypto.ciphertextFileForServing(mContext, blobId);
            } else {
                file = new File(fileShare.getFilePath());
            }
            if (file.exists()) {
                Log.d(TAG, "serve: File found serving: " + fileShare.getFilePath());

                // Phase 2: bounded-use authorization semantics (best-effort, retry-tolerant).
                // Increment once per HTTP request served.
                long id = fileShare.get_id();
                int maxFinal = effectiveMax;
                realm.executeTransaction(r -> {
                    FileShare fs = r.where(FileShare.class).equalTo("_id", id).findFirst();
                    if (fs != null) {
                        if (fs.getMaxServeRequests() <= 0) fs.setMaxServeRequests(maxFinal);
                        fs.setServeRequestCount(fs.getServeRequestCount() + 1);
                    }
                });
            } else {
                Log.d(TAG, "serve: File not found: " + fileShare.getFilePath());
                realm.close();
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/*", FILE_NOT_FOUND + "");
            }
        } else {
            Log.d(TAG, "serve: File not found: " + fn);
            realm.close();
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/*", "" + FILE_NOT_SERVABLE);
        }
        realm.close();
        String mimeType = getMimeType(file.getAbsolutePath());
        response = serveFile(session.getUri(), session.getHeaders(), file, mimeType);
        return response;
    }

    private static boolean isUuid(String s) {
        if (s == null) return false;
        // Fast UUID v4-ish validation: 36 chars with hyphens at canonical positions.
        if (s.length() != 36) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                if (c != '-') return false;
                continue;
            }
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    //Announce that the file server accepts partial content requests
    private Response createResponse(Response.Status status, String mimeType,
                                    InputStream message) {
        Response res = newFixedLengthResponse(status, mimeType, message, 50);
        res.addHeader("Accept-Ranges", "bytes");
        return res;
    }

    /**
     * Serves file from homeDir and its' subdirectories (only). Uses only URI,
     * ignores all headers and HTTP parameters.
     */
    private Response serveFile(String uri, Map<String, String> header,
                               File file, String mime) {
        Response res;
        try {
            // Calculate etag
            String etag = Integer.toHexString((file.getAbsolutePath()
                    + file.lastModified() + "" + file.length()).hashCode());

            // Support (simple) skipping:
            long startFrom = 0;
            long endAt = -1;
            String range = header.get("range");
            if (range != null) {
                if (range.startsWith("bytes=")) {
                    range = range.substring("bytes=".length());
                    int minus = range.indexOf('-');
                    try {
                        if (minus > 0) {
                            startFrom = Long.parseLong(range
                                    .substring(0, minus));
                            endAt = Long.parseLong(range.substring(minus + 1));
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            // Change return code and add Content-Range header when skipping is
            // requested
            long fileLen = file.length();
            if (range != null && startFrom >= 0) {
                if (startFrom >= fileLen) {
                    res = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE,
                            NanoHTTPD.MIME_PLAINTEXT, "");
                    res.addHeader("Content-Range", "bytes 0-0/" + fileLen);
                    res.addHeader("ETag", etag);
                } else {
                    if (endAt < 0) {
                        endAt = fileLen - 1;
                    }
                    long newLen = endAt - startFrom + 1;
                    if (newLen < 0) {
                        newLen = 0;
                    }

                    final long dataLen = newLen;
                    FileInputStream fis = new FileInputStream(file) {
                        @Override
                        public int available() {
                            return (int) dataLen;
                        }
                    };
                    fis.skip(startFrom);

                    res = createResponse(Response.Status.PARTIAL_CONTENT, mime,
                            fis);
                    res.addHeader("Content-Length", "" + dataLen);
                    res.addHeader("Content-Range", "bytes " + startFrom + "-"
                            + endAt + "/" + fileLen);
                    res.addHeader("ETag", etag);
                }
            } else {
                if (etag.equals(header.get("if-none-match")))
                    res = newFixedLengthResponse(Response.Status.NOT_MODIFIED, mime, "");
                else {
                    res = createResponse(Response.Status.OK, mime,
                            new FileInputStream(file));
                    res.addHeader("Content-Length", "" + fileLen);
                    res.addHeader("ETag", etag);
                }
            }
        } catch (IOException ioe) {
            res = newFixedLengthResponse(Response.Status.FORBIDDEN,
                    NanoHTTPD.MIME_PLAINTEXT, "FORBIDDEN: Reading file failed.");
        }

        return res;
    }

    public static String getMimeType(String url) {
        Log.d(TAG, "get mime type of " + url);
        if (url.lastIndexOf(".") < 0) {
            return null;
        }
        String extension = url.substring(url.lastIndexOf("."));
        String mimeTypeMap = MimeTypeMap.getFileExtensionFromUrl(extension);
        String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(mimeTypeMap);
        if (mimeType == null) {
            mimeType = "application/octet-stream";
        }
        Log.d(TAG, "getMimeType: " + url + " type is " + mimeType);
        return mimeType;
    }

    public static int getMessageType(String mime) {
        if (mime == null) return Message.TYPE_FILE;

        if (mime.startsWith("image")) {
            Log.d(TAG, "getMessageType: image");
            return Message.TYPE_IMAGE;
        } else if (mime.startsWith("video")) {
            Log.d(TAG, "getMessageType: video");
            return Message.TYPE_VIDEO;
        } else if (mime.startsWith("audio")) {
            Log.d(TAG, "getMessageType: audio");
            return Message.TYPE_AUDIO;
        }

        Log.d(TAG, "getMessageType: file");
        return Message.TYPE_FILE;
    }
}
