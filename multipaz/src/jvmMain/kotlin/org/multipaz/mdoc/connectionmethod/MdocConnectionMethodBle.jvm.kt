package org.multipaz.mdoc.connectionmethod

actual suspend fun MdocConnectionMethodBle.Companion.isChannelSoundingAvailable(): Boolean {
    return false
}
