package com.ivor.kriptex.deliverypolicy

enum class DeliveryModePreference {
    AUTO,
    ACTIVE_ONLY,
    PASSIVE_ONLY,
}

data class DeliveryPreferences(
    val mode: DeliveryModePreference = DeliveryModePreference.AUTO,
    val allowRelay: Boolean = true,
    val preferRealtime: Boolean = true,
)
