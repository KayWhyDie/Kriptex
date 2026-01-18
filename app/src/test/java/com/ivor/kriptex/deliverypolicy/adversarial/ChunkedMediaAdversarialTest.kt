package com.ivor.kriptex.deliverypolicy.adversarial

import com.ivor.kriptex.crypto.media.MediaAttachmentCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.min

private data class ReceiverSnapshot(
    val manifestVerified: Boolean,
    val chunkBitmap: ByteArray,
)

/**
 * Deterministic, adversarial tests for Phase 3 chunked media delivery.
 *
 * These are JVM tests (no device/robolectric). We use a MockContext that only
 * provides a real filesDir path.
 */
class ChunkedMediaAdversarialTest {

    private data class SenderArtifacts(
        val senderDir: File,
        val manifestCiphertext: File,
        val chunksDir: File,
        val chunkCiphertexts: List<File>,
        val totalChunks: Int,
        val chunkSize: Int,
        val plaintextSize: Long,
        val plaintextSha256: ByteArray,
    )

    private val deriveNonceMethod: Method by lazy {
        MediaAttachmentCrypto::class.java.getDeclaredMethod(
            "deriveDeterministicNonce",
            ByteArray::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            String::class.java,
        ).apply { isAccessible = true }
    }

    private val encryptWithNonceMethod: Method by lazy {
        MediaAttachmentCrypto::class.java.getDeclaredMethod(
            "encryptBytesToCiphertextWithNonce",
            ByteArray::class.java,
            File::class.java,
            ByteArray::class.java,
            ByteArray::class.java,
            ByteArray::class.java,
        ).apply { isAccessible = true }
    }

    private fun deriveNonce(mediaKey32: ByteArray, mediaId: String, chunkIndex: Int, purpose: String): ByteArray {
        @Suppress("UNCHECKED_CAST")
        return deriveNonceMethod.invoke(null, mediaKey32, mediaId, chunkIndex, purpose) as ByteArray
    }

    private fun encryptBytesWithNonce(plaintext: ByteArray, outCipher: File, mediaKey32: ByteArray, nonce24: ByteArray, aad: ByteArray) {
        encryptWithNonceMethod.invoke(null, plaintext, outCipher, mediaKey32, nonce24, aad)
    }

    private fun buildSenderArtifacts(
        senderDir: File,
        plaintext: ByteArray,
        mediaId: String,
        mediaKey32: ByteArray,
        chunkSize: Int,
    ): SenderArtifacts {
        val plaintextSha = MediaAttachmentCrypto.sha256Bytes(plaintext)
        val plaintextSize = plaintext.size.toLong()
        var totalChunks = ((plaintextSize + chunkSize.toLong() - 1L) / chunkSize.toLong()).toInt()
        if (totalChunks <= 0) totalChunks = 1

        val mediaDir = File(senderDir, mediaId)
        val chunksDir = File(mediaDir, "chunks")
        Files.createDirectories(chunksDir.toPath())

        val manifestCipher = File(mediaDir, "manifest.bin")
        val manifest = MediaAttachmentCrypto.MediaManifestV1(mediaId, totalChunks, plaintextSize, plaintextSha, chunkSize)
        val manifestPlain = manifest.encode()
        val manifestAad = MediaAttachmentCrypto.buildManifestAadV1(mediaId, totalChunks, plaintextSize, chunkSize, plaintextSha)
        val manifestNonce = deriveNonce(mediaKey32, mediaId, -1, "manifest")
        encryptBytesWithNonce(manifestPlain, manifestCipher, mediaKey32, manifestNonce, manifestAad)

        val chunkFiles = ArrayList<File>(totalChunks)
        for (i in 0 until totalChunks) {
            val start = i * chunkSize
            val end = min(plaintext.size, start + chunkSize)
            val chunkPlain = plaintext.copyOfRange(start, end)
            val out = File(chunksDir, "chunk_${i}.bin")
            val chunkAad = MediaAttachmentCrypto.buildChunkAadV1(mediaId, i, totalChunks, plaintextSha)
            val nonce = deriveNonce(mediaKey32, mediaId, i, "chunk")
            encryptBytesWithNonce(chunkPlain, out, mediaKey32, nonce, chunkAad)
            chunkFiles.add(out)
        }

        return SenderArtifacts(
            senderDir = senderDir,
            manifestCiphertext = manifestCipher,
            chunksDir = chunksDir,
            chunkCiphertexts = chunkFiles,
            totalChunks = totalChunks,
            chunkSize = chunkSize,
            plaintextSize = plaintextSize,
            plaintextSha256 = plaintextSha,
        )
    }

