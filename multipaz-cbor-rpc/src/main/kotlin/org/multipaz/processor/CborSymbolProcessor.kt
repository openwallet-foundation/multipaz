package org.multipaz.processor

import com.google.devtools.ksp.isInternal
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import org.multipaz.graphhash.Composite
import org.multipaz.graphhash.DeferredEdge
import org.multipaz.graphhash.Edge
import org.multipaz.graphhash.EdgeKind
import org.multipaz.graphhash.GraphHasher
import org.multipaz.graphhash.HashBuilder
import org.multipaz.graphhash.ImmediateEdge
import org.multipaz.graphhash.Leaf
import org.multipaz.graphhash.Node
import org.multipaz.graphhash.UnassignedLoopException
import java.security.MessageDigest
import kotlin.collections.set
import kotlin.io.encoding.Base64
import kotlin.io.encoding.Base64.PaddingOption
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

/**
 * Kotlin Annotation Processor that generates serialization and deserialization code for
 * `@CborSerializable` annotation.
 */
class CborSymbolProcessor(
    private val options: Map<String, String>,
    private val logger: KSPLogger,
    private val codeGenerator: CodeGenerator,
) : SymbolProcessor {

    companion object {
        const val ANNOTATION_PACKAGE = "org.multipaz.cbor.annotation"
        const val ANNOTATION_SERIALIZABLE = "CborSerializable"
        const val ANNOTATION_SERIALIZABLE_GENERATED = "CborSerializableGenerated"
        const val ANNOTATION_SERIALIZATION_IMPLEMENTED = "CborSerializationImplemented"
        const val ANNOTATION_MERGE = "CborMerge"
        const val CBOR_TYPE = "org.multipaz.cbor.Cbor"
        const val BSTR_TYPE = "org.multipaz.cbor.Bstr"
        const val TSTR_TYPE = "org.multipaz.cbor.Tstr"
        const val SIMPLE_TYPE = "org.multipaz.cbor.Simple"
        const val CBOR_MAP_TYPE = "org.multipaz.cbor.CborMap"
        const val BUILD_CBOR_ARRAY = "org.multipaz.cbor.buildCborArray"
        const val BUILD_CBOR_MAP = "org.multipaz.cbor.buildCborMap"
        const val DATA_ITEM_CLASS = "org.multipaz.cbor.DataItem"
        const val BYTESTRING_TYPE = "kotlinx.io.bytestring.ByteString"
        const val TO_DATAITEM_DATETIMESTRING_FUN = "org.multipaz.cbor.toDataItemDateTimeString"
        const val TO_DATAITEM_FULLDATE_FUN = "org.multipaz.cbor.toDataItemFullDate"
        const val TO_DATAITEM_FUN = "org.multipaz.cbor.toDataItem"

        val UNDEFINED = ByteString((255).toByte())

        fun deserializeValue(
            codeBuilder: CodeBuilder,
            code: String,
            type: KSType,
            canBeNull: Boolean = false
        ): String {
            val declaration = type.declaration
            val base = if (canBeNull && type.isMarkedNullable) {
                "$code.asNullable?."
            } else {
                "$code."
            }
            return when (val qualifiedName = declaration.qualifiedName!!.asString()) {
                "kotlin.collections.Map", "kotlin.collections.MutableMap" ->
                    with(codeBuilder) {
                        val map = varName("map")
                        val mutable = qualifiedName.contains("Mutable")
                        block("val $map = ${base}let") {
                            block(
                                before = "buildMap<${typeArguments(this, type)}>",
                                hasBlockAfter = mutable
                            ) {
                                addDeserializedMapValues(this, "", "it", type)
                            }
                            if (mutable) {
                                finishLine(".toMutableMap()")
                            }
                        }
                        map
                    }

                "kotlin.collections.List", "kotlin.collections.MutableList",
                "kotlin.collections.Set", "kotlin.collections.MutableSet" ->
                    with(codeBuilder) {
                        val array = varName("array")
                        block("val $array = ${base}let") {
                            val builder = if (qualifiedName.endsWith("Set")) {
                                "buildSet<${typeArguments(this, type)}>"
                            } else {
                                "buildList<${typeArguments(this, type)}>"
                            }
                            val mutable = qualifiedName.contains("Mutable")
                            block(builder, hasBlockAfter = mutable) {
                                block(before = "for (value in it.asArray)") {
                                    val value = deserializeValue(
                                        this, "value",
                                        type.arguments[0].type!!.resolve()
                                    )
                                    line("add($value)")
                                }
                            }
                            if (mutable) {
                                if (qualifiedName.endsWith("Set")) {
                                    finishLine(".toMutableSet()")
                                } else {
                                    finishLine(".toMutableList()")
                                }
                            }
                        }
                        array
                    }

                "kotlin.String" -> return "${base}asTstr"
                "kotlin.ByteArray" -> return "${base}asBstr"
                BYTESTRING_TYPE -> {
                    codeBuilder.importQualifiedName(BYTESTRING_TYPE)
                    return if (type.isMarkedNullable) {
                        "${base}let { ByteString(it.asBstr) }"
                    } else {
                        "ByteString(${base}asBstr)"
                    }
                }
                "kotlin.Long" -> return "${base}asNumber"
                "kotlin.Int" -> return if (type.isMarkedNullable) {
                    "${base}asNumber?.toInt()"
                } else {
                    "${base}asNumber.toInt()"
                }
                "kotlin.Float" -> return "${base}asFloat"
                "kotlin.Double" -> return "${base}asDouble"
                "kotlin.Boolean" -> return "${base}asBoolean"
                "kotlin.time.Instant" -> "${base}asDateTimeString"
                "kotlin.time.LocalDate" -> "${base}asDateString"
                DATA_ITEM_CLASS -> return code
                else -> return if (declaration is KSClassDeclaration &&
                    declaration.classKind == ClassKind.ENUM_CLASS
                ) {
                    if (type.isMarkedNullable) {
                        "${base}let { ${typeRef(codeBuilder, type)}.valueOf(it.asTstr) }"
                    } else {
                        "${typeRef(codeBuilder, type)}.valueOf($code.asTstr)"
                    }
                } else {
                    codeBuilder.importQualifiedName(qualifiedName)
                    val deserializer = deserializerName(declaration as KSClassDeclaration, false)
                    whenSerializationGenerated(declaration) {
                        val shortName = deserializer.substring(deserializer.lastIndexOf(".") + 1)
                        codeBuilder.importFunctionName(
                            shortName,
                            declaration.packageName.asString()
                        )
                    }

                    "${deserializer}($code)"
                }
            }
        }

        private fun addDeserializedMapValues(
            codeBuilder: CodeBuilder,
            targetCode: String,
            sourceCode: String,
            targetType: KSType,
            fieldNameSet: String? = null
        ) {
            val entry = codeBuilder.varName("entry")
            codeBuilder.block("for ($entry in $sourceCode.asMap.entries)") {
                if (fieldNameSet != null) {
                    importQualifiedName(TSTR_TYPE)
                    block("if ($entry.key is Tstr && $fieldNameSet.contains($entry.key.asTstr))") {
                        line("continue")
                    }
                }
                val key = deserializeValue(
                    this, "$entry.key",
                    targetType.arguments[0].type!!.resolve()
                )
                val value = deserializeValue(
                    this, "$entry.value",
                    targetType.arguments[1].type!!.resolve()
                )
                line("${targetCode}put($key, $value)")
            }
        }

        private fun findAnnotation(declaration: KSDeclaration, simpleName: String): KSAnnotation? {
            for (annotation in declaration.annotations) {
                if (annotation.shortName.asString() == simpleName &&
                    annotation.annotationType.resolve().declaration.packageName.asString() == ANNOTATION_PACKAGE
                ) {
                    return annotation
                }
            }
            return null
        }

        private fun deserializerName(
            classDeclaration: KSClassDeclaration, forDeclaration: Boolean): String {
            val baseName = classDeclaration.simpleName.asString()
            return if (hasCompanion(classDeclaration)) {
                if (forDeclaration) {
                    "${baseName}.Companion.fromDataItem"
                } else {
                    // for call
                    "${baseName}.fromDataItem"
                }
            } else {
                "${baseName}_fromDataItem"
            }
        }

        private fun hasCompanion(declaration: KSClassDeclaration): Boolean {
            return getCompanion(declaration) != null
        }

        fun getCompanion(declaration: KSClassDeclaration): KSClassDeclaration? {
            return declaration.declarations.filterIsInstance<KSClassDeclaration>()
                .firstOrNull { it.isCompanionObject }
        }

        fun serializeValue(
            codeBuilder: CodeBuilder,
            code: String,
            type: KSType,
            canBeNull: Boolean = false
        ): String {
            val declaration = type.declaration
            val declarationQualifiedName = declaration.qualifiedName
                ?: throw NullPointerException("Declaration $declaration with null qualified name!")
            val nullable = canBeNull && type.isMarkedNullable
            val base = if (nullable) {
                codeBuilder.importQualifiedName(SIMPLE_TYPE)
                "$code?."
            } else {
                "$code."
            }
            when (val qualifiedName = declarationQualifiedName.asString()) {
                "kotlin.collections.Map", "kotlin.collections.MutableMap" ->
                    with(codeBuilder) {
                        val map = varName("map")
                        importQualifiedName(BUILD_CBOR_MAP)
                        block("val $map = ${base}let", hasBlockAfter = nullable) {
                            block("buildCborMap") {
                                addSerializedMapValues(this, "", "it", type)
                            }
                        }
                        if (nullable) {
                            importQualifiedName(SIMPLE_TYPE)
                            finishLine(" ?: Simple.NULL")
                        }
                        return map
                    }

                "kotlin.collections.List", "kotlin.collections.MutableList",
                "kotlin.collections.Set", "kotlin.collections.MutableSet" ->
                    with(codeBuilder) {
                        val array = varName("array")
                        importQualifiedName(BUILD_CBOR_ARRAY)
                        block("val $array = ${base}let", hasBlockAfter = nullable) {
                            block("buildCborArray") {
                                block("for (value in it)") {
                                    val value = serializeValue(
                                        this, "value",
                                        type.arguments[0].type!!.resolve()
                                    )
                                    line("add($value)")
                                }
                            }
                        }
                        if (nullable) {
                            importQualifiedName(SIMPLE_TYPE)
                            finishLine(" ?: Simple.NULL")
                        }
                        return array
                    }

                "kotlin.String" -> {
                    codeBuilder.importQualifiedName(TSTR_TYPE)
                    return if (nullable) {
                        codeBuilder.importQualifiedName(SIMPLE_TYPE)
                        "${base}let { Tstr(it) } ?: Simple.NULL"
                    } else {
                        "Tstr($code)"
                    }
                }
                "kotlin.ByteArray" -> {
                    codeBuilder.importQualifiedName(BSTR_TYPE)
                    return if (nullable) {
                        codeBuilder.importQualifiedName(SIMPLE_TYPE)
                        "${base}let { Bstr(it) } ?: Simple.NULL"
                    } else {
                        "Bstr($code)"
                    }
                }
                BYTESTRING_TYPE -> {
                    codeBuilder.importQualifiedName(BSTR_TYPE)
                    return if (nullable) {
                        codeBuilder.importQualifiedName(SIMPLE_TYPE)
                        "${base}let { Bstr(it.toByteArray()) } ?: Simple.NULL"
                    } else {
                        "Bstr(${base}toByteArray())"
                    }
                }
                "kotlin.Int" -> {
                    codeBuilder.importQualifiedName(TO_DATAITEM_FUN)
                    return if (nullable) {
                        "${base}toLong()?.toDataItem() ?: Simple.NULL"
                    } else {
                        "${base}toLong().toDataItem()"
                    }
                }

                "kotlin.Long", "kotlin.Float", "kotlin.Double", "kotlin.Boolean" -> {
                    codeBuilder.importQualifiedName(TO_DATAITEM_FUN)
                    return if (nullable) {
                        "${base}toDataItem() ?: Simple.NULL"
                    } else {
                        "${base}toDataItem()"
                    }
                }

                "kotlin.time.Instant" -> {
                    codeBuilder.importQualifiedName(TO_DATAITEM_DATETIMESTRING_FUN)
                    return if (nullable) {
                        "${base}toDataItemDateTimeString() ?: Simple.NULL"
                    } else {
                        "${base}toDataItemDateTimeString()"
                    }
                }

                "kotlinx.datetime.LocalDate" -> {
                    codeBuilder.importQualifiedName(TO_DATAITEM_FULLDATE_FUN)
                    return if (nullable) {
                        "${base}let { it.toDataItemFullDate() } ?: Simple.NULL"
                    } else {
                        "${base}toDataItemFullDate()"
                    }
                }

                DATA_ITEM_CLASS -> return code
                else -> return if (declaration is KSClassDeclaration &&
                    declaration.classKind == ClassKind.ENUM_CLASS
                ) {
                    codeBuilder.importQualifiedName(TSTR_TYPE)
                    if (nullable) {
                        "${base}let { Tstr(it.name) } ?: Simple.NULL"
                    } else {
                        "Tstr($code.name)"
                    }
                } else {
                    codeBuilder.importQualifiedName(qualifiedName)
                    whenSerializationGenerated(declaration) { serializableDeclaration ->
                        codeBuilder.importFunctionName("toDataItem",
                            serializableDeclaration.packageName.asString())
                    }
                    if (nullable) {
                        "${base}toDataItem() ?: Simple.NULL"
                    } else {
                        "${base}toDataItem()"
                    }
                }
            }
        }

        private fun whenSerializationGenerated(
            declaration: KSDeclaration,
            block: (serializableDeclaration: KSDeclaration) -> Unit
        ) {
            var decl = declaration
            while (true) {
                if (findAnnotation(decl, ANNOTATION_SERIALIZABLE) != null) {
                    block(decl)
                } else if (decl is KSClassDeclaration) {
                    val supertypes = decl.superTypes.iterator()
                    if (supertypes.hasNext()) {
                        decl = supertypes.next().resolve().declaration
                        continue
                    }
                }
                break
            }
        }

        private fun addSerializedMapValues(
            codeBuilder: CodeBuilder,
            targetCode: String, sourceCode: String, sourceType: KSType
        ) {
            codeBuilder.block("for (entry in $sourceCode.entries)") {
                val key = serializeValue(
                    this, "entry.key",
                    sourceType.arguments[0].type!!.resolve()
                )
                val value = serializeValue(
                    this, "entry.value",
                    sourceType.arguments[1].type!!.resolve()
                )
                line("${targetCode}put($key, $value)")
            }
        }

        private fun typeArguments(codeBuilder: CodeBuilder, type: KSType): String {
            val str = StringBuilder()
            for (arg in type.arguments) {
                if (str.isNotEmpty()) {
                    str.append(", ")
                }
                str.append(typeRef(codeBuilder, arg.type!!.resolve()))
            }
            return str.toString()
        }

        fun typeRef(codeBuilder: CodeBuilder, type: KSType): String {
            return when (val qualifiedName = type.declaration.qualifiedName!!.asString()) {
                "kotlin.collections.Map" -> "Map<${typeArguments(codeBuilder, type)}>"
                "kotlin.collections.List" -> "List<${typeArguments(codeBuilder, type)}>"
                "kotlin.String" -> "String"
                "kotlin.ByteArray" -> "ByteArray"
                "kotlin.Long" -> "Long"
                "kotlin.Int" -> "Int"
                "kotlin.Float" -> "Float"
                "kotlin.Double" -> "Double"
                "kotlin.Boolean" -> "Boolean"
                DATA_ITEM_CLASS -> "DataItem"
                else -> {
                    codeBuilder.importQualifiedName(qualifiedName)
                    return type.declaration.simpleName.asString()
                }
            }
        }

        fun typeRefNullable(codeBuilder: CodeBuilder, type: KSType): String {
            val typeStr = typeRef(codeBuilder, type)
            return if (type.isMarkedNullable) {
                "$typeStr?"
            } else {
                typeStr
            }
        }

        /**
         * Extension to encode a [ByteArray] to a URL-safe base64 encoding without padding
         * as defined in Section 5 of RFC 4648.
         */
        @OptIn(ExperimentalEncodingApi::class)
        private fun ByteString.toBase64Url(): String =
            Base64.UrlSafe.encode(toByteArray()).trimEnd('=')

        /**
         * Extension to decode a [ByteArray] from a URL-safe base64 encoded string
         * as defined in Section 5 of RFC 4648.
         *
         * This works for both strings with or without padding.
         */
        @OptIn(ExperimentalEncodingApi::class)
        private fun String.fromBase64Url(): ByteString {
            return ByteString(Base64.UrlSafe.withPadding(PaddingOption.ABSENT_OPTIONAL)
                .decode(this))
        }

    }

    private lateinit var resolver: Resolver
    private lateinit var schemaTypeInfoCache: MutableMap<String, SchemaTypeInfo>
    private lateinit var compilationUnitClassMap: MutableMap<String, KSClassDeclaration>

    /**
     * Processor main entry point.
     */
    override fun process(resolver: Resolver): List<KSAnnotated> {
        this.resolver = resolver
        val allSealedSubclasses = mutableMapOf<KSClassDeclaration, MutableSet<KSClassDeclaration>>()
        val allRegularClasses = mutableSetOf<KSClassDeclaration>()
        resolver.getSymbolsWithAnnotation("$ANNOTATION_PACKAGE.$ANNOTATION_SERIALIZABLE")
            .filterIsInstance<KSClassDeclaration>().forEach Declaration@{ clazz ->
                val superDeclaration = getSealedSuperclass(clazz)
                if (superDeclaration != null) {
                    val superAnnotation = findAnnotation(superDeclaration, ANNOTATION_SERIALIZABLE)
                    if (superAnnotation == null) {
                        logger.error(
                            "Superclass is not marked with @$ANNOTATION_SERIALIZABLE",
                            clazz
                        )
                    } else {
                        // Annotated subclass
                        allSealedSubclasses.getOrPut(superDeclaration) { mutableSetOf() }
                            .add(clazz)
                        return@Declaration
                    }
                }
                if (clazz.modifiers.contains(Modifier.SEALED)) {
                    val subclasses = clazz.getSealedSubclasses()
                    for (subclass in subclasses) {
                        // if annotated, it will be processed in another branch
                        if (findAnnotation(subclass, ANNOTATION_SERIALIZABLE) == null) {
                            allSealedSubclasses.getOrPut(clazz) { mutableSetOf() }
                                .add(subclass)
                        }
                    }
                } else {
                    allRegularClasses.add(clazz)
                }
            }

        val thisCompilationUnitClasses = allRegularClasses + allSealedSubclasses.keys +
                allSealedSubclasses.values.flatten()
        compilationUnitClassMap =
            thisCompilationUnitClasses.associateBy { it.qualifiedName!!.asString() }
                .toMutableMap()
        schemaTypeInfoCache = mutableMapOf()
        val schemaIds = computeShemaIds()

        for ((superclass, subclasses) in allSealedSubclasses) {
            processSuperclass(superclass, subclasses, schemaIds[superclass])
            for (subclass in subclasses) {
                processSubclass(subclass, superclass, schemaIds[subclass])
            }
        }
        for (clazz in allRegularClasses) {
            processClass(clazz, schemaIds[clazz])
        }
        return listOf()
    }

    // Handle a standalone data class.
    private fun processClass(classDeclaration: KSClassDeclaration, schemaId: ByteString?) {
        processDataClass(classDeclaration, null, null, schemaId)
    }

    // Handle a leaf (data) class that has sealed superclass.
    private fun processSubclass(
        classDeclaration: KSClassDeclaration,
        superDeclaration: KSClassDeclaration,
        schemaId: ByteString?
    ) {
        val typeKey: String = getTypeKey(findAnnotation(superDeclaration, ANNOTATION_SERIALIZABLE))
        val typeId: String = getTypeId(superDeclaration, classDeclaration, true)
        processDataClass(classDeclaration, typeKey, typeId, schemaId)
    }

    // Handle a sealed class that has subclasses.
    private fun processSuperclass(
        classDeclaration: KSClassDeclaration,
        subclasses: Set<KSClassDeclaration>,
        schemaId: ByteString?
    ) {
        val containingFile = classDeclaration.containingFile
        val packageName = classDeclaration.packageName.asString()
        val baseName = classDeclaration.simpleName.asString()
        val annotation = findAnnotation(classDeclaration, ANNOTATION_SERIALIZABLE)
        val modifier = getModifier(classDeclaration)

        with(CodeBuilder()) {
            importQualifiedName(DATA_ITEM_CLASS)
            importQualifiedName(classDeclaration)

            generateSerialization(this, classDeclaration, schemaId)

            block("${modifier}fun $baseName.toDataItem(): DataItem") {
                block("return when (this)") {
                    for (subclass in subclasses) {
                        importQualifiedName(subclass)
                        val subclassName = subclass.simpleName.asString()
                        line("is $subclassName -> (this as $subclassName).toDataItem()")
                    }
                }
            }

            emptyLine()
            val deserializer = deserializerName(classDeclaration, true)
            block("${modifier}fun $deserializer(dataItem: DataItem): $baseName") {
                val typeKey = getTypeKey(annotation)
                line("val type = dataItem[\"$typeKey\"].asTstr")
                block("return when (type)") {
                    for (subclass in subclasses) {
                        val typeId = getTypeId(classDeclaration, subclass, false)
                        line("\"$typeId\" -> ${deserializerName(subclass, false)}(dataItem)")
                    }
                    line("else -> throw IllegalArgumentException(\"wrong type: \$type\")")
                }
            }

            writeToFile(
                codeGenerator = codeGenerator,
                dependencies = Dependencies(false, containingFile!!),
                packageName = packageName,
                fileName = "${baseName}_Cbor"
            )
        }
    }

    private fun processDataClass(
        classDeclaration: KSClassDeclaration,
        typeKey: String?,
        typeId: String?,
        schemaId: ByteString?
    ) {
        val containingFile = classDeclaration.containingFile
        val packageName = classDeclaration.packageName.asString()
        val baseName = classDeclaration.simpleName.asString()
        with(CodeBuilder()) {
            importQualifiedName(DATA_ITEM_CLASS)
            importQualifiedName(classDeclaration)

            generateSerialization(this, classDeclaration, schemaId)

            var hadMergedMap = false
            val modifier = getModifier(classDeclaration)

            block("${modifier}fun $baseName.toDataItem(): DataItem") {
                importQualifiedName(CBOR_MAP_TYPE)
                line("val builder = CborMap.builder()")
                if (typeKey != null) {
                    line("builder.put(\"$typeKey\", \"$typeId\")")
                }
                classDeclaration.getAllProperties().forEach { property ->
                    if (!property.hasBackingField) {
                        return@forEach
                    }
                    val name = property.simpleName.asString()
                    val type = property.type.resolve()
                    // We want exceptions to be serializable (if marked with @CborSerializable),
                    // but we want to skip cause
                    if (name == "cause" && type.declaration.qualifiedName?.asString() == "kotlin.Throwable") {
                        return@forEach
                    }
                    val (source, condition) = if (type.isMarkedNullable) {
                        val valueVar = varName(name)
                        line("val $valueVar = this.$name")
                        Pair(valueVar, "if ($valueVar != null)")
                    } else {
                        Pair("this.$name", null)
                    }
                    optionalBlock(condition) {
                        if (findAnnotation(property, ANNOTATION_MERGE) != null) {
                            if (hadMergedMap) {
                                logger.error("@$ANNOTATION_MERGE can be used only on a single field")
                            } else if (type.declaration.qualifiedName!!.asString() != "kotlin.collections.Map") {
                                logger.error("@$ANNOTATION_MERGE requires field of type Map")
                            } else {
                                hadMergedMap = true
                                addSerializedMapValues(
                                    this, "builder.",
                                    source, type
                                )
                            }
                        } else {
                            val code = serializeValue(this, source, type)
                            line("builder.put(\"$name\", $code)")
                        }
                    }
                }
                line("return builder.end().build()")
            }

            val fieldNameSet = if (hadMergedMap) varName("fieldNameSet") else null
            val dataItem = varName("dataItem")

            emptyLine()
            val deserializer = deserializerName(classDeclaration, true)

            block("${modifier}fun $deserializer($dataItem: DataItem): $baseName") {
                val constructorParameters = mutableListOf<String>()
                classDeclaration.getAllProperties().forEach { property ->
                    if (!property.hasBackingField) {
                        return@forEach
                    }
                    val type = property.type.resolve()
                    val fieldName = property.simpleName.asString()
                    // We want exceptions to be serializable (if marked with @CborSerializable),
                    // but we want to skip cause
                    if (fieldName == "cause" && type.declaration.qualifiedName?.asString() == "kotlin.Throwable") {
                        return@forEach
                    }
                    val name = varName(fieldName)
                    // Always use field names (and require constructor to use them as parameter
                    // names!), because the order of parameters in the constructor sometimes have
                    // to differ from the order of the fields (esp. when using inheritance).
                    constructorParameters.add("$fieldName = $name")
                    if (findAnnotation(property, ANNOTATION_MERGE) != null) {
                        if (type.declaration.qualifiedName!!.asString() == "kotlin.collections.Map") {
                            line("val $name = mutableMapOf<${typeArguments(this, type)}>()")
                            addDeserializedMapValues(this, "$name.", dataItem, type, fieldNameSet)
                        }
                    } else {
                        val item = "$dataItem[\"$fieldName\"]"
                        if (type.isMarkedNullable) {
                            block(
                                "val $name = if ($dataItem.hasKey(\"$fieldName\"))",
                                hasBlockAfter = true
                            ) {
                                line(deserializeValue(this, item, type))
                            }
                            block("else", hasBlockBefore = true) {
                                line("null")
                            }
                        } else {
                            line("val $name = ${deserializeValue(this, item, type)}")
                        }
                    }
                }
                line("return $baseName(")
                withIndent {
                    constructorParameters.forEach { parameter ->
                        line("$parameter,")
                    }
                }
                line(")")
            }

            if (hadMergedMap) {
                emptyLine()
                line("private val $fieldNameSet = setOf(")
                withIndent {
                    classDeclaration.getAllProperties().forEach { property ->
                        if (!property.hasBackingField) {
                            return@forEach
                        }
                        val name = property.simpleName.asString()
                        line("\"$name\",")
                    }
                    if (typeKey != null) {
                        line("\"$typeKey\"")
                    }
                }
                line(")")
            }

            writeToFile(
                codeGenerator = codeGenerator,
                dependencies = Dependencies(false, containingFile!!),
                packageName = packageName,
                fileName = "${baseName}_Cbor"
            )
        }
    }

    private fun getModifier(classDeclaration: KSClassDeclaration): String {
        return if (classDeclaration.isInternal()) "internal " else ""
    }

    private fun getSealedSuperclass(classDeclaration: KSClassDeclaration): KSClassDeclaration? {
        for (supertype in classDeclaration.superTypes) {
            val superDeclaration = supertype.resolve().declaration
            if (superDeclaration is KSClassDeclaration &&
                superDeclaration.classKind == ClassKind.CLASS &&
                superDeclaration.modifiers.contains(Modifier.SEALED)) {
                return superDeclaration
            }
        }
        return null
    }

    private fun getTypeKey(annotation: KSAnnotation?): String {
        annotation?.arguments?.forEach { arg ->
            when (arg.name?.asString()) {
                "typeKey" -> if (arg.value != null) {
                    val field = arg.value.toString()
                    if (field.isNotEmpty()) {
                        return field
                    }
                }
            }
        }
        return "type"
    }

    private fun getTypeId(
        superclass: KSClassDeclaration,
        subclass: KSClassDeclaration,
        warn: Boolean
    ): String {
        findAnnotation(subclass, ANNOTATION_SERIALIZABLE)?.arguments?.forEach { arg ->
            when (arg.name?.asString()) {
                "typeId" -> if (arg.value != null) {
                    val field = arg.value.toString()
                    if (field.isNotEmpty()) {
                        return field
                    }
                }
            }
        }
        val superName = superclass.simpleName.asString()
        val name = subclass.simpleName.asString()
        return if (name.startsWith(superName)) {
            name.substring(superName.length)
        } else if (name.endsWith(superName)) {
            name.substring(0, name.length - superName.length)
        } else if (subclass.parentDeclaration != null &&
            (subclass.parentDeclaration === superclass
                || subclass.parentDeclaration == superclass.parentDeclaration)) {
            // subclass is scoped in superclass or they are scoped in some other class
            name
        } else {
            if (warn) {
                logger.warn(
                    "Supertype name is not a prefix or suffix of subtype name, rename or specify typeId explicitly: $name",
                    subclass
                )
            }
            name
        }
    }

    private fun generateSerialization(
        codeBuilder: CodeBuilder,
        classDeclaration: KSClassDeclaration,
        schemaId: ByteString?
    ) = with(codeBuilder) {
        importQualifiedName(CBOR_TYPE)
        val baseName = classDeclaration.simpleName.asString()
        val modifier = getModifier(classDeclaration)

        block("${modifier}fun $baseName.toCbor(): ByteArray") {
            line("return Cbor.encode(toDataItem())")
        }
        emptyLine()

        if (schemaId != null) {
            importQualifiedName(BYTESTRING_TYPE)
            importQualifiedName("$ANNOTATION_PACKAGE.$ANNOTATION_SERIALIZABLE_GENERATED")
            val bytes = StringBuilder()
            for (i in 0..<schemaId.size) {
                if (bytes.isNotEmpty()) {
                    bytes.append(", ")
                }
                bytes.append(schemaId[i])
            }
            line("@$ANNOTATION_SERIALIZABLE_GENERATED(schemaId = \"${schemaId.toBase64Url()}\")")
            line("${modifier}val ${baseName}_cborSchemaId = ByteString($bytes)")
            emptyLine()
        }

        if (hasCompanion(classDeclaration)) {
            block("${modifier}fun $baseName.Companion.fromCbor(data: ByteArray): $baseName") {
                line("return $baseName.fromDataItem(Cbor.decode(data))")
            }
            emptyLine()

            if (schemaId != null) {
                line("${modifier}val $baseName.Companion.cborSchemaId: ByteString")
                line("    get() = ${baseName}_cborSchemaId")
                emptyLine()
            }
        }
    }

    private fun computeShemaIds(): Map<KSClassDeclaration, ByteString> {

        var retryCount = 0
        while (true) {
            val missedClasses = mutableMapOf<String, KSClassDeclaration>()
            for ((qualifiedName, clazz) in compilationUnitClassMap.entries) {
                if (!schemaTypeInfoCache.contains(qualifiedName)) {
                    try {
                        getSchemaTypeInfoForDefinedClass(clazz, qualifiedName)
                    } catch (err: ForceAddSerializableClass) {
                        val missedClass = err.declaration
                        missedClasses[missedClass.qualifiedName!!.asString()] = missedClass
                    }
                }
            }
            if (missedClasses.isEmpty()) {
                break
            }
            compilationUnitClassMap.putAll(missedClasses)
            schemaTypeInfoCache.clear()
            if (++retryCount > 4) {
                logger.error("Too many attempts to collect annotated classes")
                break
            }
            logger.warn("Adding to annotated classes in this compilation unit ($retryCount): ${missedClasses.keys.joinToString(", ")}")
        }

        val graphHasher = GraphHasher {
            object: HashBuilder {
                val digest = MessageDigest.getInstance("SHA3-256");
                override fun update(data: ByteString) = digest.update(data.toByteArray())
                override fun build(): ByteString = ByteString(digest.digest())
            }
        }
        for (className in compilationUnitClassMap.keys) {
            val typeInfo = schemaTypeInfoCache[className]!!
            if (typeInfo.specifiedId != null && typeInfo.specifiedId != UNDEFINED) {
                graphHasher.setAssignedHash(typeInfo.graphNode, typeInfo.specifiedId)
            }
        }
        val schemaIds = mutableMapOf<KSClassDeclaration, ByteString>()
        for ((className, declaration) in compilationUnitClassMap) {
            val typeInfo = schemaTypeInfoCache[className]!!
            val computedHash = try {
                graphHasher.hash(typeInfo.graphNode)
            } catch (err: UnassignedLoopException) {
                // If a loop detected, abort further processing, as we will hit this loop
                // repeatedly. Dump the loop members, so it is easy to find them.
                val nodeToName = schemaTypeInfoCache.entries.associate { Pair(it.value.graphNode, it.key) }
                for (node in err.loop) {
                    val name = nodeToName[node]
                    if (name != null) {
                        val classDeclaration = compilationUnitClassMap[name]
                        if (classDeclaration != null) {
                            logger.error("Dependency loop detected for $name", classDeclaration)
                        }
                    }
                }
                val random = ByteString(Random.Default.nextBytes(32)).toBase64Url()
                logger.error("Specify ($className) schemaId on one of the loop members, e.g, schemaId = \"$random\"")
                return emptyMap()
            }
            if (typeInfo.specifiedHash != null && computedHash != typeInfo.specifiedHash) {
                val encoded = computedHash.toBase64Url()
                logger.error("Schema change detected for $className, new schemaHash = \"${encoded}\"", declaration)
            }
            schemaIds[declaration] = typeInfo.specifiedId ?: computedHash
        }
        return schemaIds
    }

    /**
     * Get schema type info for a class in this compilation unit.
     */
    private fun getSchemaTypeInfoForDefinedClass(
        clazz: KSClassDeclaration,
        qualifiedName: String
    ): SchemaTypeInfo {
        val (schemaHash, schemaId) = getSchemaHashAndId(clazz, false)
        val edges = mutableListOf<Edge>()
        var extra: ByteString? = null
        if (clazz.modifiers.contains(Modifier.SEALED)) {
            // For sealed class, the schema is a union of subclasses
            val annotation = findAnnotation(clazz, ANNOTATION_SERIALIZABLE)!!
            extra = getTypeKey(annotation).encodeToByteString()
            for (subclass in clazz.getSealedSubclasses()) {
                val subclassQualifiedName = subclass.qualifiedName!!.asString()
                val subclassTypeInfo = schemaTypeInfoCache[qualifiedName] ?:
                    getSchemaTypeInfoForDefinedClass(subclass, subclassQualifiedName)
                val name = getTypeId(clazz, subclass, false)
                edges.add(ImmediateEdge(name, EdgeKind.ALTERNATIVE, subclassTypeInfo.graphNode))
            }
        } else {
            // Otherwise schema is determined by the collection of properties. No need to consider
            // superclasses specially, as getAllProperties() returns superclass properties too.
            for (property in clazz.getAllProperties()) {
                if (!property.hasBackingField) {
                    continue
                }
                if (findAnnotation(property, ANNOTATION_MERGE) != null) {
                    // this is essentially an open-ended extension map, treat it as a
                    // special kind of property
                    extra = "+".encodeToByteString()
                    continue
                }
                val name = property.simpleName.asString()
                val type = property.type.resolve()
                // We want exceptions to be serializable (if marked with @CborSerializable),
                // but we want to skip cause
                if (name == "cause" && type.declaration.qualifiedName?.asString() == "kotlin.Throwable") {
                    continue
                }

                val stableClass = if (schemaHash != null) {
                    clazz
                } else {
                    val superClass = getSealedSuperclass(clazz)
                    if (superClass == null) {
                        null
                    } else {
                        val (superHash, _) = getSchemaHashAndId(superClass, false)
                        if (superHash != null) {
                            superClass
                        } else {
                            null
                        }
                    }
                }
                edges.add(createEdge(name, type, stableClass))
            }
        }
        val typeInfo = SchemaTypeInfo(Composite(edges.toList(), extra), schemaHash, schemaId)
        schemaTypeInfoCache[qualifiedName] = typeInfo
        return typeInfo
    }

    private fun createEdge(
        name: String,
        type: KSType,
        stableOwnerClass: KSClassDeclaration?
    ): Edge {
        val typeName = type.declaration.qualifiedName!!.asString()
        val edgeKind = if (type.isMarkedNullable) EdgeKind.OPTIONAL else EdgeKind.REQUIRED
        val target = schemaTypeInfoCache[typeName]
        val compositeType = compilationUnitClassMap[typeName]
        return if (target != null) {
            checkStableDependency(stableOwnerClass, typeName, target)
            ImmediateEdge(name, edgeKind, target.graphNode)
        } else if (compositeType != null) {
            DeferredEdge(name, edgeKind) {
                val schemaTypeInfo = getSchemaTypeInfoForDefinedClass(compositeType, typeName)
                checkStableDependency(stableOwnerClass, typeName, schemaTypeInfo)
                schemaTypeInfo.graphNode
            }
        } else {
            val schemaTypeInfo = if (isCollectionType(typeName)) {
                getSchemaTypeInfoForCollectionClass(type, stableOwnerClass)
            } else {
                getSchemaTypeInfoForLeafClass(typeName, type).also {
                    checkStableDependency(stableOwnerClass, typeName, it)
                }
            }
            ImmediateEdge(name, edgeKind, schemaTypeInfo.graphNode)
        }
    }

    private fun checkStableDependency(
        declaration: KSClassDeclaration?,
        dependencyTypeName: String,
        typeInfo: SchemaTypeInfo
    ) {
        if (declaration == null) {
            // Not stable (does not have schemaHash specified)
            return
        }
        if (typeInfo.graphNode is Composite &&
            typeInfo.specifiedId == null && typeInfo.specifiedHash == null) {
            logger.warn(
                "Dependency not stable (does no have schemaId or schemaHash): $dependencyTypeName",
                declaration
            )
        }
    }

    private fun isCollectionType(typeName: String) = when(typeName) {
        "kotlin.collections.Map", "kotlin.collections.MutableMap",
        "kotlin.collections.List", "kotlin.collections.MutableList",
        "kotlin.collections.Set", "kotlin.collections.MutableSet" -> true
        else -> false
    }

    private fun findLeafCborAnnotation(declaration: KSClassDeclaration): KSAnnotation? {
        val qualifiedName = declaration.qualifiedName!!.asString()
        // Classes that manually implement CBOR serialization are annotated with this annotation.
        val annotation = findAnnotation(declaration, ANNOTATION_SERIALIZATION_IMPLEMENTED)
        if (annotation != null) {
            return annotation
        }
        if (findAnnotation(declaration, ANNOTATION_SERIALIZABLE) == null) {
            logger.error("$qualifiedName: not Cbor-serializable, must be marked with either @$ANNOTATION_SERIALIZATION_IMPLEMENTED or $ANNOTATION_SERIALIZABLE")
            return null
        }
        val cborSchemaIdProperty = resolver.getKSNameFromString(qualifiedName + "_cborSchemaId")
        val prop = resolver.getPropertyDeclarationByName(cborSchemaIdProperty, true)
        if (prop == null) {
            // We should not ever get here, this seems to be a bug in KSP (and sometimes we hit
            // this code). This is caused by resolver.getSymbolsWithAnnotation not returning some
            // of the classes that are actually are annotated with ANNOTATION_SERIALIZABLE.
            throw ForceAddSerializableClass(declaration)
        }
        return findAnnotation(prop, ANNOTATION_SERIALIZABLE_GENERATED)
    }

    private fun getSchemaHashAndId(
        declaration: KSClassDeclaration,
        leaf: Boolean
    ): Pair<ByteString?, ByteString?> {
        var schemaId: ByteString? = null
        var schemaHash: ByteString? = null
        val annotation =
            if (leaf) {
                findLeafCborAnnotation(declaration)
            } else {
                findAnnotation(declaration, ANNOTATION_SERIALIZABLE)
            }
        annotation?.arguments?.forEach { arg ->
            when (val name = arg.name?.asString()) {
                "schemaId", "schemaHash" -> if (arg.value != null) {
                    val field = arg.value.toString()
                    if (field.isNotEmpty()) {
                        val value = if (field == "?") UNDEFINED else field.fromBase64Url()
                        if (name == "schemaId") {
                            schemaId = value
                        } else {
                            schemaHash = value
                        }
                    }
                }
            }
        }
        return Pair(schemaHash, schemaId)
    }

    private fun getSchemaTypeInfoForCollectionClass(
        type: KSType,
        stableOwnerClass: KSClassDeclaration?
    ): SchemaTypeInfo {
        // For the serialization purposes we distinguish only between map and list.
        // Set is like List and mutability is irrelevant.
        val edges = mutableListOf<Edge>()
        for ((index, arg) in type.arguments.withIndex()) {
            edges.add(createEdge(index.toString(), arg.type!!.resolve(), stableOwnerClass))
        }
        // Important: collection classes are parameterized by types, so they are not
        // defined solely by their names and thus are not cached!
        return SchemaTypeInfo(Composite(edges.toList()), null, null)
    }

    private fun simpleLeaf(text: String) = SchemaTypeInfo(Leaf(text), null, null)

    private fun getSchemaTypeInfoForLeafClass(
        qualifiedName: String,
        type: KSType
    ): SchemaTypeInfo {
        val declaration = type.declaration
        val typeInfo = if (declaration is KSClassDeclaration &&
            declaration.classKind == ClassKind.ENUM_CLASS) {
            // NB: get only enum entry declarations!
            val names = declaration.declarations
                .filter { it is KSClassDeclaration && it.classKind == ClassKind.ENUM_ENTRY }
                .map { it.simpleName.asString() }
                .toMutableList()
            names.sortWith { a, b -> a.compareTo(b) }
            simpleLeaf("(" + names.joinToString("|") + ")")
        } else when (qualifiedName) {
            "kotlin.String" -> simpleLeaf("String")
            "kotlin.ByteArray", BYTESTRING_TYPE  -> simpleLeaf("ByteString")
            "kotlin.Long", "kotlin.Int" -> simpleLeaf("Int")
            "kotlin.Float" -> simpleLeaf("Float")
            "kotlin.Double" -> simpleLeaf("Double")
            "kotlin.Boolean" -> simpleLeaf("Boolean")
            "kotlin.time.Instant" -> simpleLeaf("DateTimeString")
            "kotlinx.datetime.LocalDate" -> simpleLeaf("DateString")
            DATA_ITEM_CLASS -> simpleLeaf("Any")
            else -> {
                val (schemaHash, schemaId) = getSchemaHashAndId(declaration as KSClassDeclaration, true)
                SchemaTypeInfo(Leaf(qualifiedName), schemaHash, schemaId ?: schemaHash)
            }
        }
        schemaTypeInfoCache[qualifiedName] = typeInfo
        return typeInfo
    }

    class SchemaTypeInfo(
        val graphNode: Node,
        val specifiedHash: ByteString?,
        val specifiedId: ByteString?
    )

    class ForceAddSerializableClass(val declaration: KSClassDeclaration): Exception()
}

