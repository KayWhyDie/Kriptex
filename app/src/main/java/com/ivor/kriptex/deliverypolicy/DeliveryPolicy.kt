package com.ivor.kriptex.deliverypolicy

sealed interface DeliveryPolicy {
    fun decide(connectionState: ConnectionState, prefs: DeliveryPreferences): DeliveryStrategy
}

object DefaultDeliveryPolicy : DeliveryPolicy {
    override fun decide(connectionState: ConnectionState, prefs: DeliveryPreferences): DeliveryStrategy {
        return when (prefs.mode) {
            DeliveryModePreference.PASSIVE_ONLY -> PassiveDelivery(PassiveDelivery.QueueReason.USER_PREFERENCE)
            DeliveryModePreference.ACTIVE_ONLY -> ActiveDelivery
            DeliveryModePreference.AUTO -> decideAuto(connectionState, prefs)
        }
    }

    private fun decideAuto(connectionState: ConnectionState, prefs: DeliveryPreferences): DeliveryStrategy {
        return when (connectionState) {
            ConnectionState.DirectReady -> {
                if (prefs.preferRealtime) ActiveDelivery else PassiveDelivery(PassiveDelivery.QueueReason.USER_PREFERENCE)
            }

            ConnectionState.DirectConnecting -> {
                if (prefs.preferRealtime) ActiveDelivery else PassiveDelivery(PassiveDelivery.QueueReason.USER_PREFERENCE)
            }

            ConnectionState.RelayReady -> {
                if (prefs.allowRelay) {
                    PassiveDelivery(PassiveDelivery.QueueReason.RELAY_ONLY)
                } else {
                    PassiveDelivery(PassiveDelivery.QueueReason.NO_ROUTE)
                }
            }

            ConnectionState.PeerOffline -> PassiveDelivery(PassiveDelivery.QueueReason.PEER_OFFLINE)
            ConnectionState.Unknown -> PassiveDelivery(PassiveDelivery.QueueReason.UNKNOWN)
        }
    }
}