    private class ReceiverHarness(
        private val mediaId: String,
        private val mediaKey32: ByteArray,
        private val totalChunks: Int,
        private val chunkSize: Int,
        private val plaintextSize: Long,
        private val plaintextSha256: ByteArray,
        private val outputPlaintext: File,
        private val receiverDir: File,
    ) {
        private var manifestVerified: Boolean = false
        private var bitmap: ByteArray = ByteArray((totalChunks + 7) / 8)

        fun snapshot(): ReceiverSnapshot = ReceiverSnapshot(
            manifestVerified = manifestVerified,
            chunkBitmap = bitmap.copyOf(),
        )

        fun restore(snapshot: ReceiverSnapshot) {
            manifestVerified = snapshot.manifestVerified
            bitmap = snapshot.chunkBitmap.copyOf()
        }

        fun manifestDone(): Boolean = manifestVerified

        fun isChunkDone(index: Int): Boolean {
            require(index in 0 until totalChunks)
            val b = bitmap[index / 8].toInt() and 0xFF
            val mask = 1 shl (index % 8)
            return (b and mask) != 0
        }

        fun completedChunkCount(): Int {
            var c = 0
            for (i in 0 until totalChunks) if (isChunkDone(i)) c++
            return c
        }

        private fun receiverMediaDir(): File = File(receiverDir, mediaId)
        private fun receiverChunksDir(): File = File(receiverMediaDir(), "chunks")
        private fun receiverManifestFinal(): File = File(receiverMediaDir(), "manifest.bin")
        private fun receiverManifestTmp(): File = File(receiverMediaDir(), "manifest.bin.tmpdl")
        private fun receiverChunkFinal(index: Int): File = File(receiverChunksDir(), "chunk_${index}.bin")
        private fun receiverChunkTmp(index: Int): File = File(receiverChunksDir(), "chunk_${index}.bin.tmpdl")

        fun acceptManifestFrom(senderManifestCiphertext: File): Boolean {
            val tmp = receiverManifestTmp()
            val final = receiverManifestFinal()
            Files.createDirectories(tmp.parentFile.toPath())
            Files.copy(senderManifestCiphertext.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)

            return try {
                MediaAttachmentCrypto.decryptAndValidateManifest(
                    tmp,
                    mediaKey32,
                    mediaId,
                    totalChunks,
                    plaintextSize,
                    chunkSize,
                    plaintextSha256,
                )
                Files.move(tmp.toPath(), final.toPath(), StandardCopyOption.REPLACE_EXISTING)
                manifestVerified = true
                true
            } catch (t: Throwable) {
                // Rejected; do not mark progress.
                false
            } finally {
                // Best-effort cleanup of failed temp download.
                if (tmp.exists() && !manifestVerified) tmp.delete()
            }
        }

        fun acceptChunkFrom(senderChunkCiphertext: File, claimedIndex: Int): Boolean {
            require(claimedIndex in 0 until totalChunks)

            // Production pipeline never schedules chunks pre-manifest.
            if (!manifestVerified) return false

            val tmp = receiverChunkTmp(claimedIndex)
            val final = receiverChunkFinal(claimedIndex)
            Files.createDirectories(tmp.parentFile.toPath())
            Files.copy(senderChunkCiphertext.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)

            val expectedPlainLen = expectedChunkPlaintextLen(claimedIndex)
            return try {
                MediaAttachmentCrypto.validateChunkCiphertext(
                    tmp,
                    mediaKey32,
                    mediaId,
                    claimedIndex,
                    totalChunks,
                    plaintextSha256,
                    expectedPlainLen,
                )
                Files.move(tmp.toPath(), final.toPath(), StandardCopyOption.REPLACE_EXISTING)
                setBit(claimedIndex)
                true
            } catch (t: Throwable) {
                false
            } finally {
                // Best-effort cleanup of failed temp download.
                if (tmp.exists() && !isChunkDone(claimedIndex)) tmp.delete()
            }
        }

        fun isComplete(): Boolean {
            if (!manifestVerified) return false
            for (i in 0 until totalChunks) if (!isChunkDone(i)) return false
            return true
        }

        fun assembleIfComplete(): Boolean {
            if (!isComplete()) return false
            val tmpOut = File(outputPlaintext.parentFile, outputPlaintext.name + ".dec")
            if (tmpOut.exists()) tmpOut.delete()
            Files.createDirectories(tmpOut.parentFile.toPath())

            try {
                FileOutputStream(tmpOut).use { fos ->
                    for (i in 0 until totalChunks) {
                        val chunk = receiverChunkFinal(i)
                        MediaAttachmentCrypto.decryptChunkToStream(
                            chunk,
                            fos,
                            mediaKey32,
                            mediaId,
                            i,
                            totalChunks,
                            plaintextSha256,
                        )
                    }
                }

                assertEquals("plaintext size must match", plaintextSize, tmpOut.length())
                val sha = MediaAttachmentCrypto.sha256File(tmpOut)
                assertArrayEquals("plaintext sha256 must match", plaintextSha256, sha)

                Files.move(tmpOut.toPath(), outputPlaintext.toPath(), StandardCopyOption.REPLACE_EXISTING)
                return true
            } finally {
                if (tmpOut.exists() && !outputPlaintext.exists()) tmpOut.delete()
            }
        }

        private fun expectedChunkPlaintextLen(chunkIndex: Int): Int {
            val offset = chunkIndex.toLong() * chunkSize.toLong()
            val remaining = plaintextSize - offset
            return min(chunkSize.toLong(), remaining).toInt()
        }

        private fun setBit(index: Int) {
            val i = index / 8
            val bit = 1 shl (index % 8)
            val b = bitmap[i].toInt() and 0xFF
            bitmap[i] = (b or bit).toByte()
        }
    }

