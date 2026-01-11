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
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import com.ivor.kriptex.R;
import com.ivor.kriptex.crypto.AdvancedCrypto;
import com.ivor.kriptex.utils.Util;

import org.apache.commons.codec.binary.Base32;
import org.apache.commons.codec.digest.DigestUtils;
import org.spongycastle.asn1.ASN1OutputStream;
import org.spongycastle.asn1.x509.RSAPublicKeyStructure;
import org.spongycastle.jce.provider.BouncyCastleProvider;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PublicKey;
import java.security.Security;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;

public class Tor {

    private static String torname = "ctor";
    private static String tordirname = "tordata";
    private static String torservdir = "torserv";
    private static String torCfg = "torcfg";
    private static int HIDDEN_SERVICE_VERSION = 3;
    private static Tor instance = null;
    private Context mContext;
    private static int mSocksPort = 9151;
    private static int mHttpPort = 8191;
    private String mDomain = "";
    private ArrayList<Listener> mListeners;
    private ArrayList<LogListener> mLogListeners;
    private String status = "";
    private boolean mReady = false;

    private File mTorDir;

    private Process mProcessTor;

    private AtomicBoolean mRunning = new AtomicBoolean(false);

    // Tor v3 hidden services use Ed25519 keys (hs_ed25519_secret_key). That key is NOT an RSA key.
    // This app needs its own RSA keypair for signing/TLS, persisted in app-private storage.
    private static final String APP_RSA_PRIVATE_KEY_FILE = "app_rsa_private_pkcs8.b64";
    private volatile RSAPrivateKey mCachedAppPrivateKey;

    private Tor(Context c) {

        this.mContext = c;

        mListeners = new ArrayList<>();
        mLogListeners = new ArrayList<>();

        mTorDir = new File(c.getFilesDir(), "tor");
        if (!mTorDir.exists()) {
            mTorDir.mkdir();
        }

        mDomain = Util.filestr(new File(getServiceDir(), "hostname")).trim();
        log(mDomain);
    }

