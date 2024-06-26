package com.github.raymank26

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.TypeSpec.Companion.interfaceBuilder
import java.nio.file.Path

class SpecInterfaceGenerator(
    private val specMetadata: SpecMetadata,
    private val basePackageName: String,
    private val baseGenerationPath: Path
) {

    fun generate() {
        val typeSpecBuilder = interfaceBuilder("${specMetadata.namePrefix}Spec")

        specMetadata.operations.forEach { operationDescriptor ->
            val funBuilder = FunSpec.builder(operationDescriptor.operationId)
            funBuilder.addModifiers(KModifier.ABSTRACT)
            for (paramDescriptor in operationDescriptor.paramDescriptors) {
                funBuilder.addParameter(
                    ParameterSpec(
                        transformToCorrectName(paramDescriptor.name),
                        getParamTypeName(
                            paramDescriptor.typePropertyDescriptor.name,
                            paramDescriptor.typePropertyDescriptor.type,
                            paramDescriptor.typePropertyDescriptor.required
                        )
                    )
                )
            }
            if (operationDescriptor.requestBody != null) {
                funBuilder.addParameter(
                    ParameterSpec(
                        "requestBody",
                        ClassName(basePackageName, operationDescriptor.requestBody.clsName)
                    )
                )
            }
            funBuilder.returns(
                getParamTypeName(
                    operationDescriptor.responseBody.clsName,
                    operationDescriptor.responseBody.type,
                    required = true
                )
            )
            typeSpecBuilder.addFunction(funBuilder.build())
        }
        FileSpec.builder(basePackageName, "${specMetadata.namePrefix}Spec")
            .addType(typeSpecBuilder.build())
            .build()
            .writeTo(baseGenerationPath)
    }

    private fun getParamTypeName(name: String, descriptor: TypeDescriptor, required: Boolean): TypeName {
        return when (descriptor) {
            is TypeDescriptor.Array -> ClassName(basePackageName, name)
            is TypeDescriptor.Object -> ClassName(basePackageName, name)
            is TypeDescriptor.OneOf -> ClassName(basePackageName, name)
            is TypeDescriptor.RefType -> ClassName(basePackageName, descriptor.name)
            is TypeDescriptor.SingleValueType -> ClassName(basePackageName, name)
            TypeDescriptor.Int64Type -> Long::class.java.asTypeName()
            TypeDescriptor.IntType -> Int::class.java.asTypeName()
            TypeDescriptor.FileUploadType -> ClassName(basePackageName, "FileUpload")
            TypeDescriptor.FloatType -> Float::class.java.asTypeName()
            TypeDescriptor.StringType -> ClassName("kotlin", "String")
            TypeDescriptor.BooleanType -> Boolean::class.java.asTypeName()
            TypeDescriptor.FileType -> error("Not supported")
        }.copy(nullable = !required)
    }
}

private fun transformToCorrectName(name: String): String {
    val resChars = mutableListOf<Char>()

    var index = 0
    while (index < name.length) {
        val c = name[index]
        if (index == 0 && c.isUpperCase()) {
            resChars.add(c.lowercaseChar())
            index++
            continue
        }
        if (c == '_' || c == '-') {
            resChars.add(name[index + 1].uppercase()[0])
            index += 2
            continue
        }
        resChars.add(name[index])
        index++
    }
    return resChars.joinToString("")
}