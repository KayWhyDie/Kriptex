package com.ivor.kriptex.deliverypolicy.group.senderkey.dataplane

import com.ivor.kriptex.deliverypolicy.group.GroupId

interface SenderKeyGroupMessageDebugTrace {
    fun onEncrypt(groupId: GroupId, senderKeyId: Long, counter: Long, plaintextSize: Int, ciphertextSize: Int)

    fun onDecrypt(groupId: GroupId, senderKeyId: Long, counter: Long, ciphertextSize: Int, plaintextSize: Int)

    fun onDecryptRejected(groupId: GroupId, senderKeyId: Long, counter: Long, reason: String)
}

object NoOpSenderKeyGroupMessageDebugTrace : SenderKeyGroupMessageDebugTrace {
    override fun onEncrypt(groupId: GroupId, senderKeyId: Long, counter: Long, plaintextSize: Int, ciphertextSize: Int) = Unit
    override fun onDecrypt(groupId: GroupId, senderKeyId: Long, counter: Long, ciphertextSize: Int, plaintextSize: Int) = Unit
    override fun onDecryptRejected(groupId: GroupId, senderKeyId: Long, counter: Long, reason: String) = Unit
}
