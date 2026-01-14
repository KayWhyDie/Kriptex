package com.ivor.kriptex.crypto;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Pads messages to fixed buckets (512/1024/2048 bytes) to reduce metadata leakage.
 *
 * <p>Format: [2-byte big-endian length][message bytes][random padding]
 *
 * <p>No encryption is performed here; this is intended to be applied to plaintext
 * prior to encryption.
 */
public final class StaticLengthPadding {

    public static final int BUCKET_512 = 512;
    public static final int BUCKET_1024 = 1024;
    public static final int BUCKET_2048 = 2048;

    /**
     * 2-byte unsigned length header, big-endian.
     *
     * <p>Max message payload is {@code bucketSize - HEADER_SIZE}.
     */
    public static final int HEADER_SIZE = 2;

    private static final int[] BUCKETS = new int[]{BUCKET_512, BUCKET_1024, BUCKET_2048};

    private static final SecureRandom RNG = new SecureRandom();

    private StaticLengthPadding() {
    }

    /**
     * Pads {@code message} into the smallest bucket that can contain
     * {@code HEADER_SIZE + message.length}.
     *
     * @throws IllegalArgumentException if the message is too large (> 2048 minus header)
     */
    public static byte[] pad(byte[] message) {
        if (message == null) {
            throw new IllegalArgumentException("message == null");
        }

        final int messageLength = message.length;
        final int bucketSize = chooseBucketSizeForMessageLength(messageLength);

        final int maxPayload = bucketSize - HEADER_SIZE;
        if (messageLength > maxPayload) {
            // Defensive: chooseBucketSizeForMessageLength should have rejected this.
            throw new IllegalArgumentException("Message too large for bucket: " + messageLength + " > " + maxPayload);
        }

        final byte[] out = new byte[bucketSize];

        // 2-byte big-endian length
        out[0] = (byte) ((messageLength >>> 8) & 0xFF);
        out[1] = (byte) (messageLength & 0xFF);

        // message
        System.arraycopy(message, 0, out, HEADER_SIZE, messageLength);

        // cryptographically random padding (if any)
        final int paddingStart = HEADER_SIZE + messageLength;
        final int paddingLen = bucketSize - paddingStart;
        if (paddingLen > 0) {
            final byte[] padding = new byte[paddingLen];
            RNG.nextBytes(padding);
            System.arraycopy(padding, 0, out, paddingStart, paddingLen);
        }

        return out;
    }

    /**
     * Convenience for UTF-8 text.
     */
    public static byte[] padUtf8(String text) {
        if (text == null) {
            throw new IllegalArgumentException("text == null");
        }
        return pad(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Unpads a padded bucket and returns the original message bytes.
     *
     * @throws IllegalArgumentException if the input is not exactly 512/1024/2048 bytes,
     *                                  or if the embedded length is invalid.
     */
    public static byte[] unpad(byte[] padded) {
        if (padded == null) {
            throw new IllegalArgumentException("padded == null");
        }

        final int bucketSize = padded.length;
        if (!isAllowedBucket(bucketSize)) {
            throw new IllegalArgumentException("Invalid padded length (must be 512/1024/2048): " + bucketSize);
        }
        if (bucketSize < HEADER_SIZE) {
            throw new IllegalArgumentException("Invalid padded length: " + bucketSize);
        }

        final int length = ((padded[0] & 0xFF) << 8) | (padded[1] & 0xFF);
        final int maxPayload = bucketSize - HEADER_SIZE;
        if (length < 0 || length > maxPayload) {
            throw new IllegalArgumentException("Invalid embedded message length: " + length + " (max " + maxPayload + ")");
        }

        final byte[] message = new byte[length];
        System.arraycopy(padded, HEADER_SIZE, message, 0, length);
        return message;
    }

    /**
     * Convenience: unpad to UTF-8 string.
     */
    public static String unpadToUtf8(byte[] padded) {
        return new String(unpad(padded), StandardCharsets.UTF_8);
    }

    /**
     * Returns the bucket size (512/1024/2048) that can contain this message.
     *
     * <p>Note: This accounts for the length header.
     */
    public static int chooseBucketSizeForMessageLength(int messageLength) {
        if (messageLength < 0) {
            throw new IllegalArgumentException("messageLength < 0");
        }

        final int total = HEADER_SIZE + messageLength;
        for (int bucket : BUCKETS) {
            if (total <= bucket) {
                return bucket;
            }
        }

        throw new IllegalArgumentException(
            "Message too large: " + messageLength + " bytes (max " + (BUCKET_2048 - HEADER_SIZE) + ")"
        );
    }

    private static boolean isAllowedBucket(int size) {
        return size == BUCKET_512 || size == BUCKET_1024 || size == BUCKET_2048;
    }
}
