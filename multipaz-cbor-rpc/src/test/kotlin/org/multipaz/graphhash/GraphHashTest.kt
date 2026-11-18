package org.multipaz.graphhash

import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.decodeToString
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.io.bytestring.toHexString
import org.junit.Assert
import org.junit.Before
import org.junit.Test

class GraphHashTest {
    val capturedSequences = mutableListOf<String>()
    lateinit var graphHasher: GraphHasher

    @Before
    fun before() {
        capturedSequences.clear()
        graphHasher = GraphHasher { CapturingHasher() }
    }

    @Test
    fun testSimple() {
        val composite = Composite(
            edges = listOf(
                ImmediateEdge("foo", EdgeKind.REQUIRED, Leaf("FOO")),
                ImmediateEdge("bar", EdgeKind.OPTIONAL, Leaf("BAR")),
                ImmediateEdge("buz", EdgeKind.ALTERNATIVE, Leaf("BUZ")),
                ImmediateEdge("def", EdgeKind.DEFAULT, Leaf("DEF")),
            ),
            extra = "zzz".encodeToByteString()
        )
        val hash = graphHasher.hash(composite).decodeToString()
        Assert.assertEquals("hash4", hash)
        Assert.assertEquals(5, capturedSequences.size)
        Assert.assertEquals("<01>[BAR]", capturedSequences[0])
        Assert.assertEquals("<01>[BUZ]", capturedSequences[1])
        Assert.assertEquals("<01>[DEF]", capturedSequences[2])
        Assert.assertEquals("<01>[FOO]", capturedSequences[3])
        Assert.assertEquals(
            "<02>[zzz][bar]<00><f1>[hash0][buz]<00><f2>[hash1][def]<00><f3>[hash2][foo]<00><f0>[hash3]",
            capturedSequences[4]
        )
    }

    @Test
    fun loop() {
        lateinit var list: Composite
        val treeNode = Composite(
            edges = listOf(
                ImmediateEdge("value", EdgeKind.REQUIRED, Leaf("String")),
                DeferredEdge("children", EdgeKind.OPTIONAL) { list },
            )
        )
        list = Composite(listOf(ImmediateEdge("elements", EdgeKind.REQUIRED, treeNode)))
        val ref = Composite(listOf(ImmediateEdge("ref", EdgeKind.REQUIRED, treeNode)))

        // No assigned id
        val caught = try {
            graphHasher.hash(ref)
            false
        } catch (err: UnassignedLoopException) {
            Assert.assertEquals(2, err.loop.size)
            Assert.assertSame(treeNode, err.loop[0])
            Assert.assertEquals(list, err.loop[1])
            true
        }
        Assert.assertTrue(caught)

        // With assigned id
        graphHasher = GraphHasher { CapturingHasher() }
        capturedSequences.clear()
        graphHasher.setAssignedHash(treeNode, "assigned".encodeToByteString())
        val hash = graphHasher.hash(ref).decodeToString()
        Assert.assertEquals("hash0", hash)
        Assert.assertEquals("<02>[ref]<00><f0>[assigned]", capturedSequences[0])
    }

    // Mock HashBuilder that captures data to be hashed
    inner class CapturingHasher : HashBuilder {
        private val buffer = StringBuilder()

        private fun isAscii(data: ByteString): Boolean {
            for (i in 0..<data.size) {
                val byte = data[i].toInt()
                if (32 > byte || byte > 126) {
                    return false
                }
            }
            return true
        }

        @OptIn(ExperimentalStdlibApi::class)
        override fun update(data: ByteString) {
            if (isAscii(data)) {
                buffer.append("[")
                buffer.append(data.decodeToString())
                buffer.append("]")
            } else {
                buffer.append("<")
                buffer.append(data.toHexString())
                buffer.append(">")
            }
        }

        override fun build(): ByteString {
            val hash = "hash" + capturedSequences.size
            capturedSequences.add(buffer.toString())
            return hash.encodeToByteString()
        }
    }
}