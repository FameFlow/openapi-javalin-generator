package com.github.raymank26

import com.squareup.kotlinpoet.*
import java.nio.file.Path

class JavalinControllerGenerator(
    private val specMetadata: SpecMetadata,
    private val basePackageName: String,
    private val baseGenerationPath: Path,
) {

    fun generate() {
        val serverInterfaceType = ClassName(basePackageName, "${specMetadata.namePrefix}Spec")
        val typeBuilder = TypeSpec.classBuilder("${specMetadata.namePrefix}JavalinController")
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter(
                        ParameterSpec.builder("server", serverInterfaceType)
                            .build()
                    )
                    .build()
            )

        typeBuilder.addProperty(
            PropertySpec.builder("server", serverInterfaceType)
                .addModifiers(KModifier.PRIVATE)
                .initializer("server")
                .build()
        )

        typeBuilder.addFunction(
            FunSpec
                .builder("bind")
                .addParameter("javalin", ClassName("io.javalin", "Javalin"))
                .addCode(buildCodeBlock {

                    specMetadata.operations.forEach { operationDescriptor ->
                        val requestBody: RequestBody? = operationDescriptor.requestBody
                        val parameters = buildCodeBlock {
                            add("(")
                            val paramDescriptors = operationDescriptor.paramDescriptors
                            for ((index, paramDescriptor) in paramDescriptors.withIndex()) {
                                val readFunc = if (paramDescriptor.typePropertyDescriptor.required) {
                                    "readRequiredParam"
                                } else {
                                    "readOptionalParam"
                                }
                                val formatFunc = when (paramDescriptor.typePropertyDescriptor.type) {
                                    TypeDescriptor.Int64Type -> "{ it.toLongOrNull() }"
                                    TypeDescriptor.IntType -> "{ it.toIntOrNull() }"
                                    TypeDescriptor.FloatType -> "{ it.toFloatOrNull() }"
                                    TypeDescriptor.StringType -> "{ it }"
                                    TypeDescriptor.BooleanType -> "{ it.toBooleanStrictOrNull() }"
                                    else -> error("Cannot get param of complex type")
                                }
                                val getParamFunc = when (paramDescriptor.place) {
                                    "query" -> "ctx.queryParam(%S)"
                                    "path" -> "ctx.pathParam(%S)"
                                    "header" -> "ctx.header(%S)"
                                    else -> error("Param place = ${paramDescriptor.place} is not found")
                                }
                                val paramName = paramDescriptor.typePropertyDescriptor.name
                                add("%L(%S, ${getParamFunc}) %L", readFunc, paramName, paramName, formatFunc)
                                if (index != paramDescriptors.size - 1) {
                                    add(", ")
                                }
                            }
                            if (requestBody != null) {
                                if (paramDescriptors.isNotEmpty()) {
                                    add(", ")
                                }
                                add("body")
                            }
                            addStatement(")")

                        }
                        addStatement(
                            """javalin.%L(%S) { ctx -> """,
                            operationDescriptor.method,
                            operationDescriptor.path
                        )
                            .withIndent {
                                addResponseProcessingCode(requestBody, operationDescriptor, parameters)
                            }
                            .addStatement("}")
                    }
                })
                .build()
        )

        typeBuilder.addFunction(
            FunSpec.builder("noBodyFound")
                .returns(NOTHING)
                .addCode(buildCodeBlock {
                    addStatement(
                        "throw %T(details = mapOf(\"type\" to \"noBodyFound\"))",
                        ClassName("io.javalin.http", "BadRequestResponse")
                    )
                })
                .build()
        )

        typeBuilder.addFunction(
            FunSpec.builder("getFileFromBody")
                .addParameter(ParameterSpec("ctx", ClassName("io.javalin.http", "Context")))
                .addParameter(ParameterSpec("fileName", String::class.asTypeName()))
                .addParameter(ParameterSpec("cleanupHandler", ClassName(basePackageName, "CleanupHandler")))
                .returns(ClassName(basePackageName, "FileUpload").copy(nullable = true))
                .addCode(buildCodeBlock {
                    add(
                        """
                        val uploadedFile = ctx.uploadedFile(fileName) ?: return null
                        val file = %T.createTempFile("part-", ".tmp")
                        file.outputStream().%M().use {
                            uploadedFile.content().copyTo(it)
                        }
                        cleanupHandler.add { file.delete() }
                        return FileUpload(name = uploadedFile.filename(), file, uploadedFile.contentType()!!)
                    """.trimIndent(),
                        ClassName("java.io", "File"),
                        MemberName("kotlin.io", "buffered")
                    )
                })
                .build()
        )

        typeBuilder.addFunction(
            FunSpec.builder("noParamFound")
                .addParameter("paramName", String::class)
                .returns(NOTHING)
                .addCode(buildCodeBlock {
                    addStatement(
                        "throw %T(details = mapOf(\"paramName\" to paramName, \"type\" to \"noParamFound\"))",
                        ClassName("io.javalin.http", "BadRequestResponse")
                    )
                })
                .build()
        )

        typeBuilder.addFunction(
            FunSpec.builder("readRequiredParam")
                .addParameter("paramName", String::class)
                .addParameter("param", String::class.asClassName().copy(nullable = true))
                .addTypeVariable(TypeVariableName("T"))
                .addParameter(
                    "format", LambdaTypeName.get(
                        parameters = listOf(ParameterSpec("str", String::class.asTypeName())),
                        returnType = TypeVariableName("T").copy(nullable = true)
                    )
                )
                .returns(TypeVariableName("T"))
                .addCode(buildCodeBlock {
                    add(
                        """
                        |if (param.isNullOrEmpty()) {
                        |    noParamFound(paramName)
                        |}
                        |return format(param) ?: badParamFormat(paramName)
                    """.trimMargin()
                    )
                })
                .build()
        )

        typeBuilder.addFunction(
            FunSpec.builder("readOptionalParam")
                .addParameter("paramName", String::class)
                .addParameter("param", String::class.asClassName().copy(nullable = true))
                .addTypeVariable(TypeVariableName("T"))
                .addParameter(
                    "format", LambdaTypeName.get(
                        parameters = listOf(ParameterSpec("str", String::class.asTypeName())),
                        returnType = TypeVariableName("T").copy(nullable = true)
                    )
                )
                .returns(TypeVariableName("T").copy(nullable = true))
                .addCode(buildCodeBlock {
                    add(
                        """
                        |if (param.isNullOrEmpty()) {
                        |    return null
                        |}
                        |return format(param) ?: badParamFormat(paramName)
                    """.trimMargin()
                    )
                })
                .build()
        )

        typeBuilder.addFunction(
            FunSpec.builder("getValidBody")
                .addTypeVariable(TypeVariableName("T"))
                .addParameter(
                    "parser", LambdaTypeName.get(
                        parameters = listOf(),
                        returnType = TypeVariableName("T").copy(nullable = false)
                    )
                )
                .returns(TypeVariableName("T"))
                .addCode(buildCodeBlock {
                    add(
                        """
                        |try {
                        |   return parser.invoke()
                        |} catch (e: Exception) {
                        |   if (e is BadRequestResponse) {
                        |       throw e
                        |   }
                        |   throw BadRequestResponse(details = mapOf("type" to "illegalBody"))
                        |}
                    """.trimMargin()
                    )
                })
                .build()
        )


        typeBuilder.addFunction(
            FunSpec.builder("badParamFormat")
                .addParameter("paramName", String::class)
                .returns(NOTHING)
                .addCode(buildCodeBlock {
                    addStatement(
                        "throw %T(details = mapOf(\"paramName\" to paramName, \"type\" to \"badParamFormat\"))",
                        ClassName("io.javalin.http", "BadRequestResponse")
                    )
                })
                .build()
        )

        FileSpec.builder(basePackageName, "JavalinController")
            .addType(typeBuilder.build())
            .build()
            .writeTo(baseGenerationPath)
    }

    private fun CodeBlock.Builder.addResponseProcessingCode(
        requestBody: RequestBody?,
        operationDescriptor: OperationDescriptor,
        parameters: CodeBlock,
    ) {
        addStatement("val cleanupHandler = %T()", ClassName(basePackageName, "CleanupHandler"))
        addStatement("try {")
        indent()
        val requestBodyBlock = if (requestBody != null) {
            buildCodeBlock {
                addStatement("val body = getValidBody { when (ctx.contentType()?.split(\";\")?.first()) {")
                withIndent {
                    requestBody.contentTypeToType.forEach { (key, value): Map.Entry<RequestBodyMediaType, TypeDescriptor> ->
                        val parser = when (key) {
                            RequestBodyMediaType.FormData -> buildCodeBlock {
                                val targetObj = (value as TypeDescriptor.Object)
                                add(
                                    "%T(%T(",
                                    ClassName(
                                        basePackageName,
                                        requestBody.clsName,
                                        key.clsName
                                    ),
                                    ClassName(basePackageName, targetObj.clsName!!)
                                )
                                withIndent(3) {
                                    targetObj.properties.forEach { property ->
                                        addFormParam(property)
                                    }
                                }
                                addStatement("))")
                            }

                            RequestBodyMediaType.Json -> buildCodeBlock {
                                addStatement(
                                    "%T(ctx.bodyAsClass(%L::class.java))",
                                    ClassName(
                                        basePackageName,
                                        requestBody.clsName,
                                        key.clsName
                                    ),
                                    ClassName(
                                        basePackageName,
                                        (value as TypeDescriptor.Object).clsName!!
                                    )
                                )
                            }

                            RequestBodyMediaType.Xml -> buildCodeBlock {
                                addStatement("TODO(\"Not implemented\")")
                            }

                            RequestBodyMediaType.MultipartFormData -> buildCodeBlock {
                                val rootCls = ClassName(
                                    basePackageName,
                                    requestBody.clsName,
                                    key.clsName
                                )

                                val targetCls = value as TypeDescriptor.Object
                                addStatement(
                                    "%T(%T(", rootCls, ClassName(
                                        basePackageName,
                                        targetCls.clsName!!
                                    )
                                )
                                withIndent {
                                    for (property in targetCls.properties) {
                                        if (property.type is TypeDescriptor.FileUploadType) {
                                            addStatement(
                                                "getFileFromBody(ctx, %S, cleanupHandler)%L,",
                                                property.name, if (property.required) "!!" else ""
                                            )
                                        } else {
                                            addFormParam(property)
                                        }
                                    }
                                }
                                addStatement("))")
                            }
                        }
                        add("%S -> ", key.mediaType)
                        add(parser)
                    }
                    addStatement("else -> noBodyFound()")
                }
                addStatement("}}")
            }
        } else {
            CodeBlock.builder().build()
        }
        add(requestBodyBlock)
        add("val response = server.%L", operationDescriptor.operationId)
        add(parameters)

        add(buildCodeBlock {
            addStatement("when (response) {")
                .withIndent {
                    operationDescriptor.responseBody.statusCodeToClsName.forEach { (code, option) ->
                        val sealedClsName = ClassName(
                            basePackageName,
                            operationDescriptor.responseBody.clsName
                        )
                        addStatement("is %T.%L -> {", sealedClsName, option.clsName)
                            .withIndent {
                                val statusCode = if (code == "default")
                                    "response.${option.clsName.decapitalized()}.code"
                                else code
                                addHeaders(option)
                                addStatement("ctx.status(%L)", statusCode)
                                if (option is ResponseBodySealedOption.Parametrized) {
                                    if (!option.isFile) {
                                        addStatement(
                                            "ctx.json(response.%L)",
                                            option.clsName.decapitalized()
                                        )
                                    } else {
                                        addStatement(
                                            """
                                            cleanupHandler.add { response.file.delete() }
                                            response.file.%M().use {
                                                it.%M(ctx.outputStream())
                                            }
                                        """.trimIndent(),
                                            MemberName("kotlin.io", "inputStream"),
                                            MemberName("kotlin.io", "copyTo")
                                        )

                                    }
                                }
                            }
                            .addStatement("}")
                    }
                }
                .addStatement("}")
        })
        unindent()
        addStatement("} finally {")
        withIndent {
            addStatement("cleanupHandler.cleanup()")
        }
        addStatement("}")
    }

    private fun CodeBlock.Builder.addFormParam(property: TypePropertyDescriptor) {
        add("ctx.formParam(%S)", property.name)
        add(if (property.required) "!!" else "?")
        when (property.type) {
            TypeDescriptor.Int64Type -> addStatement(".toLong(),")
            TypeDescriptor.IntType -> addStatement(".toInt(),")
            TypeDescriptor.FloatType -> addStatement(".toFloat(),")
            TypeDescriptor.StringType -> addStatement(".toString(),")
            TypeDescriptor.StringType -> addStatement(".toBooleanStrict(),")
            else -> error("Not supported type = ${property.type}")
        }
    }
}

private fun CodeBlock.Builder.addHeaders(option: ResponseBodySealedOption) {
    if (option.headers == null) {
        return
    }
    option.headers.properties.forEach { header ->
        val headerName = if (header.name.contains("-")) {
            "`${header.name}`"
        } else {
            header.name
        }
        val propertyRef = "response.${option.headers.clsName!!.decapitalized()}.${headerName}"
        if (header.required) {
            addStatement("ctx.header(%S, %L)", header.name, propertyRef)
        } else {
            addStatement("if (%L != null) {", propertyRef)
            withIndent {
                addStatement("ctx.header(%S, %L)", header.name, propertyRef)
            }
            addStatement("}")
        }
    }
}

inline fun CodeBlock.Builder.withIndent(
    steps: Int = 1,
    builderAction: CodeBlock.Builder.() -> Unit,
): CodeBlock.Builder {
    for (i in 0..steps) {
        indent()
    }
    builderAction(this)
    for (i in 0..steps) {
        unindent()
    }
    return this
}
