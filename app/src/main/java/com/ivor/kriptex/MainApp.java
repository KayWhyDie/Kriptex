package com.ivor.kriptex;

import android.util.Log;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.multidex.MultiDexApplication;

import com.ivor.kriptex.tor.FileServer;
import com.ivor.kriptex.tor.Tor;
import com.liulishuo.filedownloader.FileDownloader;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import cn.dreamtobe.filedownloader.OkHttp3Connection;
import io.realm.Realm;
import io.realm.RealmConfiguration;
import io.realm.RealmSchema;
import okhttp3.CipherSuite;
import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import okhttp3.TlsVersion;

public class MainApp extends MultiDexApplication {

    private static final String TAG = "MainApp";

    @Override
    public void onCreate() {
        super.onCreate();

        // Single-theme app: always dark. UI no longer follows a toggle.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);

        Realm.init(this);

        // The RealmConfiguration is created using the builder pattern.
        // The Realm file will be located in Context.getFilesDir() with name "myrealm.realm"
        RealmConfiguration config = new RealmConfiguration.Builder()
                .name("myrealm.realm")
                // This app performs some Realm writes from UI events (e.g., sending messages,
                // creating rooms). Allowing UI-thread writes prevents runtime crashes.
                .allowWritesOnUiThread(true)
                .allowQueriesOnUiThread(true)
            .schemaVersion(7)
                .migration((realm, oldVersion, newVersion) -> {

                    Log.d(TAG, "onCreate: Old Version: " + oldVersion + " New Version: " + newVersion);

                    RealmSchema schema = realm.getSchema();
                    while (oldVersion < newVersion) {
                        if (oldVersion == 0) {
                            if (schema.get("Contact") != null && !schema.get("Contact").hasField("pubKey")) {
                                schema.get("Contact").addField("pubKey", byte[].class);
                            }
                            oldVersion++;
                            continue;
                        }

                        if (oldVersion == 1) {
                            Log.d(TAG, "onCreate: changing contact schema and lastOnlineTime");
                            if (schema.get("Contact") != null && !schema.get("Contact").hasField("lastOnlineTime")) {
                                schema.get("Contact").addField("lastOnlineTime", Long.class);
                            }
                            oldVersion++;
                            continue;
                        }

                        if (oldVersion == 2) {
                            // Chatrooms + room messages.
                            if (schema.get("ChatRoom") == null) {
                                schema.create("ChatRoom")
                                        .addField("id", String.class, io.realm.FieldAttribute.PRIMARY_KEY, io.realm.FieldAttribute.REQUIRED)
                                        .addField("name", String.class)
                                        .addField("createdAt", long.class)
                                        .addIndex("name")
                                        .addIndex("createdAt");
                            }
                            if (schema.get("ChatRoomMember") == null) {
                                schema.create("ChatRoomMember")
                                        .addField("primaryKey", String.class, io.realm.FieldAttribute.PRIMARY_KEY, io.realm.FieldAttribute.REQUIRED)
                                        .addField("roomId", String.class)
                                        .addField("address", String.class)
                                        .addField("alias", String.class)
                                        .addIndex("roomId")
                                        .addIndex("address");
                            }
                            if (schema.get("Message") != null) {
                                if (!schema.get("Message").hasField("roomId")) {
                                    schema.get("Message").addField("roomId", String.class).addIndex("roomId");
                                }
                                if (!schema.get("Message").hasField("roomMessageId")) {
                                    schema.get("Message").addField("roomMessageId", String.class).addIndex("roomMessageId");
                                }
                                if (!schema.get("Message").hasField("roomSystemType")) {
                                    schema.get("Message").addField("roomSystemType", String.class).addIndex("roomSystemType");
                                }
                            }
                            oldVersion++;
                            continue;
                        }

                        if (oldVersion == 3) {
                            // Unread indicators per room.
                            if (schema.get("ChatRoom") != null && !schema.get("ChatRoom").hasField("lastReadStableId")) {
                                schema.get("ChatRoom")
                                        .addField("lastReadStableId", long.class)
                                        .addIndex("lastReadStableId");
                            }
                            oldVersion++;
                            continue;
                        }

                        if (oldVersion == 4) {
                            // E2EE attachment metadata stored on FileShare.
                            if (schema.get("FileShare") != null) {
                                if (!schema.get("FileShare").hasField("mediaId")) {
                                    schema.get("FileShare").addField("mediaId", String.class);
                                }
                                if (!schema.get("FileShare").hasField("mediaBlobId")) {
                                    schema.get("FileShare").addField("mediaBlobId", String.class);
                                }
                                if (!schema.get("FileShare").hasField("encryptedMediaKey")) {
                                    schema.get("FileShare").addField("encryptedMediaKey", byte[].class);
                                }
                                if (!schema.get("FileShare").hasField("mediaAEAD")) {
                                    schema.get("FileShare").addField("mediaAEAD", String.class);
                                }
                                if (!schema.get("FileShare").hasField("ciphertextSize")) {
                                    schema.get("FileShare").addField("ciphertextSize", long.class);
                                }
                            }
                            oldVersion++;
                            continue;
                        }

                        if (oldVersion == 5) {
                            // Phase 2 hardening fields.
                            if (schema.get("FileShare") != null) {
                                if (!schema.get("FileShare").hasField("plaintextSha256")) {
                                    schema.get("FileShare").addField("plaintextSha256", byte[].class);
                                }
                                if (!schema.get("FileShare").hasField("serveRequestCount")) {
                                    schema.get("FileShare").addField("serveRequestCount", int.class);
                                }
                                if (!schema.get("FileShare").hasField("maxServeRequests")) {
                                    schema.get("FileShare").addField("maxServeRequests", int.class);
                                }
                            }
                            oldVersion++;
                            continue;
                        }

                        if (oldVersion == 6) {
                            // Phase 3 chunked media delivery fields.
                            if (schema.get("FileShare") != null) {
                                if (!schema.get("FileShare").hasField("chunked")) {
                                    schema.get("FileShare").addField("chunked", boolean.class);
                                }
                                if (!schema.get("FileShare").hasField("chunkSize")) {
                                    schema.get("FileShare").addField("chunkSize", int.class);
                                }
                                if (!schema.get("FileShare").hasField("totalChunks")) {
                                    schema.get("FileShare").addField("totalChunks", int.class);
                                }
                                if (!schema.get("FileShare").hasField("manifestVerified")) {
                                    schema.get("FileShare").addField("manifestVerified", boolean.class);
                                }
                                if (!schema.get("FileShare").hasField("chunkBitmap")) {
                                    schema.get("FileShare").addField("chunkBitmap", byte[].class);
                                }
                            }
                            oldVersion++;
                            continue;
                        }

                        oldVersion++;
                    }
                })
                .build();

        Realm.setDefaultConfiguration(config);

//        InetSocketAddress proxyAddr = new InetSocketAddress("127.0.0.1", Tor.getSocksPort());
//        Proxy proxyTor = new Proxy(Proxy.Type.SOCKS, proxyAddr);

        InetSocketAddress proxyAddr = new InetSocketAddress("127.0.0.1", Tor.getHttpPort());
        Proxy proxyTor = new Proxy(Proxy.Type.HTTP, proxyAddr);

        ConnectionSpec spec = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_2)
                .cipherSuites(
                        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                        CipherSuite.TLS_DHE_RSA_WITH_AES_128_GCM_SHA256,
                        CipherSuite.TLS_DHE_RSA_WITH_AES_128_CBC_SHA,
                        CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
                        CipherSuite.TLS_RSA_WITH_3DES_EDE_CBC_SHA)
                .build();

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .proxy(proxyTor)
                .proxySelector(new ProxySelector() {
                    @Override
                    public List<Proxy> select(URI uri) {
                        return Arrays.asList(proxyTor);
                    }

                    @Override
                    public void connectFailed(URI uri, SocketAddress socketAddress, IOException e) {
                        e.printStackTrace();
                    }
                })
                .connectTimeout(300, TimeUnit.SECONDS)
                .dns(s -> Arrays.asList(InetAddress.getByAddress(new byte[]{127, 0, 0, 1})))
                .connectionSpecs(Collections.singletonList(spec))
                .readTimeout(300, TimeUnit.SECONDS);
        try {
            X509TrustManager trustAll = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustAll}, new SecureRandom());
            builder.sslSocketFactory(sslContext.getSocketFactory(), trustAll);
            builder.hostnameVerifier((s, sslSession) -> true);
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            e.printStackTrace();
        }

        // Init the FileDownloader with the OkHttp3Connection.Creator.
        FileDownloader.setupOnApplicationOnCreate(this)
                .connectionCreator(new OkHttp3Connection.Creator(builder));
//        .connectionCreator(new FileDownloadUrlConnection
//                .Creator(new FileDownloadUrlConnection.Configuration()
//                .connectTimeout(15000) // set connection timeout.
//                .readTimeout(15000) // set read timeout.
//                .proxy(proxyTor)
//        ));
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
    }

    private void testFileServer() throws IOException {
        FileServer mFileServer = FileServer.getInstance(this, Tor.getFileServerPort(), false);
        mFileServer.start(10000, false);
        Log.d(TAG, "FileServer was stopped, now started again");
    }
}
