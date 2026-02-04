package org.multipaz.tags

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.DataItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TagsTest {

    @Test
    fun testBasicReadWriteScalars() = runTest {
        var storedData: DataItem? = null
        var secondClosureInvoked = false
        val tags = Tags(storedData) { newData -> storedData = newData; { secondClosureInvoked = true } }

        tags.edit {
            set("name", "Alice")
            set("age", 30)
            set("active", true)
            set("score", 100L)
            set("blob", ByteString(byteArrayOf(0x01, 0x02)))
        }
        assertTrue(storedData != null)
        assertTrue(secondClosureInvoked)

        // 1. Verify values are accessible immediately
        assertEquals("Alice", tags.get<String>("name"))
        assertEquals(30, tags.get<Int>("age"))
        assertEquals(true, tags.get<Boolean>("active"))
        assertEquals(100L, tags.get<Long>("score"))
        assertEquals(ByteString(byteArrayOf(0x01, 0x02)), tags.get<ByteString>("blob"))

        // 2. Verify persistence (simulate app restart by reloading from storedData)
        // Note: storedData is now a valid ByteArray (not null)
        val newTags = Tags(storedData)
        assertEquals("Alice", newTags.get<String>("name"))
        assertEquals(30, newTags.get<Int>("age"))
        assertEquals(100L, newTags.get<Long>("score"))
    }

    @Test
    fun testLists() = runTest {
        var storedData: DataItem? = null
        val tags = Tags(storedData) { newData -> storedData = newData; null }

        val stringList = listOf("A", "B", "C")
        val intList = listOf(1, 2, 3)
        val longList = listOf(10L, 20L, 30L)
        val byteStringList = listOf(ByteString(byteArrayOf(1)), ByteString(byteArrayOf(2)))
        val boolList = listOf(true, false, true)

        tags.edit {
            setList("strings", stringList)
            setList("ints", intList)
            setList("longs", longList)
            setList("bytes", byteStringList)
            setList("bools", boolList)
        }

        assertEquals(stringList, tags.getList<String>("strings"))
        assertEquals(intList, tags.getList<Int>("ints"))
        assertEquals(longList, tags.getList<Long>("longs"))
        assertEquals(byteStringList, tags.getList<ByteString>("bytes"))
        assertEquals(boolList, tags.getList<Boolean>("bools"))
    }

    @Test
    fun testUpdateAndRemove() = runTest {
        val tags = Tags(null)

        // Initial populate
        tags.edit {
            set("key1", "Initial")
            set("key2", "To Remove")
        }

        // Verify initial state
        assertEquals("Initial", tags.get<String>("key1"))
        assertTrue(tags.hasKey("key2"))

        // Update and Remove
        tags.edit {
            set("key1", "Updated") // Overwrite
            remove("key2")         // Delete
        }

        assertEquals("Updated", tags.get<String>("key1"))
        assertNull(tags.get<String>("key2"))
        assertFalse(tags.hasKey("key2"))

        // Verify Keys set
        assertEquals(setOf("key1"), tags.keys)
    }

    @Test
    fun testClearAll() = runTest {
        val tags = Tags(null)

        tags.edit {
            set("a", 1)
            set("b", 2)
        }
        assertEquals(2, tags.keys.size)

        tags.edit {
            removeAll()
        }
        assertEquals(0, tags.keys.size)
        assertNull(tags.get<Int>("a"))
    }

    @Test
    fun testMissingKeysReturnNull() = runTest {
        val tags = Tags(null)
        assertNull(tags.get<String>("non_existent"))
        assertNull(tags.get<Int>("non_existent"))
        assertNull(tags.getList<String>("non_existent"))
    }
}