package org.multipaz.rpc

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.util.fromBase64Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

enum class Enum1 { A, B, C }

enum class Enum2 { C, B, A }

enum class Enum3 { C, B, A, Z }

@CborSerializable(
    schemaHash = "iDDEU1sNO1p7l664JxFOasurn7s3Nh6gHamABa87eTU"
)
internal data class Simple0(val d: Enum1, val b: Long, val c: Set<String>, val a: ByteString) {
    companion object
}

// Field order does not affect structural equivalency and schema hashes.
@CborSerializable(
    schemaHash = "iDDEU1sNO1p7l664JxFOasurn7s3Nh6gHamABa87eTU"
)
data class Simple1(val a: ByteString, val b: Long, val c: Set<String>, val d: Enum1) {
    companion object
}

// Structurally equivalent classes have the same schema hashes.
@CborSerializable(
    schemaHash = "iDDEU1sNO1p7l664JxFOasurn7s3Nh6gHamABa87eTU"
)
class Simple2(val a: ByteArray, val b: Int, val c: List<String>, val d: Enum2) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Simple2) return false
        return a.contentEquals(other.a) && b == other.b && c == other.c && d == other.d
    }

    override fun hashCode(): Int {
        return 31 * (31 * (31 * a.contentHashCode() + b) + c.hashCode()) + d.hashCode()
    }

    companion object
}

// Nullable (optional) vs non-nullable (required) values affect the schema.
@CborSerializable(
    schemaHash = "jWZxG38vb6rMn5vxW3I-Gv_4n8l3qzb-VysO_p_5fqI"
)
data class Simple3(val a: ByteString?, val b: Int, val c: List<String>, val d: Enum2) {
    companion object
}

// Enum value set affects the schema.
@CborSerializable(
    schemaHash = "vrQ3NnWf1_kx_j12OM0gDq6_6D1iUSgEBEdQJwWEWGU"
)
data class Simple4(val a: ByteString?, val b: Int, val c: List<String>, val d: Enum3) {
    companion object
}

// Field names affect the schema.
@CborSerializable(
    schemaHash = "T-sH4DsLtRBu41vqBVxMfs5nd3N0_OHjPUsb9088lUY"
)
data class Simple5(val z: ByteString?, val b: Int, val c: List<String>, val d: Enum2) {
    companion object
}

// Self-referential data structures, will cause compilation error without schemaId
@CborSerializable(
    typeKey = "type",
    schemaHash = "LIMaWzbx9GoLnJfTc0bwbTVbKWp5ikeIJFk1tq_SZC4",
    schemaId = "9uoN7E5Qk2s2m0R-b7LrskkMB8Os-mBicFLp11tXRMA"
)
internal sealed class Node {
    companion object
}

internal data class LeafNode(val content: String): Node()

internal data class ContainerNode(val nodes: List<Node>): Node()

// A set of single-field data classes to verify schema hash calculation integrity.

@CborSerializable(
    schemaHash = "XibsHJRA4fVW7fXSuVNNP8fT7iuGm4zgSPaZhpSV-Ek"
)
data class SingleInt(val a: Int) {
    companion object
}

@CborSerializable(
    schemaHash = "3J--K_uKY-CKfvl7kcYbdiP576JqyoCQVMJEOF_dv-k"
)
data class SingleString(val a: String) {
    companion object
}

@CborSerializable(
    schemaHash = "9HjqePFsyLYDoJAge8f3cDV7VCLX6hnGdMCMawyJzlA"
)
data class SingleNullableString(val a: String?) {
    companion object
}

@CborSerializable(
    schemaHash = "ugMqYu2H_U3qb9Zutfl1Q6NCGn1pwTBfikkCH5yftbM"
)
data class SingleEnum(val a: Enum1) {
    companion object
}

@CborSerializable(
    schemaHash = "rBFNVff-sk2nKSoiR5WQfJiKtksNkn8xSN4FsbQLGJI"
)
data class SingleIntList(val a: List<Int>) {
    companion object
}

@CborSerializable(
    schemaHash = "MfUotQQy34Na2r6Q-rDGrHog3hbbCpI0WTnTwsGTx8o"
)
data class SingleStringList(val a: List<String>) {
    companion object
}

// Check that intermediate classes can be sealed.
@CborSerializable
sealed class IscRoot(
    open val stuff: String
) {
    companion object
}

sealed class IscIntermediate(
    override val stuff: String,
    open val moreStuff: Int
): IscRoot(stuff)

data class IscInheritsIntermediate(
    override val stuff: String,
    override val moreStuff: Int,
    val leafStuff: Boolean
): IscIntermediate(stuff, moreStuff)

data class IscInheritsRoot(
    override val stuff: String,
    val foo: Boolean
): IscRoot(stuff)


class CborSerializationTest {
    @Test
    fun structuralEquivalency() {
        // Types which are structurally equivalent are always compatible.
        val simple0 = Simple0(Enum1.B, 57L, setOf("foo", "bar"), ByteString(3, 7, 1))
        val simple1 = Simple1(ByteString(3, 7, 1), 57L, setOf("foo", "bar"), Enum1.B)
        val simple2 = Simple2(byteArrayOf(3, 7, 1), 57, listOf("foo", "bar"), Enum2.B)
        assertEquals(simple0, Simple0.fromCbor(simple2.toCbor()))
        assertEquals(simple1, Simple1.fromCbor(simple2.toCbor()))
        assertEquals(simple2, Simple2.fromCbor(simple0.toCbor()))
        assertEquals(simple2, Simple2.fromCbor(simple1.toCbor()))
    }

