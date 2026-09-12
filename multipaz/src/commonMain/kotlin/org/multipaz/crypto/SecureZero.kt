package org.multipaz.crypto

/**
 * Securely zeroes out the contents of this byte array in memory.
 *
 * This function takes platform-specific measures to prevent compiler optimizations
 * (such as dead store elimination) from skipping the zeroing writes.
 */
expect fun ByteArray.secureZero()
