package org.multipaz.tags

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.CborArray
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItem

/**
 * A persistent key-value store for arbitrary metadata attributes, backed by CBOR.
 *
 * This class provides a type-safe mechanism to attach string, integer, long, boolean,
 * and byte string properties (and lists thereof).
 *
 * Changes to the tags are transactional and performed via the [edit] function.
 * When an edit block completes successfully, the entire map is re-serialized to CBOR
 * and persisted using the provided [saveFn].
 *
 * **Usage Example:**
 * ```kotlin
 * val tags = Tags(existingCborData) { encoded -> saveToDisk(encoded) }
 *
 * // Reading values (Null-safe)
 * val name: String? = tags.get<String>("name")
 * val roles: List<String>? = tags.getList<String>("roles")
 *
 * // Writing values (Transactional)
 * tags.edit {
 *     put("name", "Alice")
 *     put("login_count", 42)
 *     putList("roles", listOf("admin", "editor"))
 * }
 * ```
 *
 * @property data The serialized representation of the tag or `null` for empty.
 * @property editLock The lock to take when editing, to serialize edits from multiple concurrent coroutines.
 * @property saveFn A suspending function invoked with the edited data, whenever changes are committed.
 *   Note that [editLock] is held while this is called. If the closure returned is non-null it will be
 *   invoked right after this but without holding [editLock]. This can be used to e.g. emit change events
 *   without any locks being held.
 */
