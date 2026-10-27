package dz.hirz.shared.signal

import kotlinx.serialization.Serializable

/** How a position was obtained; drives the accuracy we trust (design §3). */
@Serializable
enum class FixSource { GNSS, WIFI, CELL, BLE_RELAY, SMS }
