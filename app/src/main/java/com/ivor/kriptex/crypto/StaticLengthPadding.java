package com.ivor.kriptex.crypto;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Static-length padding for metadata-hiding.
 *
 * <p>Format (all big-endian):</p>
 * <pre>
 * [2-byte unsigned message length][message bytes][cryptographically-random padding bytes]
 * </pre>
 *
 * <p>The output length is always exactly one of: 512, 1024, 2048 bytes.</p>
 */
public final class StaticLengthPadding {

    public static final int BUCKET_512 = 512;
    public static final int BUCKET_1024 = 1024;
    public static final int BUCKET_2048 = 2048;

    private static final int HEADER_SIZE = 2;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private StaticLengthPadding() {
        // no instances
    }

    /**
     * Pads {@code message} to a static bucket size.
     *
     * @param message raw message bytes
     * @return a new byte array whose length is exactly 512/1024/2048
     */
    public static byte[] pad(byte[] message) {
        if (message == null) {
            throw new IllegalArgumentException("message is null");
        }

        int messageLength = message.length;
        if (messageLength > (BUCKET_2048 - HEADER_SIZE)) {
            throw new IllegalArgumentException(
                    "message too large for largest bucket: " + messageLength + " bytes");
        }

        int bucketSize = chooseBucketSizeForMessageLength(messageLength);
        byte[] out = new byte[bucketSize];

        // Header: unsigned 16-bit big-endian message length
        out[0] = (byte) ((messageLength >>> 8) & 0xFF);
        out[1] = (byte) (messageLength & 0xFF);

        // Message
        System.arraycopy(message, 0, out, HEADER_SIZE, messageLength);

        // Padding
        int paddingStart = HEADER_SIZE + messageLength;
        if (paddingStart < out.length) {
            byte[] padding = new byte[out.length - paddingStart];
            SECURE_RANDOM.nextBytes(padding);
            System.arraycopy(padding, 0, out, paddingStart, padding.length);
        }

        return out;
    }

    public static byte[] padUtf8(String message) {
        if (message == null) {
            throw new IllegalArgumentException("message is null");
        }
        return pad(message.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Removes static-length padding produced by {@link #pad(byte[])}.
     *
     * @param padded a byte array whose length is exactly 512/1024/2048
     * @return the original message bytes
     */
    public static byte[] unpad(byte[] padded) {
        if (padded == null) {
            throw new IllegalArgumentException("padded is null");
        }

        int bucketSize = padded.length;
        if (!isValidBucketSize(bucketSize)) {
            throw new IllegalArgumentException("invalid bucket size: " + bucketSize);
        }
        if (bucketSize < HEADER_SIZE) {
            throw new IllegalArgumentException("invalid padded input");
        }

        int messageLength = ((padded[0] & 0xFF) << 8) | (padded[1] & 0xFF);
        if (messageLength < 0 || messageLength > (bucketSize - HEADER_SIZE)) {
            throw new IllegalArgumentException("invalid message length in header: " + messageLength);
        }

        byte[] message = new byte[messageLength];
        System.arraycopy(padded, HEADER_SIZE, message, 0, messageLength);
        return message;
    }

    public static String unpadToUtf8(byte[] padded) {
        return new String(unpad(padded), StandardCharsets.UTF_8);
    }

    /**
     * Chooses the smallest bucket that can fit {@code HEADER_SIZE + messageLength}.
     */
    static int chooseBucketSizeForMessageLength(int messageLength) {
        int total = HEADER_SIZE + messageLength;
        return chooseBucketSizeForTotalLength(total);
    }

    static boolean isValidBucketSize(int bucketSize) {
        return bucketSize == BUCKET_512 || bucketSize == BUCKET_1024 || bucketSize == BUCKET_2048;
    }

    static int chooseBucketSizeForTotalLength(int totalLength) {
        if (totalLength <= BUCKET_512) {
            return BUCKET_512;
        }
        if (totalLength <= BUCKET_1024) {
            return BUCKET_1024;
        }
        if (totalLength <= BUCKET_2048) {
            return BUCKET_2048;
        }
        throw new IllegalArgumentException("message too large");
    }
}
