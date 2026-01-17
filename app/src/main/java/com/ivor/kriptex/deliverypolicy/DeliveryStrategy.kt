package com.ivor.kriptex.deliverypolicy

enum class DeliveryMode {
    ACTIVE,
    PASSIVE,
}

sealed interface DeliveryStrategy {
    val mode: DeliveryMode
    val isRealtime: Boolean
}

data object ActiveDelivery : DeliveryStrategy {
    override val mode: DeliveryMode = DeliveryMode.ACTIVE
    override val isRealtime: Boolean = true
}

data class PassiveDelivery(
    val queueReason: QueueReason,
) : DeliveryStrategy {
    override val mode: DeliveryMode = DeliveryMode.PASSIVE
    override val isRealtime: Boolean = false

    enum class QueueReason {
        UNKNOWN,
        PEER_OFFLINE,
        RELAY_ONLY,
        NO_ROUTE,
        USER_PREFERENCE,
        CONNECTING_TIMEOUT,
    }
}
