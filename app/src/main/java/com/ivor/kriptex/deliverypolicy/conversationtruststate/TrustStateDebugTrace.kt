package com.ivor.kriptex.deliverypolicy.conversationtruststate

interface TrustStateDebugTrace {
    fun onIssueDetected(conversationId: String, issue: TrustIssue, reason: String)

    fun onTrustDowngraded(conversationId: String, from: TrustLevel, to: TrustLevel, reason: String)

    fun onUserAcknowledged(conversationId: String, acknowledgedIssueCount: Int, reason: String)
}

object NoOpTrustStateDebugTrace : TrustStateDebugTrace {
    override fun onIssueDetected(conversationId: String, issue: TrustIssue, reason: String) = Unit

    override fun onTrustDowngraded(conversationId: String, from: TrustLevel, to: TrustLevel, reason: String) = Unit

    override fun onUserAcknowledged(conversationId: String, acknowledgedIssueCount: Int, reason: String) = Unit
}