    @Test
    fun reorder_duplicate_and_resume_completes_and_produces_exact_plaintext() {
        val senderRoot = Files.createTempDirectory("kriptex_sender").toFile()
        val receiverRoot = Files.createTempDirectory("kriptex_receiver").toFile()

        val mediaId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val mediaKey = ByteArray(32) { i -> (0x40 + i).toByte() }
        val chunkSize = 1024

        val plaintext = ByteArray(19_777) { i -> (i * 13).toByte() }
        val sender = buildSenderArtifacts(
            senderDir = senderRoot,
            plaintext = plaintext,
            mediaId = mediaId,
            mediaKey32 = mediaKey,
            chunkSize = chunkSize,
        )

        val out = File(receiverRoot, "out_plain.bin")
        val r1 = ReceiverHarness(
            mediaId = mediaId,
            mediaKey32 = mediaKey,
            totalChunks = sender.totalChunks,
            chunkSize = sender.chunkSize,
            plaintextSize = plaintext.size.toLong(),
            plaintextSha256 = sender.plaintextSha256,
            outputPlaintext = out,
            receiverDir = receiverRoot,
        )

        // Deliver a chunk before manifest; must not advance.
        assertFalse(r1.acceptChunkFrom(sender.chunkCiphertexts[0], claimedIndex = 0))
        assertFalse(r1.manifestDone())
        assertEquals(0, r1.completedChunkCount())
        assertFalse(out.exists())

        // Manifest first.
        assertTrue(r1.acceptManifestFrom(sender.manifestCiphertext))
        assertTrue(r1.manifestDone())

        // CUT: withhold chunk 0 until the end.
        for (i in (sender.totalChunks - 1) downTo 1) {
            // DUPLICATE: deliver each chunk twice.
            assertTrue(r1.acceptChunkFrom(sender.chunkCiphertexts[i], claimedIndex = i))
            assertTrue(
                "duplicate delivery should be idempotent",
                r1.acceptChunkFrom(sender.chunkCiphertexts[i], claimedIndex = i),
            )
        }
        assertFalse("still missing chunk 0", r1.isComplete())
        assertFalse("no plaintext until complete", out.exists())

        // Simulate app restart: snapshot -> new harness -> continue.
        val snap = r1.snapshot()
        val r2 = ReceiverHarness(
            mediaId = mediaId,
            mediaKey32 = mediaKey,
            totalChunks = sender.totalChunks,
            chunkSize = sender.chunkSize,
            plaintextSize = plaintext.size.toLong(),
            plaintextSha256 = sender.plaintextSha256,
            outputPlaintext = out,
            receiverDir = receiverRoot,
        )
        r2.restore(snap)
        assertTrue(r2.manifestDone())
        assertFalse(r2.isChunkDone(0))

        assertTrue(r2.acceptChunkFrom(sender.chunkCiphertexts[0], claimedIndex = 0))
        assertTrue(r2.isComplete())
        assertTrue(r2.assembleIfComplete())
        assertTrue(out.exists())
        assertArrayEquals(plaintext, Files.readAllBytes(out.toPath()))
    }

