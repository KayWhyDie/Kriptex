package com.ivor.kriptex;

import android.util.Log;

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

        Realm.init(this);

        // The RealmConfiguration is created using the builder pattern.
        // The Realm file will be located in Context.getFilesDir() with name "myrealm.realm"
        RealmConfiguration config = new RealmConfiguration.Builder()
                .name("myrealm.realm")
                .schemaVersion(2)
                .migration((realm, oldVersion, newVersion) -> {

                    Log.d(TAG, "onCreate: Old Version: " + oldVersion + " New Version: " + newVersion);

                    if (oldVersion == 0 && newVersion == 1) {
                        RealmSchema schema = realm.getSchema();
                        schema.get("Contact")
                                .addField("pubKey", byte[].class);
//                        oldVersion++;
                    }

                    if (oldVersion == 1 && newVersion == 2) {
                        RealmSchema schema = realm.getSchema();
                        Log.d(TAG, "onCreate: changing contact schema and lastOnelineTime");
                        schema.get("Contact")
                                .addField("lastOnlineTime", Long.class);
//                        oldVersion++;
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
