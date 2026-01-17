package com.ivor.kriptex.deliverypolicy.conversationstate

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class ConversationStateInvalidationSources(
    val messageStore: Flow<Unit> = emptyFlow(),
    val ledger: Flow<Unit> = emptyFlow(),
    val groupStore: Flow<Unit> = emptyFlow(),
    val senderKeyStore: Flow<Unit> = emptyFlow(),
    /** Optional external invalidation source for callers that already have a change bus. */
    val manual: Flow<Unit> = emptyFlow(),
)