    @Test
    fun structuralCompatibility_nonNullNullable() {
        // Certain changes in schema are compatible: nullable field can take non-null values
        val simple1 = Simple1(ByteString(3, 7, 1), 57L, setOf("foo", "bar"), Enum1.B)
        val simple3 = Simple3(ByteString(3, 7, 1), 57, listOf("foo", "bar"), Enum2.B)
        assertEquals(simple1, Simple1.fromCbor(simple3.toCbor()))
        assertEquals(simple3, Simple3.fromCbor(simple1.toCbor()))
    }

    @Test
    fun structuralCompatibility_nullValuesAreSkipped() {
        // null values are absent in serialization
        val simple3 = Simple3(null, 57, listOf("foo", "bar"), Enum2.B)
        val simple5 = Simple5(null, 57, listOf("foo", "bar"), Enum2.B)
        assertEquals(simple5, Simple5.fromCbor(simple3.toCbor()))
        assertEquals(simple3, Simple3.fromCbor(simple5.toCbor()))
    }

    @Test
    fun structuralCompatibility_enumValueSet() {
        // when possible values are added to an enum, old values still work
        val simple3 = Simple3(ByteString(3, 7, 1), 57, listOf("foo", "bar"), Enum2.B)
        val simple4 = Simple4(ByteString(3, 7, 1), 57, listOf("foo", "bar"), Enum3.B)
        assertEquals(simple4, Simple4.fromCbor(simple3.toCbor()))
        assertEquals(simple3, Simple3.fromCbor(simple4.toCbor()))
    }

    @Test
    fun unknownFieldIgnored() {
        // if a field is not known, it is silently ignored.
        val simple3 = Simple3(null, 57, listOf("foo", "bar"), Enum2.B)
        val simple5 = Simple5(ByteString(3, 7, 1), 57, listOf("foo", "bar"), Enum2.B)
        assertEquals(simple3, Simple3.fromCbor(simple5.toCbor()))
    }

    @Test
    fun unknownEnumValuesFail() {
        val simple4 = Simple4(ByteString(2, 3, 1), 57, listOf("foo", "bar"), Enum3.Z)
        try {
            Simple5.fromCbor(simple4.toCbor())
            fail()
        } catch (err: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun sealed() {
        val containerNode = ContainerNode(
            listOf(
                ContainerNode(
                    listOf(
                        LeafNode("foo"),
                        LeafNode("bar")
                    )
                ),
                LeafNode("baz")
            )
        )
        assertEquals(containerNode, Node.fromCbor(containerNode.toCbor()))
    }

    @Test
    fun schemaIds() {
        // When schemaId is explicitly specified
        assertEquals(ByteString("9uoN7E5Qk2s2m0R-b7LrskkMB8Os-mBicFLp11tXRMA".fromBase64Url()),
            Node.cborSchemaId)
        // When schemaId is not specified, schemaHash is used as id
        assertEquals(ByteString("T-sH4DsLtRBu41vqBVxMfs5nd3N0_OHjPUsb9088lUY".fromBase64Url()),
            Simple5.cborSchemaId)
    }

    @Test
    fun schemaHashSingleInt() {
        assertEquals(
            ByteString("XibsHJRA4fVW7fXSuVNNP8fT7iuGm4zgSPaZhpSV-Ek".fromBase64Url()),
            SingleInt.cborSchemaId
        )
    }

    @Test
    fun schemaHashSingleString() {
        assertEquals(
            ByteString("3J--K_uKY-CKfvl7kcYbdiP576JqyoCQVMJEOF_dv-k".fromBase64Url()),
            SingleString.cborSchemaId
        )
    }

    @Test
    fun schemaHashSingleNullableString() {
        assertEquals(
            ByteString("9HjqePFsyLYDoJAge8f3cDV7VCLX6hnGdMCMawyJzlA".fromBase64Url()),
            SingleNullableString.cborSchemaId
        )
    }

    @Test
    fun schemaHashSingleEnum() {
        assertEquals(
            ByteString("ugMqYu2H_U3qb9Zutfl1Q6NCGn1pwTBfikkCH5yftbM".fromBase64Url()),
            SingleEnum.cborSchemaId
        )
    }

    @Test
    fun schemaHashSingleIntList() {
        assertEquals(
            ByteString("rBFNVff-sk2nKSoiR5WQfJiKtksNkn8xSN4FsbQLGJI".fromBase64Url()),
            SingleIntList.cborSchemaId
        )
    }

    @Test
    fun schemaHashSingleStringList() {
        assertEquals(
            ByteString("MfUotQQy34Na2r6Q-rDGrHog3hbbCpI0WTnTwsGTx8o".fromBase64Url()),
            SingleStringList.cborSchemaId
        )
    }

    @Test
    fun intermediateSealedClasses() {
        val inheritsRoot = IscInheritsRoot(
            stuff = "foo",
            foo = true
        )
        assertEquals(
            IscRoot.fromDataItem(inheritsRoot.toDataItem()),
            inheritsRoot
        )

        val inheritsIntermediate = IscInheritsIntermediate(
            stuff = "foo",
            moreStuff = 5,
            leafStuff = false
        )
        assertEquals(
            IscRoot.fromDataItem(inheritsIntermediate.toDataItem()),
            inheritsIntermediate
        )
    }
}