    /**
     * start the tor thread
     */
    public void start() {
        if (mRunning.get()) return; // if already running, don't do anything

        Server.getInstance(mContext).setServiceRegistered(false);
        mReady = false;
        new Thread() {
            @Override
            public void run() {
                try {
                    // We ship per-ABI Tor binaries (including x86/x86_64) under jniLibs, so no ABI block here.

                    log("kill");
                    if (!Native.isAvailable()) {
                        log("Native.killTor unavailable: JNI lib not loaded");
                    }
                    Native.killTorSafe();

                    File torExe = getTorExecutableFromNativeLibDir();
                    if (torExe == null || !torExe.exists()) {
                        status = "Tor binary missing for this device ABI. Expected libctor.so in nativeLibraryDir.";
                        log(status);
                        try {
                            for (LogListener ll : mLogListeners) {
                                if (ll != null) ll.onLog();
                            }
                        } catch (Exception ignored) {
                        }
                        return;
                    }
                    log("install: using nativeLibraryDir");

                    log("make dir");
                    File tordir = new File(mTorDir, tordirname);
                    tordir.mkdirs();

                    // Tor requires geoip databases to bootstrap; ship and place them in DataDirectory.
                    File geoip = new File(tordir, "geoip");
                    File geoip6 = new File(tordir, "geoip6");
                    extractRawToFile(mContext, R.raw.geoip, geoip);
                    extractRawToFile(mContext, R.raw.geoip6, geoip6);

                    log("make service");
                    File torsrv = new File(mTorDir, torservdir);
                    torsrv.mkdirs();

                    log("configure");
                    PrintWriter torcfg = new PrintWriter(mContext.openFileOutput(torCfg, Context.MODE_PRIVATE));
                    //torcfg.println("Log debug stdout");
                    torcfg.println("Log notice stdout");
                    torcfg.println("DataDirectory " + tordir.getAbsolutePath());
                    torcfg.println("GeoIPFile " + geoip.getAbsolutePath());
                    torcfg.println("GeoIPv6File " + geoip6.getAbsolutePath());
                    torcfg.println("SOCKSPort " + mSocksPort);
                    torcfg.println("HTTPTunnelPort " + mHttpPort);
                    torcfg.println("HiddenServiceDir " + torsrv.getAbsolutePath());
                    torcfg.println("HiddenServiceVersion " + HIDDEN_SERVICE_VERSION);
                    torcfg.println("HiddenServicePort " + getHiddenServicePort() + " " + Server.getInstance(mContext).getSocketName());
                    torcfg.println("HiddenServicePort " + getFileServerPort() + " 127.0.0.1:" + getFileServerPort());
                    torcfg.println();
                    torcfg.close();
                    log(Util.filestr(new File(mContext.getFilesDir(), torCfg)));

                    log("start: " + torExe.getAbsolutePath());

                    log("Tor exe exists=" + torExe.exists() + " size=" + torExe.length() + " canExecute=" + torExe.canExecute());

                    String[] command = new String[]{
                            torExe.getAbsolutePath(),
                            "-f", mContext.getFileStreamPath(torCfg).getAbsolutePath()
                    };

                    StringBuilder sb = new StringBuilder();
                    for (String s : command) {
                        sb.append(s);
                        sb.append(" ");
                    }

                    log("Command: " + sb.toString());

                    mRunning.set(true);

                    // Tor may log to stderr; merge streams so we don't look "stuck".
                    ProcessBuilder pb = new ProcessBuilder(command);
                    pb.redirectErrorStream(true);
                    mProcessTor = pb.start();
                    BufferedReader torReader = new BufferedReader(new InputStreamReader(mProcessTor.getInputStream()));
                    while (true) {
                        final String line = torReader.readLine();
                        if (line == null) break;
                        log(line);
                        status = line;

                        boolean ready2 = mReady;

                        if (line.contains("100%")) {
                            ls(mTorDir);
                            mDomain = Util.filestr(new File(torsrv, "hostname")).trim();
                            log(mDomain);
                            try {
                                for (Listener l : mListeners) {
                                    if (l != null) l.onChange();
                                }
                            } catch (Exception e) {
                            }
                            ready2 = true;

                            Server.getInstance(mContext).checkServiceRegistered();
                        }
                        mReady = ready2;
                        try {
                            for (LogListener ll : mLogListeners) {
                                if (ll != null) {
                                    ll.onLog();
                                }
                            }
                        } catch (Exception e) {

                        }
                    }

                    try {
                        int exitCode = mProcessTor.waitFor();
                        status = "Tor exited with code " + exitCode;
                        log(status);
                        for (LogListener ll : mLogListeners) {
                            if (ll != null) ll.onLog();
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                    status = "Tor start failed: " + ex;
                    log(status);
                    try {
                        for (LogListener ll : mLogListeners) {
                            if (ll != null) ll.onLog();
                        }
                    } catch (Exception ignored) {
                    }
                    //throw new Error(ex);
                }
                mRunning.set(false);
            }
        }.start();
    }

    private File getTorExecutableFromNativeLibDir() {
        try {
            String nativeLibDir = mContext.getApplicationInfo().nativeLibraryDir;
            if (nativeLibDir == null) return null;
            File candidate = new File(nativeLibDir, "libctor.so");
            if (!candidate.exists()) return null;
            return candidate;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static Tor getInstance(Context context) {
        if (instance == null) {
            instance = new Tor(context.getApplicationContext());
        }
        return instance;
    }

    static String computeID(RSAPublicKeySpec pubKey) {
        RSAPublicKeyStructure myKey = new RSAPublicKeyStructure(pubKey.getModulus(), pubKey.getPublicExponent());
        ByteArrayOutputStream bs = new ByteArrayOutputStream();
        ASN1OutputStream as = new ASN1OutputStream(bs);
        try {
            as.writeObject(myKey.toASN1Object());
        } catch (IOException ex) {
            // TODO: error handling? ignore error?
            throw new Error(ex);
        }
        byte[] b = bs.toByteArray();
        b = DigestUtils.getSha1Digest().digest(b);
        return new Base32().encodeAsString(b).toLowerCase().substring(0, 16);
    }

    public static int getHiddenServicePort() {
        return 31512;
    }

    public static int getFileServerPort() {
        return 8088;
    }

    private void log(String s) {
        Log.d("Tor", "Data: " + s);
    }

    void ls(File f) {
        log(f.toString());
        if (f.isDirectory()) {
            for (File s : f.listFiles()) {
                ls(s);
            }
        }
    }

    public static int getSocksPort() {
        return mSocksPort;
    }

    public static int getHttpPort() {
        return mHttpPort;
    }

    public String getOnion() {
        return mDomain.trim();
    }

    public String getID() {
        return mDomain.replace(".onion", "").trim();
    }

    public void addListener(Listener l) {
        if (l != null && !mListeners.contains(l)) {
            mListeners.add(l);
            l.onChange();
        }
    }

    public void removeListener(Listener l) {
        mListeners.remove(l);
    }

    private void extractFile(Context context, int id, String name) {
        try {
            InputStream i = context.getResources().openRawResource(id);
            OutputStream o = context.openFileOutput(name, Context.MODE_PRIVATE);
            int read;
            byte[] buffer = new byte[4096];
            while ((read = i.read(buffer)) > 0) {
                o.write(buffer, 0, read);
            }
            i.close();
            o.close();
        } catch (Exception ex) {
            ex.printStackTrace();
            //throw new Error(ex);
        }
    }

    private void extractRawToFile(Context context, int id, File outFile) {
        try {
            InputStream i = context.getResources().openRawResource(id);
            File parent = outFile.getParentFile();
            if (parent != null) parent.mkdirs();
            OutputStream o = new FileOutputStream(outFile);
            int read;
            byte[] buffer = new byte[4096];
            while ((read = i.read(buffer)) > 0) {
                o.write(buffer, 0, read);
            }
            i.close();
            o.close();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public File getServiceDir() {
        return new File(mTorDir, torservdir);
    }

    private KeyFactory getKeyFactory() {
//        if (Security.getProvider("BC") == null) {
        Security.addProvider(new BouncyCastleProvider());
//        }
        try {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {
                return KeyFactory.getInstance("RSA", "BC");
            } else {
                return KeyFactory.getInstance("RSA");
            }
        } catch (Exception ex) {
            throw new Error(ex);
        }
    }

    public RSAPrivateKey getPrivateKey() {
        RSAPrivateKey cached = mCachedAppPrivateKey;
        if (cached != null) return cached;

        synchronized (this) {
            if (mCachedAppPrivateKey != null) return mCachedAppPrivateKey;

            File keyFile = mContext.getFileStreamPath(APP_RSA_PRIVATE_KEY_FILE);
            String b64 = Util.filestr(keyFile);
            if (b64 == null) b64 = "";
            b64 = b64.trim().replaceAll("\\s", "");

            RSAPrivateKey loaded = null;
            if (!b64.isEmpty()) {
                try {
                    byte[] data = Base64.decode(b64, Base64.NO_WRAP);
                    PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(data);
                    loaded = (RSAPrivateKey) getKeyFactory().generatePrivate(keySpec);
                } catch (IllegalArgumentException | InvalidKeySpecException e) {
                    log("App RSA key decode failed; regenerating: " + e);
                    //noinspection ResultOfMethodCallIgnored
                    keyFile.delete();
                }
            }

            if (loaded == null) {
                loaded = generateAndPersistAppRsaPrivateKey(keyFile);
            }

            mCachedAppPrivateKey = loaded;
            return loaded;
        }
    }

    private RSAPrivateKey generateAndPersistAppRsaPrivateKey(File keyFile) {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();

            byte[] pkcs8 = pair.getPrivate().getEncoded();
            String b64 = Base64.encodeToString(pkcs8, Base64.NO_WRAP);

            File parent = keyFile.getParentFile();
            if (parent != null) parent.mkdirs();
            OutputStream o = null;
            try {
                o = new FileOutputStream(keyFile);
                o.write(b64.getBytes(StandardCharsets.UTF_8));
                o.flush();
            } finally {
                if (o != null) try { o.close(); } catch (IOException ignored) {}
            }

            return (RSAPrivateKey) pair.getPrivate();
        } catch (Exception e) {
            throw new Error(e);
        }
    }

    private RSAPrivateKeySpec getPrivateKeySpec() {
        try {
            return getKeyFactory().getKeySpec(getPrivateKey(), RSAPrivateKeySpec.class);
        } catch (InvalidKeySpecException ex) {
            throw new Error(ex);
        }
    }

    private RSAPublicKeySpec getPublicKeySpec() {
        return new RSAPublicKeySpec(getPrivateKeySpec().getModulus(), BigInteger.valueOf(65537));
    }

    public RSAPublicKey getPublicKey() {
        try {
            return (RSAPublicKey) getKeyFactory().generatePublic(getPublicKeySpec());
        } catch (InvalidKeySpecException ex) {
            throw new Error(ex);
        }
    }

    private String computeOnion() {
        return computeID(getPublicKeySpec()) + ".onion";
    }

    public byte[] getPubKeySpec() {
        return getPrivateKeySpec().getModulus().toByteArray();
    }

    public byte[] sign(byte[] msg) {
        try {
            Signature signature;
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {
                signature = Signature.getInstance("SHA1withRSA", "BC");
            } else {
                signature = Signature.getInstance("SHA1withRSA");
            }
            signature.initSign(getPrivateKey());
            signature.update(msg);
            return signature.sign();
        } catch (Exception ex) {
            throw new Error(ex);
        }
    }

    public void stop() {
        if (mProcessTor != null) mProcessTor.destroy();
    }

    public String encryptByPublicKey(String data) throws NoSuchPaddingException, NoSuchAlgorithmException, BadPaddingException, IllegalBlockSizeException, InvalidKeyException, NoSuchProviderException {
        Cipher encrypt;
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {
            encrypt = Cipher.getInstance("RSA/ECB/PKCS1Padding", "BC");
        } else {
            encrypt = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        }
        encrypt.init(Cipher.ENCRYPT_MODE, getPublicKey());
        return AdvancedCrypto.toHex(encrypt.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    public String encryptByPublicKey(String data, byte[] pubKeySpecBytes) throws NoSuchPaddingException, NoSuchAlgorithmException, BadPaddingException, IllegalBlockSizeException, InvalidKeyException, InvalidKeySpecException, NoSuchProviderException {
        RSAPublicKeySpec publicKeySpec = new RSAPublicKeySpec(new BigInteger(pubKeySpecBytes), BigInteger.valueOf(65537));
        PublicKey publicKey = getKeyFactory().generatePublic(publicKeySpec);

        Cipher encrypt;
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {
            encrypt = Cipher.getInstance("RSA/ECB/PKCS1Padding", "BC");
        } else {
            encrypt = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        }
        encrypt.init(Cipher.ENCRYPT_MODE, publicKey);
        return AdvancedCrypto.toHex(encrypt.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    public String decryptByPrivateKey(String data) throws NoSuchPaddingException, NoSuchAlgorithmException, BadPaddingException, IllegalBlockSizeException, InvalidKeyException, NoSuchProviderException {
        Cipher decrypt;
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {
            decrypt = Cipher.getInstance("RSA/ECB/PKCS1Padding", "BC");
        } else {
            decrypt = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        }
        decrypt.init(Cipher.DECRYPT_MODE, getPrivateKey());
        return new String(decrypt.doFinal(AdvancedCrypto.toByte(data)), StandardCharsets.UTF_8);
    }

    public PublicKey convertKeySpec(byte[] pubkey) {
        RSAPublicKeySpec publicKeySpec = new RSAPublicKeySpec(new BigInteger(pubkey), BigInteger.valueOf(65537));
        PublicKey publicKey;
        try {
            publicKey = getKeyFactory().generatePublic(publicKeySpec);
        } catch (InvalidKeySpecException ex) {
            ex.printStackTrace();
            return null;
        }
        return publicKey;
    }

    boolean checkSig(String id, byte[] pubkey, byte[] sig, byte[] msg) {
        RSAPublicKeySpec publicKeySpec = new RSAPublicKeySpec(new BigInteger(pubkey), BigInteger.valueOf(65537));

        // Legacy v2 onion ids were derived from an RSA key. Tor v3 onion ids are Ed25519-based,
        // so they cannot be derived from the RSA key we use for app-level signing.
        // For v3 ids, we skip the onion<->RSA binding check and only verify the signature.
        String normalizedId = id == null ? "" : id.trim().toLowerCase(Locale.US);
        if (normalizedId.endsWith(".onion")) {
            normalizedId = normalizedId.substring(0, normalizedId.length() - ".onion".length());
        }
        String computedLegacyId = computeID(publicKeySpec);
        boolean looksLikeV3 = normalizedId.matches("[a-z2-7]{56}");
        if (!normalizedId.equals(computedLegacyId) && !looksLikeV3) {
            log("invalid id");
            return false;
        }

        PublicKey publicKey;
        try {
            publicKey = getKeyFactory().generatePublic(publicKeySpec);
        } catch (InvalidKeySpecException ex) {
            ex.printStackTrace();
            return false;
        }

        try {
            Signature signature;
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {
                signature = Signature.getInstance("SHA1withRSA", "BC");
            } else {
                signature = Signature.getInstance("SHA1withRSA");
            }
            signature.initVerify(publicKey);
            signature.update(msg);
            return signature.verify(sig);
        } catch (Exception ex) {
            ex.printStackTrace();
            return false;
        }
    }

    void test() {
        try {
            String domain = Util.filestr(new File(getServiceDir(), "hostname")).trim();

            log(Util.filestr(new File(getServiceDir(), "hostname")).trim());
            log(computeID(getPublicKeySpec()));
            log(computeOnion());
            log(Util.filestr(new File(getServiceDir(), "hostname")).trim());

            log(Base64.encodeToString(getPubKeySpec(), Base64.DEFAULT));
            log("pub " + Base64.encodeToString(getPubKeySpec(), Base64.DEFAULT));

            byte[] msg = "alkjdalwkdjaw".getBytes();
            log("msg " + Base64.encodeToString(msg, Base64.DEFAULT));

            byte[] sig = sign(msg);
            log("sig " + Base64.encodeToString(sig, Base64.DEFAULT));

            log("chk " + checkSig(getID(), getPubKeySpec(), sig, msg));
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public void addLogListener(LogListener l) {
        if (!mLogListeners.contains(l)) {
            mLogListeners.add(l);
        }
    }

    public String getStatus() {
        return status;
    }

    public boolean isReady() {
        return mReady;
    }

    public void removeLogListener(LogListener ll) {
        mLogListeners.remove(ll);
    }

    public interface Listener {
        void onChange();
    }

    public interface LogListener {
        void onLog();
    }
}
