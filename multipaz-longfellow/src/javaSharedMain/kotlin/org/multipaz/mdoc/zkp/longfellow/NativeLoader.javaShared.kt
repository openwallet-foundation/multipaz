package org.multipaz.mdoc.zkp.longfellow

/**
 * Loads native libraries.
 *
 * Used to allow each Java-based system (Android, JVM) to implement this method per their needs.
 * Returns the path or library name to be loaded by JNA.
 */
internal expect object NativeLoader {
    fun loadLibrary(libraryName: String): String?
}
