## CBOR Serialization Kotlin (KSP) annotation processor

This library provides a simple way to generate code for CBOR
serialization and deserilization.

### Getting started

**Important**: Make sure that this annotation processor is enabled as
a compiler plugin (and not just a dependency) in your build process (e.g.
with `ksp project(":cbor-processor")` line in dependency section of
`build.gradle`)

The simplest way to make use of this annotation processor is to
create a Kotlin "data class" add (possibly empty) companion
object and mark the class with `@CborSerializable` annotation.

Following field data types are supported at this point:
 - `Int`
 - `Long`
 - `String`
 - `Boolean`
 - `ByteString`
 - enums
 - CBOR `DataItem`
 - classes which are `@CborSerializable`
 - `Map`s and `List`s of the above

Objects are serialized as CBOR Maps with field names as keys. Control
of the key names and serializing objects area arrays are planned for
the future.

Annotation processor generates `toCbor` and `toDataItem` extension for the
class and `fromCbor` and `fromDataItem` extension for the
companion object.

### Serializing sealed class hierarchies

Sealed classes and their subclasses provide an excellent way to
model variant data types. To create serializer and deserializer
for the sealed class hierarchy, name your classes so that the
root class name is the prefix or suffix for leaf class names, i.e.
`Request` for the root and `RequestSimple` for the leaf (or
alternatively `typeId` parameter must be given; see below).
Then simply add `@CborSerializable` annotation to the root
(sealed) class. Annotating subclasses is optional. Additional
`type` key is added to serialized object to tell which of the
subclasses was serialized.

Only flat hierarchies with a single root and multiple leafs are
supported.

It is possible to change the name of the type key by specifying
`typeKey` parameter in `@CborSerializable` on the root class.
Specific type id can also be specified as `typeId` parameter in
`@CborSerializable` on a leaf class (and must be specified if 
root class name is not a prefix of a leaf class name).

### Merging additional keys in CBOR map that represents an object

A field of the data class can be marked with `@CborMerge` annotation
to indicate that it contains additional key-value pairs that should
be added to the object serialization. Only a single such field may be
defined in any given class and it must be of type `Map`.

### Backwards compatibility and default values

Backwards compatibility is preserved when adding either:
 - a nullable field (missing keys deserialize to `null`, and `null` values are omitted when serializing), or
 - a non-nullable constructor field with a default value (always written when serializing, and falls back to its Kotlin default value when absent during deserialization).

Nullable fields may only use `null` as a default value; specifying a non-null default on a nullable field is rejected at compile time because `null` is represented by omitting the key from the CBOR map.


