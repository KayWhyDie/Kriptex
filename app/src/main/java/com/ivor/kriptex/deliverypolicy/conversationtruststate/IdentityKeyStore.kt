package com.ivor.kriptex.deliverypolicy.conversationtruststate

/**
 * Storage abstraction for remote identity keys (Ed25519, 32 bytes) associated with logical peers.
 *
 * This is intentionally separate from the X3DH local identity key store.
 */
interface IdentityKeyStore {
    fun getPeerIdentityPublicKey(peerId: String): ByteArray?

    fun putPeerIdentityPublicKey(peerId: String, identityPublicKey: ByteArray)

    fun snapshot(): PersistedIdentityKeyStoreSnapshot

    fun restore(snapshot: PersistedIdentityKeyStoreSnapshot)
}

data class PersistedIdentityKeyStoreSnapshot(
    val version: Int = 1,
    val peers: List<PersistedPeerIdentityKey>,
)

data class PersistedPeerIdentityKey(
    val peerId: String,
    val identityPublicKey: ByteArray,
)

class InMemoryIdentityKeyStore : IdentityKeyStore {

    private val byPeerId = LinkedHashMap<String, ByteArray>()

    @Synchronized
    override fun getPeerIdentityPublicKey(peerId: String): ByteArray? = byPeerId[peerId]?.copyOf()

    @Synchronized
    override fun putPeerIdentityPublicKey(peerId: String, identityPublicKey: ByteArray) {
        require(peerId.isNotEmpty()) { "empty_peer_id" }
        require(identityPublicKey.size == 32) { "peer_identity_key_must_be_32_bytes" }
        byPeerId[peerId] = identityPublicKey.copyOf()
    }

    @Synchronized
    override fun snapshot(): PersistedIdentityKeyStoreSnapshot {
        return PersistedIdentityKeyStoreSnapshot(
            peers = byPeerId.entries.map { (peerId, key) ->
                PersistedPeerIdentityKey(peerId = peerId, identityPublicKey = key.copyOf())
            },
        )
    }

    @Synchronized
    override fun restore(snapshot: PersistedIdentityKeyStoreSnapshot) {
        byPeerId.clear()
        snapshot.peers.forEach { p ->
            byPeerId[p.peerId] = p.identityPublicKey.copyOf()
        }
    }
}
