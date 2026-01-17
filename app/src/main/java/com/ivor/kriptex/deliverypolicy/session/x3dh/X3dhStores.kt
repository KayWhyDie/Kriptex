package com.ivor.kriptex.deliverypolicy.session.x3dh

/** Storage abstraction for long-term identity key material (Ed25519). */
interface X3dhIdentityKeyStore {
    fun load(): X3dhIdentityKeyPair?
    fun save(identityKeyPair: X3dhIdentityKeyPair)
}

/** Storage abstraction for X3DH prekeys (signed prekey + optional one-time prekeys). */
interface X3dhPreKeyStore {
    /** Returns the currently published signed prekey (must exist). */
    fun getSignedPreKey(preKeyId: Int): X3dhSignedPreKey?

    /** Returns the currently published signed prekey id (must exist). */
    fun currentSignedPreKeyId(): Int

    /** Atomically consumes the one-time prekey id and returns the record, or null if already consumed/missing. */
    fun consumeOneTimePreKey(preKeyId: Int): X3dhOneTimePreKey?

    /** Returns a publishable prekey bundle. */
    fun buildBundle(identityPublicKey: ByteArray): X3dhPreKeyBundle
}

/** Simple persistence DTO for tests / app-level persistence. */
data class PersistedX3dhIdentity(
    val seed: ByteArray,
    val publicKey: ByteArray,
)

data class PersistedX3dhSignedPreKey(
    val preKeyId: Int,
    val privateKey: ByteArray,
    val publicKey: ByteArray,
    val signature: ByteArray,
    val createdAtElapsedMs: Long,
)

data class PersistedX3dhOneTimePreKey(
    val preKeyId: Int,
    val privateKey: ByteArray,
    val publicKey: ByteArray,
    val createdAtElapsedMs: Long,
    val consumed: Boolean,
)

data class PersistedX3dhPreKeyStoreSnapshot(
    val signedPreKeyId: Int,
    val signedPreKey: PersistedX3dhSignedPreKey,
    val oneTimePreKeys: List<PersistedX3dhOneTimePreKey>,
)

class InMemoryX3dhIdentityKeyStore : X3dhIdentityKeyStore {
    private var stored: X3dhIdentityKeyPair? = null

    @Synchronized
    override fun load(): X3dhIdentityKeyPair? = stored

    @Synchronized
    override fun save(identityKeyPair: X3dhIdentityKeyPair) {
        if (stored != null) throw IllegalStateException("identity_already_exists")
        stored = identityKeyPair
    }

    @Synchronized
    fun snapshot(): PersistedX3dhIdentity? {
        val s = stored ?: return null
        return PersistedX3dhIdentity(seed = s.seed, publicKey = s.publicKey)
    }

    @Synchronized
    fun restore(snapshot: PersistedX3dhIdentity?) {
        stored = snapshot?.let { X3dhIdentityKeyPair(seed = it.seed, publicKey = it.publicKey) }
    }
}

class InMemoryX3dhPreKeyStore(
    private val signedPreKey: X3dhSignedPreKey,
    private val oneTimePreKeys: List<X3dhOneTimePreKey> = emptyList(),
) : X3dhPreKeyStore {

    private val signedById = linkedMapOf(signedPreKey.preKeyId to signedPreKey)

    private data class OneTimeEntry(val preKey: X3dhOneTimePreKey, var consumed: Boolean)

    private val oneTimeById = LinkedHashMap<Int, OneTimeEntry>().apply {
        oneTimePreKeys.forEach { put(it.preKeyId, OneTimeEntry(it, consumed = false)) }
    }

    @Synchronized
    override fun getSignedPreKey(preKeyId: Int): X3dhSignedPreKey? = signedById[preKeyId]

    @Synchronized
    override fun currentSignedPreKeyId(): Int = signedPreKey.preKeyId

    @Synchronized
    override fun consumeOneTimePreKey(preKeyId: Int): X3dhOneTimePreKey? {
        val e = oneTimeById[preKeyId] ?: return null
        if (e.consumed) return null
        e.consumed = true
        return e.preKey
    }

    @Synchronized
    override fun buildBundle(identityPublicKey: ByteArray): X3dhPreKeyBundle {
        val opk = oneTimeById.values.firstOrNull { !it.consumed }?.preKey
        return X3dhPreKeyBundle(
            identityPublicKey = identityPublicKey,
            signedPreKeyId = signedPreKey.preKeyId,
            signedPreKeyPublicKey = signedPreKey.publicKey,
            signedPreKeySignature = signedPreKey.signature,
            oneTimePreKeyId = opk?.preKeyId,
            oneTimePreKeyPublicKey = opk?.publicKey,
        )
    }

    @Synchronized
    fun snapshot(): PersistedX3dhPreKeyStoreSnapshot {
        return PersistedX3dhPreKeyStoreSnapshot(
            signedPreKeyId = signedPreKey.preKeyId,
            signedPreKey = PersistedX3dhSignedPreKey(
                preKeyId = signedPreKey.preKeyId,
                privateKey = signedPreKey.privateKey,
                publicKey = signedPreKey.publicKey,
                signature = signedPreKey.signature,
                createdAtElapsedMs = signedPreKey.createdAtElapsedMs,
            ),
            oneTimePreKeys = oneTimeById.values.map {
                PersistedX3dhOneTimePreKey(
                    preKeyId = it.preKey.preKeyId,
                    privateKey = it.preKey.privateKey,
                    publicKey = it.preKey.publicKey,
                    createdAtElapsedMs = it.preKey.createdAtElapsedMs,
                    consumed = it.consumed,
                )
            },
        )
    }

    @Synchronized
    fun restore(snapshot: PersistedX3dhPreKeyStoreSnapshot) {
        // No auto-regeneration on restore: restore exactly what was captured.
        signedById.clear()
        val spk = snapshot.signedPreKey
        signedById[spk.preKeyId] = X3dhSignedPreKey(
            preKeyId = spk.preKeyId,
            privateKey = spk.privateKey,
            publicKey = spk.publicKey,
            signature = spk.signature,
            createdAtElapsedMs = spk.createdAtElapsedMs,
        )
        oneTimeById.clear()
        snapshot.oneTimePreKeys.forEach {
            oneTimeById[it.preKeyId] = OneTimeEntry(
                preKey = X3dhOneTimePreKey(
                    preKeyId = it.preKeyId,
                    privateKey = it.privateKey,
                    publicKey = it.publicKey,
                    createdAtElapsedMs = it.createdAtElapsedMs,
                ),
                consumed = it.consumed,
            )
        }
    }
}
