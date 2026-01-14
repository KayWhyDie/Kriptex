package com.ivor.kriptex.crypto;

import java.security.GeneralSecurityException;

import javax.crypto.SecretKey;

/**
 * Strict transport-layer codec.
 *
 * <p>This defines the ONLY valid send/receive pipeline:</p>
 * <pre>
 * encode: payload -> MessageEnvelope.pack -> CryptoBox.encrypt -> transport blob
 * decode: transport blob -> CryptoBox.decrypt -> MessageEnvelope.unpack -> payload
 * </pre>
 *
 * <p>Transport input is treated as fully hostile. All failures throw and abort processing.</p>
 */
public final class TransportCodec {

    private TransportCodec() {
        // no instances
    }

    /**
     * Encodes a message for transport.
     *
     * <p>Returns ONLY the ciphertext blob. No plaintext or padded envelope is exposed.</p>
     */
    public static byte[] encodeForTransport(byte messageType, byte[] payload, SecretKey key)
            throws GeneralSecurityException {
        if (payload == null) {
            throw new IllegalArgumentException("payload is null");
        }
        if (key == null) {
            throw new IllegalArgumentException("key is null");
        }

        byte[] paddedEnvelope = MessageEnvelope.pack(messageType, payload);
        return CryptoBox.encrypt(paddedEnvelope, key);
    }

    /**
     * Decodes a hostile transport blob.
     *
     * <p>Authentication is verified before any plaintext is returned.</p>
     */
    public static DecodedMessage decodeFromTransport(byte[] transportBlob, SecretKey key)
            throws GeneralSecurityException {
        if (transportBlob == null) {
            throw new IllegalArgumentException("transportBlob is null");
        }
        if (key == null) {
            throw new IllegalArgumentException("key is null");
        }

        byte[] paddedEnvelope = CryptoBox.decrypt(transportBlob, key);
        MessageEnvelope.Unpacked unpacked = MessageEnvelope.unpack(paddedEnvelope);
        return new DecodedMessage(unpacked.messageType, unpacked.payload);
    }

    public static final class DecodedMessage {
        public final byte messageType;
        public final byte[] payload;

        private DecodedMessage(byte messageType, byte[] payload) {
            this.messageType = messageType;
            this.payload = payload;
        }
    }
}
