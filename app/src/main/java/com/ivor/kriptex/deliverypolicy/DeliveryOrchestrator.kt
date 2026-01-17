package com.ivor.kriptex.deliverypolicy

data class DeliveryOrchestratorConfig(
    val connectingGraceMs: Long = 3_000L,
)

class DeliveryOrchestrator(
    private val policy: DeliveryPolicy = DefaultDeliveryPolicy,
    private val clock: Clock = MonotonicClock,
    private val config: DeliveryOrchestratorConfig = DeliveryOrchestratorConfig(),
) {
    private var lastConnectionState: ConnectionState? = null
    private var lastStateChangedAtMs: Long = 0L

    fun evaluate(connectionState: ConnectionState, prefs: DeliveryPreferences): DeliveryStrategy {
        val now = clock.nowMs()
        if (lastConnectionState != connectionState) {
            lastConnectionState = connectionState
            lastStateChangedAtMs = now
        }

        val base = policy.decide(connectionState, prefs)

        if (prefs.mode != DeliveryModePreference.AUTO) {
            return base
        }

        return when (connectionState) {
            ConnectionState.DirectConnecting -> {
                val elapsed = now - lastStateChangedAtMs
                if (base is ActiveDelivery && elapsed > config.connectingGraceMs) {
                    PassiveDelivery(PassiveDelivery.QueueReason.CONNECTING_TIMEOUT)
                } else {
                    base
                }
            }

            ConnectionState.DirectReady -> ActiveDelivery
            else -> base
        }
    }
}
