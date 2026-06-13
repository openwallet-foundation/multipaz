package org.multipaz.records.data

/**
 * Thrown when the requested [Identity] cannot be found in the storage.
 */
class IdentityNotFoundException: Exception("Identity not found")