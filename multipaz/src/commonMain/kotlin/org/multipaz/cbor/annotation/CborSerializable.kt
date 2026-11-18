package org.multipaz.cbor.annotation

/**
 * Marks class for automatic CBOR serialization code generation.
 *
 * CBOR serializer uses a CBOR map that maps Kotlin field names to serialized field values.
 *
 * Fields with value `null` are not written to the serialized map, and missing keys for nullable
 * fields deserialize to `null`. Consequently, nullable constructor properties may only specify
 * `null` as a default value (any non-null default value on a nullable property is rejected at
 * compile time).
 *
 * Non-nullable constructor properties with default values are always written to the serialized
 * map, and fall back to their Kotlin default value when absent during deserialization. This
 * preserves backwards compatibility when adding new non-nullable fields with default values to
 * an existing serializable class.
 *
 * The following field types are allowed:
 *  - primitive: `Int`, `Long`, `Float`, `Double`, `String`, `Boolean`
 *  - time: `Instant` and `LocalDate`
 *  - binary: `ByteString` and `ByteArray`
 *  - CBOR generic value: `DataItem`
 *  - enums
 *  - types manually implementing CBOR serialization (annotated by [CborSerializationImplemented])
 *  - types with automatically generated CBOR serialization (annotated by [CborSerializable])
 *  - maps, lists, or sets of the above (sets are treated as lists)
 *  - nullable variants of the above
 *
 * Single-level sealed class hierarchies are supported. Annotation is required only on the root
 * class of the sealed hierarchy. A special key ("type" by default, see [typeKey]) is added to
 * CBOR map to indicate the actual value type (type id). To avoid name conflicts it is
 * recommended that either:
 *   - leaf class names include base class name either as a prefix or a suffix (type id is
 *     generated from the leaf class name, stripping base class name),
 *   - or, leaf classes are scoped in the base class,
 *   - or, leaf classes and the base class are scoped in some other class or object,
 *   - or, explicit [typeId] is specified.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class CborSerializable(
    /**
     * Optional parameter for sealed class hierarchies to define the key that carries type id.
     *
     * This parameter should be used on the root (sealed) class.
     */
    val typeKey: String = "",
    /**
     * Optional parameter for sealed class hierarchies to define the type id for this class.
     *
     * This parameter should be used on the leaf (data) class.
     */
    val typeId: String = "",
    /**
     * Optional parameter that identifies the schema of the serialized CBOR representation
     * for the objects of the annotated type.
     *
     * When this parameter is specified, the annotation processor will build a data schema for
     * CBOR serialization of the annotated type and compute a hash of that schema definition.
     * If the computed hash does not match what is specified by this parameter, an error is
     * generated.
     *
     * In practice, this means that once this parameter is specified, any change to the
     * object's CBOR representation (including its dependencies) will require an update of
     * [schemaHash] (as well as [schemaHash] parameters for all the objects that depend on this
     * one). This helps to make (or, perhaps, not to make) changes to serialization consciously,
     * considering all potential implications of such changes.
     *
     * While it is not an error for an object that has [schemaHash] specified to reference a type
     * without either [schemaHash] or [schemaId], it is not expected and a warning is generated.
     *
     * It is sufficient to specify this parameter on the root of the sealed class hierarchy.
     * The schema of a sealed class implicitly includes all subclasses.
     */
    val schemaHash: String = "",
    /**
     * Optional parameter that identifies this type in the serialized CBOR representation
     * in objects that contain the fields of the annotated type.
     *
     * By default [schemaHash] is computed and used as [schemaId], however when a type is
     * a part of a self-referencing loop of types, [schemaId] must be assigned on some
     * types to break the loop. This also can be used to manually manage schema changes.
     *
     * Note that [schemaHash] is still computed using the usual rules and can be used to track
     * schema changes in the class structure and its dependencies.
     */
    val schemaId: String = ""
)
