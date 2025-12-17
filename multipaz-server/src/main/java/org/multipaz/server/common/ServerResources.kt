package org.multipaz.server.common

import kotlinx.io.bytestring.ByteString
import org.multipaz.rpc.backend.Resources

/**
 * [Resources] implementation that loads resources using JVM resource loading (typically from
 * a jar file).
 */
internal object ServerResources: Resources {
    override fun getRawResource(name: String): ByteString? {
        val stream = javaClass.getResourceAsStream("/resources/$name")
        return if (stream != null) ByteString(stream.readBytes()) else null
    }

    override fun getStringResource(name: String): String? {
        val stream = javaClass.getResourceAsStream("/resources/$name")
        return stream?.bufferedReader()?.readText()
    }
}