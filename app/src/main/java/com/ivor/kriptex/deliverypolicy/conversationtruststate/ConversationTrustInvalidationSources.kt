package com.ivor.kriptex.deliverypolicy.conversationtruststate

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class ConversationTrustInvalidationSources(
    val identityKeyStore: Flow<Unit> = emptyFlow(),
    val sessionStore: Flow<Unit> = emptyFlow(),
    val groupStore: Flow<Unit> = emptyFlow(),
    val senderKeyStore: Flow<Unit> = emptyFlow(),
    val senderKeyDistributionStore: Flow<Unit> = emptyFlow(),
    val manual: Flow<Unit> = emptyFlow(),
)
