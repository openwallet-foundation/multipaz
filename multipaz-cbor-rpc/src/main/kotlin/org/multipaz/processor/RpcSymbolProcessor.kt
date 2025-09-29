package org.multipaz.processor

import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeReference

/**
 * Kotlin Annotation Processor that generates dispatching code and stub
 * implementations for RPC calls. It processes annotations
 * defined in [org.multipaz.rpc.annotation] package.
 */
class RpcSymbolProcessor(
    private val options: Map<String, String>,
    private val logger: KSPLogger,
    private val codeGenerator: CodeGenerator,
) : SymbolProcessor {

    companion object {
        const val ANNOTATION_PACKAGE = "org.multipaz.rpc.annotation"
        const val ANNOTATION_STATE = "RpcState"
        const val ANNOTATION_INTERFACE = "RpcInterface"
        const val ANNOTATION_EXCEPTION = "RpcException"
        const val ANNOTATION_METHOD = "RpcMethod"
        const val RPC_DISPATCHER = "org.multipaz.rpc.handler.RpcDispatcher"
        const val RPC_NOTIFIER = "org.multipaz.rpc.handler.RpcNotifier"
        const val NOTIFIABLE_INTERFACE = "org.multipaz.rpc.client.RpcNotifiable"
        const val RPC_STUB_BASE = "org.multipaz.rpc.client.RpcStub"
        const val RPC_DISPATCHER_LOCAL = "org.multipaz.rpc.handler.RpcDispatcherLocal"
        const val RPC_RETURN_CODE = "org.multipaz.rpc.handler.RpcReturnCode"
        const val RPC_EXCEPTION_MAP = "org.multipaz.rpc.handler.RpcExceptionMap"
        const val BACKEND_ENVIRONMENT = "org.multipaz.rpc.backend.BackendEnvironment"
        const val RPC_NOTIFICATIONS = "org.multipaz.rpc.handler.RpcNotifications"
        const val MUTABLE_SHARED_FLOW_CLASS = "kotlinx.coroutines.flow.MutableSharedFlow"
        const val FLOW_COLLECTOR_CLASS = "kotlinx.coroutines.flow.FlowCollector"
        const val AS_SHARED_FLOW = "kotlinx.coroutines.flow.asSharedFlow"
        const val FLOW_MAP = "kotlinx.coroutines.flow.map"
    }

    /**
     * Processor main entry point.
     */
    override fun process(resolver: Resolver): List<KSAnnotated> {
        resolver.getSymbolsWithAnnotation("$ANNOTATION_PACKAGE.$ANNOTATION_STATE")
            .filterIsInstance<KSClassDeclaration>().forEach(this::processStateClass)
        resolver.getSymbolsWithAnnotation("$ANNOTATION_PACKAGE.$ANNOTATION_INTERFACE")
            .filterIsInstance<KSClassDeclaration>().forEach(this::processRpcInterface)
        resolver.getSymbolsWithAnnotation("$ANNOTATION_PACKAGE.$ANNOTATION_EXCEPTION")
            .filterIsInstance<KSClassDeclaration>().forEach(this::processException)
        return listOf()
    }

    private fun findRpcInterface(stateClass: KSClassDeclaration): KSClassDeclaration? {
        var rpcInterface: KSClassDeclaration? = null
        for (superType in stateClass.superTypes) {
            val declaration = superType.resolve().declaration
            val annotation = findAnnotation(declaration, ANNOTATION_INTERFACE)
            if (annotation != null) {
                if (rpcInterface != null) {
                    logger.error("Only a single rpc interface can be implemented", stateClass)
                } else {
                    rpcInterface = declaration as KSClassDeclaration
                }
            }
        }
        return rpcInterface
    }

    private fun processStateClass(stateClass: KSClassDeclaration) {
        val annotation = findAnnotation(stateClass, ANNOTATION_STATE)
        val path = getEndpointFromStateType(stateClass)
        val creatable = getBooleanArgument(annotation, "creatable", false)
        val rpcInterface = findRpcInterface(stateClass)
        if (rpcInterface == null) {
            logger.error("Must directly inherit from an interface marked with @RpcInterface",
                stateClass)
            return
        }
        val notificationType = notificationType(rpcInterface)

        val operations = extractOperations(rpcInterface)

        val rpcInfo = RpcInterfaceInfo(
            path, stateClass, rpcInterface, notificationType, operations)

        if (!stateClass.isAbstract()) {
            val companionClass = CborSymbolProcessor.getCompanion(stateClass)
            if (companionClass == null) {
                logger.error("Companion object required for non-abstract back-end state classes", stateClass)
            } else {
                generateBackendRegistration(creatable, rpcInfo)
            }
        }
    }

    private fun extractOperations(rpcInterface: KSClassDeclaration): List<RpcOperationInfo> {
        val operations = mutableListOf<RpcOperationInfo>()
        rpcInterface.getAllFunctions().forEach { function ->
            val methodAnnotation = findAnnotation(function, ANNOTATION_METHOD) ?: return@forEach
            val returnType = function.returnType?.resolve()
            val type = if (returnType?.declaration?.qualifiedName?.asString() == "kotlin.Unit") {
                null
            } else {
                returnType
            }
            val parameters = mutableListOf<RpcOperationParameterInfo>()
            function.parameters.forEach { parameter ->
                val parameterType = parameter.type.resolve()
                val parameterName = parameter.name?.asString()
                if (parameterName == null) {
                    logger.error("Parameter name required", parameter)
                } else {
                    val clientTypeInfo = getInterfaceRpcTypeInfo(parameterType)
                    parameters.add(
                        RpcOperationParameterInfo(
                            parameterName, clientTypeInfo, parameterType
                        )
                    )
                }
            }
            val clientTypeInfo = getInterfaceRpcTypeInfo(type)
            val methodName = function.simpleName.getShortName()
            val methodPath = getStringArgument(methodAnnotation, "endpoint", methodName)
            operations.add(
                RpcOperationInfo(
                    function, methodPath, methodName, parameters, type, clientTypeInfo
                )
            )
        }
        return operations.toList()
    }

    private fun processRpcInterface(rpcInterface: KSClassDeclaration) {
        val annotation = findAnnotation(rpcInterface, ANNOTATION_INTERFACE)
        val operations = extractOperations(rpcInterface)
        val notificationType = notificationType(rpcInterface)
        val interfaceName = rpcInterface.simpleName.asString()
        val interfaceFullName = rpcInterface.qualifiedName!!.asString()
        val stubName = getStringArgument(
            annotation, "stubName", "${interfaceFullName}Stub"
        )
        val containingFile = rpcInterface.containingFile!!
        generateStub(containingFile, interfaceFullName, interfaceName,
            notificationType, stubName, operations)
    }

    private fun processException(exceptionClass: KSClassDeclaration) {
        val companionClass = CborSymbolProcessor.getCompanion(exceptionClass)
        if (companionClass == null) {
            logger.error("Companion object required", exceptionClass)
            return
        }

        val baseName = exceptionClass.simpleName.asString()
        val annotation = findAnnotation(exceptionClass, ANNOTATION_EXCEPTION)
        val exceptionId = getStringArgument(
            annotation = annotation,
            name = "exceptionId",
            defaultValue = if (baseName.endsWith("Exception")) {
                baseName.substring(0, baseName.length - 9)
            } else {
                baseName
            }
        )
        val containingFile = exceptionClass.containingFile!!
        val packageName = exceptionClass.packageName.asString()
        val type = exceptionClass.asType(listOf())
        with(CodeBuilder()) {
            importQualifiedName(CborSymbolProcessor.DATA_ITEM_CLASS)
            importQualifiedName(exceptionClass)
            importQualifiedName(RPC_EXCEPTION_MAP)
            importQualifiedName(CborSymbolProcessor.BSTR_TYPE)

            block("fun $baseName.Companion.register(exceptionMapBuilder: RpcExceptionMap.Builder)") {
                line("exceptionMapBuilder.addException<$baseName>(")
                withIndent {
                    line("\"$exceptionId\",")
                    block("", hasBlockAfter = true, lambdaParameters = "exception") {
                        line(CborSymbolProcessor.serializeValue(
                            this, "exception", type
                        ))
                    }
                    append(",")
                    endLine()
                    block("", hasBlockAfter = true, lambdaParameters = "dataItem") {
                        line(CborSymbolProcessor.deserializeValue(
                            this, "dataItem", type
                        ))
                    }
                    append(",")
                    endLine()
                    line("listOf(")
                    withIndent {
                        line("$baseName::class,")
                        for (subclass in exceptionClass.getSealedSubclasses()) {
                            importQualifiedName(subclass)
                            line("${subclass.simpleName.asString()}::class,")
                        }
                        line(")")
                    }
                }
                line(")")
            }

            writeToFile(
                codeGenerator = codeGenerator,
                dependencies = Dependencies(false, containingFile),
                packageName = packageName,
                fileName = "${baseName}_Registration"
            )
        }
    }

    private fun generateStub(
        containingFile: KSFile,
        interfaceFullName: String,
        interfaceName: String,
        notificationType: KSType?,
        stubName: String,
        operations: List<RpcOperationInfo>
    ) {
        val lastDotStub = stubName.lastIndexOf('.')
        if (lastDotStub < 0) {
            throw IllegalArgumentException("Expected fully-qualified name: $stubName ($interfaceFullName)")
        }
        val packageName = stubName.substring(0, lastDotStub)
        val baseName = stubName.substring(lastDotStub + 1)
        with(CodeBuilder()) {
            importQualifiedName(CborSymbolProcessor.DATA_ITEM_CLASS)
            importQualifiedName(RPC_DISPATCHER)
            importQualifiedName(RPC_NOTIFIER)
            importQualifiedName(RPC_RETURN_CODE)
            importQualifiedName(CborSymbolProcessor.BSTR_TYPE)
            importQualifiedName(interfaceFullName)

            emptyLine()
            line("class $baseName(")
            withIndent {
                line("endpoint: String,")
                line("dispatcher: RpcDispatcher,")
                line("notifier: RpcNotifier,")
                line("state: DataItem = Bstr(byteArrayOf())")
            }
            importQualifiedName(RPC_STUB_BASE)
            block("): RpcStub(endpoint, dispatcher, notifier, state), $interfaceName") {
                if (notificationType != null) {
                    line("private var disposed = false")
                    val simpleName = notificationType.declaration.simpleName.asString()
                    if (notificationType.declaration is KSClassDeclaration) {
                        importQualifiedName(notificationType.declaration as KSClassDeclaration)
                    }
                    importQualifiedName(MUTABLE_SHARED_FLOW_CLASS)
                    importQualifiedName(FLOW_COLLECTOR_CLASS)
                    importQualifiedName(AS_SHARED_FLOW)
                    importQualifiedName(FLOW_MAP)
                    line("private val notificationFlow = MutableSharedFlow<$simpleName>(0, 1)")
                    block("override suspend fun collect(collector: FlowCollector<$simpleName>)") {
                        line("notificationFlow.collect(collector)")
                    }
                    emptyLine()
                    importQualifiedName(CborSymbolProcessor.BYTESTRING_TYPE)
                    block("suspend fun startNotifications()") {
                        block("rpcNotifier.register(rpcEndpoint, rpcState, notificationFlow)") {
                            line(CborSymbolProcessor.deserializeValue(this, "it", notificationType))
                        }
                    }
                    emptyLine()
                    block("suspend fun stopNotifications()") {
                        line("rpcNotifier.unregister(rpcEndpoint, rpcState)")
                    }
                }
                operations.forEach { op ->
                    generateRpcMethodStub(this, notificationType != null, op)
                }
                if (notificationType != null) {
                    emptyLine()
                    block("override suspend fun dispose()") {
                        line("checkNotDisposed()")
                        line("stopNotifications()")
                        line("rpcState = Bstr(byteArrayOf())")
                        line("disposed = true")
                    }
                    emptyLine()
                    block("private fun checkNotDisposed()") {
                        block("if (disposed)") {
                            line("throw IllegalStateException(\"object was disposed\")")
                        }
                    }
                }
                emptyLine()
                block("companion object") {
                    line("fun fromDataItem(")
                    withIndent {
                        line("data: DataItem,")
                        line("dispatcher: RpcDispatcher,")
                        line("notifier: RpcNotifier,")
                    }
                    block("): $baseName") {
                        line("val endpoint = data[\"endpoint\"].asTstr")
                        line("val state = data[\"state\"]")
                        line("return $baseName(endpoint, dispatcher, notifier, state)")
                    }

                    emptyLine()
                    line("fun fromCbor(")
                    withIndent {
                        line("data: ByteArray,")
                        line("dispatcher: RpcDispatcher,")
                        line("notifier: RpcNotifier,")
                    }
                    block("): $baseName") {
                        importQualifiedName(CborSymbolProcessor.CBOR_TYPE)
                        line("val dataItem = Cbor.decode(data)")
                        line("return fromDataItem(dataItem, dispatcher, notifier)")
                    }
                }
            }

            writeToFile(
                codeGenerator = codeGenerator,
                dependencies = Dependencies(false, containingFile),
                packageName = packageName,
                fileName = baseName
            )
        }
    }

    private fun generateRpcMethodStub(
        codeBuilder: CodeBuilder,
        notifications: Boolean,
        op: RpcOperationInfo
    ) {
        with(codeBuilder) {
            emptyLine()
            block("override suspend fun ${opDeclaration(this, op)}") {
                if (notifications) {
                    line("checkNotDisposed()")
                }
                val parameters = op.parameters.map { parameter ->
                    if (parameter.typeInfo == null) {
                        CborSymbolProcessor.serializeValue(
                            this, parameter.name, parameter.type, true
                        )
                    } else {
                        "RpcStub.rpcParameter(${parameter.name})"
                    }
                }

                importQualifiedName(CborSymbolProcessor.BUILD_CBOR_ARRAY)
                line("val rpcState = this.rpcState")
                line("val rpcParameters = buildCborArray {")
                withIndent {
                    line("add(rpcState)")
                    parameters.forEach { parameter ->
                        line("add($parameter)")
                    }
                }
                line("}")
                line("val rpcResponse = rpcDispatcher.dispatch(rpcEndpoint, \"${op.path}\", rpcParameters)")
                if (notifications) {
                    block("if (rpcState != rpcResponse[0])") {
                        line("stopNotifications()")
                        line("this.rpcState = rpcResponse[0]")
                        line("startNotifications()")
                    }
                } else {
                    line("this.rpcState = rpcResponse[0]")
                }
                block("if (rpcResponse[2].asNumber.toInt() != RpcReturnCode.RESULT.ordinal)") {
                    line("this.rpcDispatcher.exceptionMap.handleExceptionReturn(rpcResponse)")
                }
                if (op.typeInfo != null) {
                    importQualifiedName(op.typeInfo.qualifiedStubName)
                    val ctr = op.typeInfo.simpleStubName
                    line("val packed = rpcResponse[3].asArray")
                    line("val target = packed[0].asTstr")
                    line("val result = $ctr(target, rpcDispatcher, rpcNotifier, packed[1])")
                    if (op.typeInfo.notifications) {
                        line("result.startNotifications()")
                    }
                    line("return result")
                } else if (op.type != null) {
                    var result = CborSymbolProcessor.deserializeValue(
                        this, "rpcResponse[3]", op.type
                    )
                    if (op.type.isMarkedNullable) {
                        importQualifiedName(CborSymbolProcessor.SIMPLE_TYPE)
                        result = "if (rpcResponse[3] == Simple.NULL) { null } else { $result }"
                    }
                    line("return $result")
                }
            }
        }
    }

    private fun getEndpointFromStateType(declaration: KSDeclaration): String {
        val baseName = declaration.simpleName.asString()
        val annotation = findAnnotation(declaration, ANNOTATION_STATE)
        return getStringArgument(
            annotation = annotation,
            name = "endpoint",
            defaultValue = if (baseName.endsWith("State")) {
                baseName.substring(0, baseName.length - 5)
            } else {
                baseName
            }
        )
    }

    private fun getInterfaceRpcTypeInfo(type: KSType?): RpcTypeInfo? {
        if (type == null) {
            return null
        }
        val annotation = findAnnotation(type.declaration, ANNOTATION_INTERFACE) ?: return null
        val fullInterfaceName = type.declaration.qualifiedName!!.asString()
        val notificationType = notificationType(type.declaration as KSClassDeclaration)
        val simpleInterfaceName = type.declaration.simpleName.asString()
        val fullStubName = getStringArgument(
            annotation, "stubName", fullInterfaceName + "Stub"
        )
        val simpleStubName = fullStubName.substring(fullStubName.lastIndexOf('.') + 1)
        return RpcTypeInfo(
            fullInterfaceName,
            simpleInterfaceName,
            fullStubName,
            simpleStubName,
            notificationType != null
        )
    }

    private fun opDeclaration(codeBuilder: CodeBuilder, op: RpcOperationInfo): String {
        val signature = signature(codeBuilder, op)
        if (op.type == null) {
            return "${op.name}($signature)"
        } else {
            val type = if (op.typeInfo != null) {
                codeBuilder.importQualifiedName(op.typeInfo.qualifiedInterfaceName)
                op.typeInfo.simpleInterfaceName
            } else {
                CborSymbolProcessor.typeRefNullable(codeBuilder, op.type)
            }
            return "${op.name}($signature): $type"
        }
    }

    private fun signature(codeBuilder: CodeBuilder, op: RpcOperationInfo): String {
        val builder = StringBuilder()
        op.parameters.forEach { parameter ->
            if (builder.isNotEmpty()) {
                builder.append(", ")
            }
            val type = if (parameter.typeInfo != null) {
                codeBuilder.importQualifiedName(parameter.typeInfo.qualifiedInterfaceName)
                parameter.typeInfo.simpleInterfaceName
            } else {
                CborSymbolProcessor.typeRefNullable(codeBuilder, parameter.type)
            }
            builder.append("${parameter.name}: $type")
        }
        return builder.toString()
    }

    private fun generateBackendRegistration(creatable: Boolean, rpcInfo: RpcInterfaceInfo) {
        val containingFile = rpcInfo.stateClass.containingFile!!
        val packageName = rpcInfo.stateClass.packageName.asString()
        val baseName = rpcInfo.stateClass.simpleName.asString()
        with(CodeBuilder()) {
            importQualifiedName(CborSymbolProcessor.DATA_ITEM_CLASS)
            importQualifiedName(rpcInfo.stateClass)
            importQualifiedName(RPC_DISPATCHER_LOCAL)
            importQualifiedName(CborSymbolProcessor.BSTR_TYPE)

            block("private fun serialize(state: $baseName?): DataItem") {
                line("return state?.toDataItem() ?: Bstr(byteArrayOf())")
            }

            emptyLine()
            block("private fun deserialize(state: DataItem): $baseName") {
                if (creatable) {
                    block(
                        "return if (state is Bstr && state.value.isEmpty())", hasBlockAfter = true
                    ) {
                        line("$baseName()")
                    }
                    block("else", hasBlockBefore = true) {
                        line("$baseName.fromDataItem(state)")
                    }
                } else {
                    line("return $baseName.fromDataItem(state)")
                }
            }

            if (rpcInfo.notificationType != null) {
                emptyLine()
                importQualifiedName(BACKEND_ENVIRONMENT)
                importQualifiedName(RPC_NOTIFICATIONS)
                val declaration = rpcInfo.notificationType.declaration
                if (declaration is KSClassDeclaration) {
                    importQualifiedName(declaration)
                }
                val notificationType = declaration.simpleName.asString()
                block("suspend fun $baseName.emit(notification: $notificationType)") {
                    importQualifiedName(BACKEND_ENVIRONMENT)
                    importQualifiedName(RPC_NOTIFICATIONS)
                    line("val notifications = BackendEnvironment.getInterface(RpcNotifications::class)!!")
                    val notification = CborSymbolProcessor.serializeValue(
                        this, "notification", rpcInfo.notificationType)
                    line("notifications.emit(\"${rpcInfo.path}\", this.toDataItem(), $notification)")
                }

                emptyLine()
                importQualifiedName(FLOW_COLLECTOR_CLASS)
                importQualifiedName(CborSymbolProcessor.BSTR_TYPE)
                importQualifiedName(CborSymbolProcessor.CBOR_TYPE)
                block("suspend fun $baseName.collectImpl(collector: FlowCollector<$notificationType>)") {
                    importQualifiedName(MUTABLE_SHARED_FLOW_CLASS)
                    line("val flow = MutableSharedFlow<$notificationType>(0, 1)")
                    line("val notifier = BackendEnvironment.getInterface(RpcNotifier::class)!!")
                    line("val key = Bstr(Cbor.encode(this.toDataItem()))")
                    block("notifier.register(\"${rpcInfo.path}\", key, flow)") {
                        line(CborSymbolProcessor.deserializeValue(
                            this, "it", rpcInfo.notificationType))
                    }
                    line("flow.collect(collector)")
                }

                emptyLine()
                block("suspend fun $baseName.disposeImpl()") {
                    importQualifiedName(RPC_NOTIFIER)
                    line("val notifier = BackendEnvironment.getInterface(RpcNotifier::class)!!")
                    line("val key = Bstr(Cbor.encode(this.toDataItem()))")
                    line("notifier.unregister(\"${rpcInfo.path}\", key)")
                }
            }

            emptyLine()
            block("fun $baseName.Companion.register(dispatcherBuilder: RpcDispatcherLocal.Builder)") {
                block("dispatcherBuilder.addEndpoint(\"${rpcInfo.path}\", $baseName::class, ::serialize, ::deserialize)") {
                    if (creatable) {
                        line("creatable()")
                    }
                    rpcInfo.operations.forEach { op ->
                        block(
                            "dispatch(\"${op.path}\")",
                            lambdaParameters = "dispatcher, rpcState, flowMethodArgList"
                        ) {
                            var index = 0
                            val params = op.parameters.map { parameter ->
                                val param = "flowMethodArgList[$index]"
                                index++
                                if (parameter.typeInfo != null) {
                                    val declaration = parameter.type.declaration
                                    importQualifiedName(declaration as KSClassDeclaration)
                                    val simpleName = declaration.simpleName.asString()
                                    "dispatcher.decodeStateParameter($param) as $simpleName"
                                } else {
                                    CborSymbolProcessor.deserializeValue(
                                        this, param, parameter.type, true
                                    )
                                }

                            }
                            val resVal = if (op.type == null) "" else "val result = "
                            line("${resVal}rpcState.${op.name}(")
                            withIndent {
                                params.forEach {
                                    line("$it, ")
                                }
                            }
                            line(")")
                            if (op.type == null) {
                                line("Bstr(byteArrayOf())")
                            } else if (op.typeInfo != null) {
                                line("dispatcher.encodeStateResult(result)")
                            } else {
                                val serialization = CborSymbolProcessor.serializeValue(
                                    this, "result", op.type
                                )
                                line(
                                    if (op.type.isMarkedNullable) {
                                        importQualifiedName(CborSymbolProcessor.SIMPLE_TYPE)
                                        "if (result == null) { Simple.NULL } else { $serialization }"
                                    } else {
                                        serialization
                                    }
                                )
                            }
                        }
                    }
                }
            }

            writeToFile(
                codeGenerator = codeGenerator,
                dependencies = Dependencies(false, containingFile),
                packageName = packageName,
                fileName = "${baseName}_Registration"
            )
        }
    }

    private fun notificationType(clazz: KSClassDeclaration): KSType? {
        for (supertype in clazz.superTypes) {
            val notificationType = notificationType(supertype)
            if (notificationType != null) {
                return notificationType
            }
        }
        return null
    }

    private fun notificationType(typeReference: KSTypeReference): KSType? {
        val declaration = typeReference.resolve().declaration
        if (declaration.qualifiedName?.asString() == NOTIFIABLE_INTERFACE) {
            return typeReference.element?.typeArguments?.get(0)?.type?.resolve()
        }
        if (declaration is KSClassDeclaration) {
            return notificationType(declaration)
        }
        return null
    }

    private fun findAnnotation(declaration: KSDeclaration, simpleName: String): KSAnnotation? {
        for (annotation in declaration.annotations) {
            if (annotation.shortName.asString() == simpleName && annotation.annotationType.resolve().declaration.packageName.asString() == ANNOTATION_PACKAGE) {
                return annotation
            }
        }
        return null
    }

    private fun getStringArgument(
        annotation: KSAnnotation?, name: String, defaultValue: String
    ): String {
        annotation?.arguments?.forEach { arg ->
            if (arg.name?.asString() == name) {
                val field = arg.value.toString()
                if (field.isNotEmpty() && field != "null") {
                    return field
                }
            }
        }
        return defaultValue
    }

    private fun getBooleanArgument(
        annotation: KSAnnotation?, name: String, defaultValue: Boolean
    ): Boolean {
        annotation?.arguments?.forEach { arg ->
            if (arg.name?.asString() == name) {
                val field = arg.value
                if (field is Boolean) {
                    return field
                }
            }
        }
        return defaultValue
    }

    data class RpcInterfaceInfo(
        val path: String,
        val stateClass: KSClassDeclaration,
        val interfaceClass: KSClassDeclaration,
        val notificationType: KSType?,
        val operations: List<RpcOperationInfo>
    )

    data class RpcOperationInfo(
        val declaration: KSFunctionDeclaration,
        val path: String,
        val name: String,
        val parameters: List<RpcOperationParameterInfo>,
        val type: KSType?,
        val typeInfo: RpcTypeInfo?,
    )

    data class RpcOperationParameterInfo(
        val name: String,
        val typeInfo: RpcTypeInfo?,
        val type: KSType
    )

    data class RpcTypeInfo(
        val qualifiedInterfaceName: String,
        val simpleInterfaceName: String,
        val qualifiedStubName: String,
        val simpleStubName: String,
        val notifications: Boolean
    )
}