class Tags(
    data: DataItem?,
    private val editLock: Mutex = Mutex(),
    private val saveFn: suspend (encodedData: DataItem) -> (suspend () -> Unit)? = { null }
) {
    internal var _tags: MutableMap<String, DataItem> = if (data != null) {
        data.asMap.mapKeys { it.key.asTstr }.toMutableMap()
    } else {
        mutableMapOf()
    }

    /**
     * Returns a snapshot of all keys currently present in the tags.
     */
    val keys: Set<String> get() = _tags.keys

    /**
     * Checks if a specific key exists.
     */
    fun hasKey(key: String): Boolean = _tags.containsKey(key)

    /**
     * Internal helper accessed by the inline functions below.
     *
     * @PublishedApi makes this visible to the inlined code of the caller.
     */
    @PublishedApi
    internal fun getRawDataItem(key: String): DataItem? = _tags[key]

    /**
     * Retrieves a scalar value for the given [key].
     *
     * Supported types [T] are: [String], [Int], [Long], [Boolean], and [kotlinx.io.bytestring.ByteString].
     * Returns `null` if the key is missing.
     *
     * @param key the name of the key to get.
     * @returns the value or `null` if not found.
     * @throws IllegalArgumentException if [T] is not one of the supported types.
     */
    inline fun <reified T> get(key: String): T? {
        val item = getRawDataItem(key) ?: return null

        return when (T::class) {
            String::class -> item.asTstr as T
            Int::class -> item.asNumber.toInt() as T
            Long::class -> item.asNumber as T
            ByteString::class -> ByteString(item.asBstr) as T
            Boolean::class -> item.asBoolean as T
            else -> throw IllegalArgumentException("Unsupported type: ${T::class.simpleName}")
        }
    }

    /**
     * Retrieves a list of values for the given [key].
     *
     * Supported element types [T] are: [String], [Int], [Long], [Boolean], and [ByteString].
     * Returns `null` if the key is missing.
     *
     * @param key the name of the key to get.
     * @returns the value or `null` if not found.
     * @throws IllegalArgumentException if [T] is not one of the supported types.
     */
    inline fun <reified T> getList(key: String): List<T>? {
        val item = getRawDataItem(key) ?: return null
        val array = try { item.asArray } catch (e: Exception) { return null }

        return when (T::class) {
            String::class -> array.map { it.asTstr as T }
            Int::class -> array.map { it.asNumber.toInt() as T }
            Long::class -> array.map { it.asNumber as T }
            ByteString::class -> array.map { ByteString(it.asBstr) as T }
            Boolean::class -> array.map { it.asBoolean as T }
            else -> throw IllegalArgumentException("Unsupported List element type: ${T::class.simpleName}")
        }
    }

    /**
     * Convenience function to get a value.
     *
     * @param key the name of the key to get.
     * @returns the value or `null` if not found.
     */
    fun getString(key: String): String? = getRawDataItem(key)?.asTstr

    /**
     * Convenience function to get a value.
     *
     * @param key the name of the key to get.
     * @returns the value or `null` if not found.
     */
    fun getInt(key: String): Int? = getRawDataItem(key)?.asNumber?.toInt()

    /**
     * Convenience function to get a value.
     *
     * @param key the name of the key to get.
     * @returns the value or `null` if not found.
     */
    fun getLong(key: String): Long? = getRawDataItem(key)?.asNumber

    /**
     * Convenience function to get a value.
     *
     * @param key the name of the key to get.
     * @returns the value or `null` if not found.
     */
    fun getBoolean(key: String): Boolean? = getRawDataItem(key)?.asBoolean

    /**
     * Convenience function to get a value.
     *
     * @param key the name of the key to get.
     * @returns the value or `null` if not found.
     */
    fun getByteString(key: String): ByteString? = getRawDataItem(key)?.asBstr?.let { ByteString(it) }


    // --- EDITING ---

    /**
     * Performs a transactional edit on the tags.
     *
     * The [editAction] is executed against a temporary [Editor]. If the action completes
     * successfully, the changes are committed to memory, serialized to CBOR, and
     * persisted using the class's `saveFn`.
     *
     * @param editAction A lambda receiver on [Editor] to perform modifications.
     */
    suspend fun edit(editAction: suspend Editor.() -> Unit) {
        val secondClosure = editLock.withLock {
            val editor = Editor(_tags.toMutableMap())
            editAction(editor)

            // Commit changes
            _tags = editor.tags
            val newData = buildCborMap {
                _tags.forEach { (key, value) -> put(key, value) }
            }
            saveFn(newData)
        }
        if (secondClosure != null) {
            secondClosure()
        }
    }

    /**
     * A temporary handle for modifying tag values.
     * Instances of this class are only valid within the scope of an [edit] block.
     */
    class Editor internal constructor(
        internal val tags: MutableMap<String, DataItem>
    ) {
        /**
         * Removes all tags.
         */
        fun removeAll() = tags.clear()

        /**
         * Removes the tag with the specified [key].
         *
         * @param key the key to remove.
         */
        fun remove(key: String) = tags.remove(key)

        /**
         * Sets a scalar value for the given [key].
         *
         * Supported types are: [String], [Int], [Long], [Boolean], and [ByteString].
         *
         * @param key the key to insert.
         * @param value the value to insert.
         */
        fun set(key: String, value: Any) {
            val item = when (value) {
                is String -> value.toDataItem()
                is Int -> value.toDataItem()
                is Long -> value.toDataItem()
                is ByteString -> value.toByteArray().toDataItem()
                is Boolean -> value.toDataItem()
                else -> throw IllegalArgumentException("Unsupported type: ${value::class.simpleName}")
            }
            tags[key] = item
        }

        /**
         * Sets a list of values for the given [key].
         *
         * All elements in the list must be of the same supported type.
         *
         * @param key the key to insert.
         * @param value the value to insert.
         */
        fun setList(key: String, value: List<Any>) {
            val items = value.map { item ->
                when (item) {
                    is String -> item.toDataItem()
                    is Int -> item.toDataItem()
                    is Long -> item.toDataItem()
                    is ByteString -> item.toByteArray().toDataItem()
                    is Boolean -> item.toDataItem()
                    else -> throw IllegalArgumentException("Unsupported List element type: ${item::class.simpleName}")
                }
            }
            tags[key] = CborArray(items.toMutableList())
        }

        /**
         * Convenience function to set a value.
         *
         * @param key the name of the key to set.
         * @param value the value to set.
         */
        fun setString(key: String, value: String) = set(key, value)

        /**
         * Convenience function to set a value.
         *
         * @param key the name of the key to set.
         * @param value the value to set.
         */
        fun setInt(key: String, value: Int) = set(key, value)

        /**
         * Convenience function to set a value.
         *
         * @param key the name of the key to set.
         * @param value the value to set.
         */
        fun setLong(key: String, value: Long) = set(key, value)

        /**
         * Convenience function to set a value.
         *
         * @param key the name of the key to set.
         * @param value the value to set.
         */
        fun setBoolean(key: String, value: Boolean) = set(key, value)

        /**
         * Convenience function to set a value.
         *
         * @param key the name of the key to set.
         * @param value the value to set.
         */
        fun setByteString(key: String, value: ByteString) = set(key, value)
    }
}