package org.multipaz.mdoc.connectionmethod

actual suspend fun MdocConnectionMethod.Ble.Companion.isChannelSoundingAvailable(): Boolean {
    return false
}
