package com.ivor.kriptex.crypto;

import java.security.SecureRandom;

/**
 * Versioned binary message envelope with static-size padding.
 *
 * <p>Envelope format (big-endian) before padding:</p>
 * <pre>
 * version:        1 byte   (currently 0x01)
 * message_type:   1 byte   (preserved but not interpreted here)
 * payload_length: 2 bytes  (uint16, big-endian)
 * payload:        N bytes
 * padding:        random bytes until bucket is reached
 * </pre>
 *
 * <p>Total output size is always exactly one of: 512, 1024, 2048 bytes.</p>
 *
 * <p>This is designed to be encrypted later as a single blob (e.g., AEAD).</p>
 */
final class MessageEnvelope {

    public static final byte VERSION_1 = 0x01;

    private static final int HEADER_SIZE = 4;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private MessageEnvelope() {
        // no instances
    }

    /**
     * Packs {@code payload} into a versioned envelope and pads it to a fixed bucket size.
     *
     * @param messageType 1-byte type identifier (preserved verbatim)
     * @param payload raw bytes
     * @return fixed-size byte array (512/1024/2048)
     */
    static byte[] pack(byte messageType, byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload is null");
        }

        int payloadLength = payload.length;
        int maxPayload = StaticLengthPadding.BUCKET_2048 - HEADER_SIZE;
        if (payloadLength > maxPayload) {
            throw new IllegalArgumentException("payload too large for largest bucket: " + payloadLength + " bytes");
        }

        int total = HEADER_SIZE + payloadLength;
        int bucketSize = StaticLengthPadding.chooseBucketSizeForTotalLength(total);

        byte[] out = new byte[bucketSize];

        // Header
        out[0] = VERSION_1;
        out[1] = messageType;
        out[2] = (byte) ((payloadLength >>> 8) & 0xFF);
        out[3] = (byte) (payloadLength & 0xFF);

        // Payload
        System.arraycopy(payload, 0, out, HEADER_SIZE, payloadLength);

        // Random padding
        int paddingStart = HEADER_SIZE + payloadLength;
        if (paddingStart < out.length) {
            byte[] padding = new byte[out.length - paddingStart];
            SECURE_RANDOM.nextBytes(padding);
            System.arraycopy(padding, 0, out, paddingStart, padding.length);
        }

        return out;
    }

    /**
     * Unpacks a fixed-size envelope produced by {@link #pack(byte, byte[])}.
     *
     * @param padded envelope whose length is exactly 512/1024/2048
     * @return unpacked message type + payload
     */
    static Unpacked unpack(byte[] padded) {
        if (padded == null) {
            throw new IllegalArgumentException("padded is null");
        }

        int bucketSize = padded.length;
        if (!StaticLengthPadding.isValidBucketSize(bucketSize)) {
            throw new IllegalArgumentException("invalid bucket size: " + bucketSize);
        }
        if (bucketSize < HEADER_SIZE) {
            throw new IllegalArgumentException("invalid padded input");
        }

        byte version = padded[0];
        if (version != VERSION_1) {
            throw new IllegalArgumentException("unsupported envelope version: " + (version & 0xFF));
        }

        byte messageType = padded[1];
        int payloadLength = ((padded[2] & 0xFF) << 8) | (padded[3] & 0xFF);
        if (payloadLength < 0 || payloadLength > (bucketSize - HEADER_SIZE)) {
            throw new IllegalArgumentException("invalid payload length in header: " + payloadLength);
        }

        byte[] payload = new byte[payloadLength];
        System.arraycopy(padded, HEADER_SIZE, payload, 0, payloadLength);

        return new Unpacked(messageType, payload);
    }

    static final class Unpacked {
        public final byte messageType;
        public final byte[] payload;

        private Unpacked(byte messageType, byte[] payload) {
            this.messageType = messageType;
            this.payload = payload;
        }
    }
}
