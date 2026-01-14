package com.ivor.kriptex.crypto;

import org.junit.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class CryptoBoxSelfTest {

    private static final SecureRandom RNG = new SecureRandom();

    @Test
    public void cryptoBox_roundTrip_and_authFailure_writesReport() throws Exception {
        StringBuilder report = new StringBuilder();
        report.append("Kriptex CryptoBox self-test\n");
        report.append("timestamp_utc: ").append(Instant.now().toString()).append("\n");

        boolean ok = false;
        Throwable failure = null;

        try {
            SecretKey key = random256BitKey();

            runOneCase(report, key, 1, "bucket512");
            runOneCase(report, key, 700, "bucket1024");
            runOneCase(report, key, 1500, "bucket2048");

            ok = true;
        } catch (Throwable t) {
            failure = t;
        } finally {
            report.append("result: ").append(ok ? "PASS" : "FAIL").append("\n");
            if (failure != null) {
                report.append("error_type: ").append(failure.getClass().getName()).append("\n");
                report.append("error_message: ").append(safeMessage(failure)).append("\n");
                report.append("stacktrace:\n");
                report.append(stackTraceToString(failure)).append("\n");
            }

            Path outFile = resolveRepoRoot().resolve("crypto_selftest_report.txt");
            Files.write(outFile, report.toString().getBytes(StandardCharsets.UTF_8));
        }

        if (!ok) {
            fail("CryptoBox self-test failed; see crypto_selftest_report.txt");
        }
    }

    private static void runOneCase(StringBuilder report, SecretKey key, int payloadLen, String label) throws Exception {
        byte[] payload = new byte[payloadLen];
        RNG.nextBytes(payload);

        byte[] paddedEnvelope = MessageEnvelope.pack((byte) 0x01, payload);
        int bucket = paddedEnvelope.length;
        report.append("case: ").append(label).append("\n");
        report.append("  payload_length: ").append(payloadLen).append("\n");
        report.append("  padded_bucket: ").append(bucket).append("\n");

        // Encrypt/decrypt must not parse envelope (opaque bytes).
        byte[] ciphertext = CryptoBox.encrypt(paddedEnvelope, key);
        report.append("  ciphertext_length: ").append(ciphertext.length).append("\n");

        int nonceLen = inferNonceLength(ciphertext.length, bucket);
        report.append("  nonce_length: ").append(nonceLen).append("\n");
        report.append("  aead: ").append(nonceLen == 24 ? "XChaCha20-Poly1305" : "AES-256-GCM").append("\n");

        byte[] decrypted = CryptoBox.decrypt(ciphertext, key);
        assertEquals("decrypted length must equal bucket", bucket, decrypted.length);
        assertArrayEquals("round-trip mismatch", paddedEnvelope, decrypted);
        report.append("  round_trip: OK\n");

        // Tamper test (auth must fail, no plaintext returned).
        byte[] tampered = Arrays.copyOf(ciphertext, ciphertext.length);
        int flipIndex = Math.min(tampered.length - 1, nonceLen + 1);
        tampered[flipIndex] ^= 0x01;

        boolean authFailed = false;
        try {
            CryptoBox.decrypt(tampered, key);
        } catch (GeneralSecurityException | IllegalArgumentException expected) {
            authFailed = true;
        }

        if (!authFailed) {
            throw new AssertionError("tamper did not trigger auth failure");
        }
        report.append("  tamper_auth_failure: OK\n");
    }

    private static SecretKey random256BitKey() {
        byte[] keyBytes = new byte[32];
        RNG.nextBytes(keyBytes);
        // Algorithm name is not used by CryptoBox's key-length check (only encoded bytes),
        // but keep it compatible with the AES-GCM fallback.
        return new SecretKeySpec(keyBytes, "AES");
    }

    private static int inferNonceLength(int ciphertextLength, int bucketLen) {
        // CryptoBox format is [nonce || (encrypted||tag)]. For JCA AEAD, encrypted||tag length is bucketLen + 16.
        int expectedBody = bucketLen + 16;
        int nonceLen = ciphertextLength - expectedBody;
        if (nonceLen != 12 && nonceLen != 24) {
            // If this happens, the report will show mismatch and the test will fail.
            return nonceLen;
        }
        return nonceLen;
    }

    private static Path resolveRepoRoot() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (dir.getFileName() != null && "app".equalsIgnoreCase(dir.getFileName().toString())) {
            Path parent = dir.getParent();
            if (parent != null) {
                return parent;
            }
        }
        return dir;
    }

    private static String safeMessage(Throwable t) {
        String msg = t.getMessage();
        if (msg == null) {
            return "";
        }
        // Avoid dumping any accidental sensitive bytes; truncate.
        if (msg.length() > 300) {
            return msg.substring(0, 300) + "...";
        }
        return msg;
    }

    private static String stackTraceToString(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }
}