    @Test
    fun replay_rejected_when_ciphertext_swapped_between_indices() {
        val senderRoot = Files.createTempDirectory("kriptex_sender").toFile()
        val receiverRoot = Files.createTempDirectory("kriptex_receiver").toFile()

        val mediaId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        val mediaKey = ByteArray(32) { 9 }
        val chunkSize = 512
        val plaintext = ByteArray(4096) { i -> (i xor 0xA5).toByte() }
        val sender = buildSenderArtifacts(
            senderDir = senderRoot,
            plaintext = plaintext,
            mediaId = mediaId,
            mediaKey32 = mediaKey,
            chunkSize = chunkSize,
        )

        val out = File(receiverRoot, "out_plain.bin")
        val r = ReceiverHarness(
            mediaId = mediaId,
            mediaKey32 = mediaKey,
            totalChunks = sender.totalChunks,
            chunkSize = sender.chunkSize,
            plaintextSize = plaintext.size.toLong(),
            plaintextSha256 = sender.plaintextSha256,
            outputPlaintext = out,
            receiverDir = receiverRoot,
        )

        assertTrue(r.acceptManifestFrom(sender.manifestCiphertext))

        // Attempt replay/substitution: feed ciphertext for chunk 0 but claim it is chunk 1.
        val swappedAccepted = r.acceptChunkFrom(sender.chunkCiphertexts[0], claimedIndex = 1)
        assertFalse("nonce/AAD binding must reject swapped chunk", swappedAccepted)
        assertFalse(r.isChunkDone(1))
        assertEquals(0, r.completedChunkCount())

        // Correct chunk 1 should be accepted.
        assertTrue(r.acceptChunkFrom(sender.chunkCiphertexts[1], claimedIndex = 1))
        assertTrue(r.isChunkDone(1))
    }

    @Test
    fun cut_reorder_duplicate_drains_to_completion_with_bounded_state() {
        val senderRoot = Files.createTempDirectory("kriptex_sender").toFile()
        val receiverRoot = Files.createTempDirectory("kriptex_receiver").toFile()

        val mediaId = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        val mediaKey = ByteArray(32) { i -> (i * 7).toByte() }
        val chunkSize = 256

        val plaintext = ByteArray(10_000) { i -> (i * 3 + 1).toByte() }
        val sender = buildSenderArtifacts(
            senderDir = senderRoot,
            plaintext = plaintext,
            mediaId = mediaId,
            mediaKey32 = mediaKey,
            chunkSize = chunkSize,
        )

        val out = File(receiverRoot, "out_plain.bin")
        val r = ReceiverHarness(
            mediaId = mediaId,
            mediaKey32 = mediaKey,
            totalChunks = sender.totalChunks,
            chunkSize = sender.chunkSize,
            plaintextSize = plaintext.size.toLong(),
            plaintextSha256 = sender.plaintextSha256,
            outputPlaintext = out,
            receiverDir = receiverRoot,
        )

        assertTrue(r.acceptManifestFrom(sender.manifestCiphertext))

        // CUT: deliver odd chunks first; with duplicates; then even chunks in reverse.
        val odd = (1 until sender.totalChunks step 2).toList()
        val even = (0 until sender.totalChunks step 2).toList().reversed()

        for (i in odd) {
            assertTrue(r.acceptChunkFrom(sender.chunkCiphertexts[i], claimedIndex = i))
            assertTrue(r.acceptChunkFrom(sender.chunkCiphertexts[i], claimedIndex = i))
        }

        assertFalse(r.isComplete())
        assertFalse(out.exists())

        for (i in even) {
            assertTrue(r.acceptChunkFrom(sender.chunkCiphertexts[i], claimedIndex = i))
        }

        assertTrue(r.isComplete())
        assertTrue(r.assembleIfComplete())
        assertArrayEquals(plaintext, Files.readAllBytes(out.toPath()))

        // Bounded state: receiver media directory contains at most manifest + chunks (+ a small slack).
        val mediaDir = File(receiverRoot, mediaId)
        val fileCount = countFiles(mediaDir)
        assertTrue("state should be bounded (fileCount=$fileCount)", fileCount <= sender.totalChunks + 4)
    }

    private fun countFiles(dir: File): Int {
        if (!dir.exists()) return 0
        var count = 0
        val stack = ArrayDeque<File>()
        stack.add(dir)
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            val kids = f.listFiles().orEmpty()
            for (k in kids) {
                if (k.isDirectory) stack.add(k) else count++
            }
        }
        return count
    }
}
