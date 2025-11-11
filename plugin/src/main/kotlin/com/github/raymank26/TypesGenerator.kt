package com.github.raymank26

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ClassName.Companion.bestGuess
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path

private val log = LoggerFactory.getLogger(TypesGenerator::class.java)

class TypesGenerator(
    private val specMetadata: SpecMetadata,
    private val basePackageName: String,
    private val baseGenPath: Path,
) {

    private val alreadyGenerated: MutableMap<String, TypeName> = mutableMapOf()

    fun generateTypes() {
        log.info("Starting type generations, specMetadata = {}", specMetadata)
        specMetadata.refs.forEach { (_, value: TypeDescriptor) ->
            generateTypeDescriptor(value, true)
        }
        specMetadata.operations.forEach { operationDescriptor ->
            generateTypeDescriptor(operationDescriptor.responseBody.type, true)
            if (operationDescriptor.requestBody != null) {
                generateTypeDescriptor(operationDescriptor.requestBody.type, true)
            }
        }

        val fileUploadType = TypeSpec.classBuilder(ClassName(basePackageName, "FileUpload"))
            .addModifiers(KModifier.DATA)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter(ParameterSpec.builder("name", String::class).build())
                    .addParameter(ParameterSpec.builder("file", File::class).build())
                    .addParameter(ParameterSpec.builder("contentType", String::class).build())
                    .build()
            )
            .addProperty(PropertySpec.builder("name", String::class).initializer("name").build())
            .addProperty(PropertySpec.builder("file", File::class).initializer("file").build())
            .addProperty(PropertySpec.builder("contentType", String::class).initializer("contentType").build())
            .build()

        FileSpec.builder(ClassName(basePackageName, "FileUpload"))
            .addType(fileUploadType)
            .build()
            .writeTo(baseGenPath)

        // Generate CleanupHandler - use fun interface to avoid kotlin.Unit import K2 compiler bug
        val cleanupTaskInterface = TypeSpec.funInterfaceBuilder("CleanupTask")
            .addFunction(
                FunSpec.builder("run")
                    .addModifiers(KModifier.ABSTRACT)
                    .build()
            )
            .build()

        val cleanupHandlerType = TypeSpec.classBuilder(ClassName(basePackageName, "CleanupHandler"))
            .addType(cleanupTaskInterface)
            .addProperty(
                PropertySpec.builder(
                    "resources",
                    ClassName("kotlin.collections", "MutableList")
                        .parameterizedBy(ClassName(basePackageName, "CleanupHandler").nestedClass("CleanupTask"))
                ).initializer(codeBlock = buildCodeBlock {
                    add("mutableListOf()")
                }).build()
            )
            .addFunction(
                FunSpec.builder("add")
                    .addParameter("runnable", ClassName(basePackageName, "CleanupHandler").nestedClass("CleanupTask"))
                    .addCode(buildCodeBlock {
                        add("resources.add(runnable)")
                    })
                    .build()
            )
            .addFunction(
                FunSpec.builder("cleanup")
                    .addCode(buildCodeBlock {
                        add("resources.forEach { it.run() }")
                    })
                    .build()
            )
            .build()

        FileSpec.builder(ClassName(basePackageName, "CleanupHandler"))
            .addType(cleanupHandlerType)
            .build()
            .writeTo(baseGenPath)
    }

    private fun generateTypeDescriptor(
        value: TypeDescriptor,
        required: Boolean,
    ): TypeName {
        val basicType = when (value) {
            is TypeDescriptor.Array -> {
                val innerTypeName = generateTypeDescriptor(value.itemDescriptor, true)
                val name = value.clsName
                val listType = bestGuess("kotlin.collections.List")
                    .parameterizedBy(innerTypeName)
                    .copy(nullable = !required)
                if (name == null) {
                    return listType
                }

                alreadyGenerated[name]?.let {
                    return it
                }
                val clsBuilder = TypeSpec.classBuilder(name)
                    .addModifiers(KModifier.DATA)
                clsBuilder.primaryConstructor(
                    FunSpec.constructorBuilder()
                        .addParameter(ParameterSpec(name.decapitalized(), listType))
                        .build()
                )
                clsBuilder.addProperty(
                    PropertySpec
                        .builder(name.decapitalized(), listType)
                        .initializer(name.decapitalized())
                        .build()
                )
                val typeSpec = clsBuilder.build()
                FileSpec.builder(basePackageName, value.clsName)
                    .addType(typeSpec)
                    .build()
                    .writeTo(baseGenPath)
                val typeName = bestGuess("$basePackageName.$name")
                alreadyGenerated[value.clsName] = typeName
                typeName
            }

            is TypeDescriptor.RefType -> bestGuess(basePackageName + "." + value.name.split("/").last())
            is TypeDescriptor.Object -> {
                val name = value.clsName!!
                alreadyGenerated[name]?.let {
                    return it
                }
                val clsBuilder = TypeSpec.classBuilder(name)
                    .addModifiers(KModifier.DATA)

                clsBuilder.addDataProperties(value.properties)
                val typeSpec = clsBuilder.build()
                FileSpec.builder(basePackageName, value.clsName)
                    .addType(typeSpec)
                    .build()
                    .writeTo(baseGenPath)
                val typeName = bestGuess("$basePackageName.$name")
                alreadyGenerated[value.clsName] = typeName
                typeName
            }

            is TypeDescriptor.OneOf -> {
                val name = value.clsName
                val responseTypeBuilder = TypeSpec.interfaceBuilder(name)
                    .addModifiers(KModifier.SEALED)
                val typeName = ClassName(basePackageName, name)

                value.typeDescriptors.forEach { (clsName: String, descriptions: List<TypeDescriptor>) ->

                    val constructorBuilder = FunSpec.constructorBuilder()
                    val properties = mutableListOf<PropertySpec>()

                    descriptions.forEach { description ->
                        val subType = generateTypeDescriptor(description, true)
                        val simpleName = when (subType) {
                            is ParameterizedTypeName -> subType.rawType.simpleName
                            is ClassName -> subType.simpleName
                            else -> error("Unsupported type of subType = $subType")
                        }

                        constructorBuilder.addParameter(
                            ParameterSpec
                                .builder(simpleName.decapitalized(), subType)
                                .build()
                        )
                        properties.add(
                            PropertySpec.builder(simpleName.decapitalized(), subType)
                                .initializer(simpleName.decapitalized())
                                .build()
                        )
                    }

                    val subTypeSpec = if (properties.isNotEmpty()) {
                        TypeSpec.classBuilder(clsName)
                            .addSuperinterface(typeName)
                            .addModifiers(KModifier.DATA)
                            .primaryConstructor(constructorBuilder.build())
                            .addProperties(properties)
                            .build()
                    } else {
                        TypeSpec.objectBuilder(clsName)
                            .addSuperinterface(typeName)
                            .addModifiers(KModifier.DATA)
                            .build()
                    }

                    responseTypeBuilder.addType(subTypeSpec)
                }
                FileSpec.builder(basePackageName, value.clsName)
                    .addType(responseTypeBuilder.build())
                    .build()
                    .writeTo(baseGenPath)
                typeName
            }

            is TypeDescriptor.SingleValueType -> {
                val name = value.clsName
                alreadyGenerated[name]?.let {
                    return it
                }
                val clsBuilder = TypeSpec.classBuilder(name)
                    .addModifiers(KModifier.DATA)
                val type = generateTypeDescriptor(value.property, true)
                clsBuilder.primaryConstructor(
                    FunSpec.constructorBuilder()
                        .addParameter(
                            ParameterSpec.builder("content", type)
                                .build()
                        )
                        .build()
                )
                val jsonProperty = AnnotationSpec
                    .builder(ClassName("com.fasterxml.jackson.annotation", "JsonValue"))
                    .build()
                val jsonPropertyGetter = AnnotationSpec
                    .builder(ClassName("com.fasterxml.jackson.annotation", "JsonValue"))
                    .useSiteTarget(AnnotationSpec.UseSiteTarget.GET)
                    .build()

                clsBuilder.addProperty(
                    PropertySpec.builder(
                        name = "content",
                        type = type
                    )
                        .addAnnotation(jsonProperty)
                        .addAnnotation(jsonPropertyGetter)
                        .initializer("content")
                        .build()
                )
                val typeSpec = clsBuilder.build()
                FileSpec.builder(basePackageName, value.clsName)
                    .addType(typeSpec)
                    .build()
                    .writeTo(baseGenPath)
                val typeName = bestGuess("$basePackageName.$name")
                alreadyGenerated[value.clsName] = typeName
                typeName
            }
            TypeDescriptor.Int64Type -> Long::class.java.asTypeName()
            TypeDescriptor.IntType -> Int::class.java.asTypeName()
            TypeDescriptor.FloatType -> Float::class.java.asTypeName()
            TypeDescriptor.StringType -> ClassName("kotlin", "String")
            TypeDescriptor.BooleanType -> Boolean::class.java.asTypeName()
            TypeDescriptor.FileUploadType -> ClassName(basePackageName, "FileUpload")
            TypeDescriptor.FileType -> File::class.java.asTypeName()
        }
        return basicType.copy(nullable = !required)
    }

    private fun TypeSpec.Builder.addDataProperties(descriptors: List<TypePropertyDescriptor>) {
        val constructorProperties = descriptors
            .map {
                ParameterSpec(it.name.decapitalized(), generateTypeDescriptor(it.type, it.required))
            }.toMutableList()
        val additionalPropertiesType = ClassName("kotlin.collections", "Map").parameterizedBy(
            listOf(
                ClassName("kotlin", "String"),
                ClassName("kotlin", "Any").copy(nullable = true)
            )
        )
        if (descriptors.isEmpty()) {
            constructorProperties.add(
                ParameterSpec("additionalProperties", additionalPropertiesType)
            )
        }
        primaryConstructor(
            FunSpec.constructorBuilder()
                .addParameters(constructorProperties)
                .build()
        )

        val properties = descriptors.map { property ->
            val jsonProperty = AnnotationSpec
                .builder(ClassName("com.fasterxml.jackson.annotation", "JsonProperty"))
                .addMember("%S", property.name)
                .build()
            val jsonPropertyField = AnnotationSpec
                .builder(ClassName("com.fasterxml.jackson.annotation", "JsonProperty"))
                .addMember("%S", property.name)
                .useSiteTarget(AnnotationSpec.UseSiteTarget.FIELD)
                .build()
            val jsonPropertyGetter = AnnotationSpec
                .builder(ClassName("com.fasterxml.jackson.annotation", "JsonProperty"))
                .addMember("%S", property.name)
                .useSiteTarget(AnnotationSpec.UseSiteTarget.GET)
                .build()
            PropertySpec
                .builder(property.name.decapitalized(), generateTypeDescriptor(property.type, property.required))
                .initializer(property.name.decapitalized())
                .addAnnotation(jsonProperty)
                .addAnnotation(jsonPropertyField)
                .addAnnotation(jsonPropertyGetter)
                .build()
        }.toMutableList()
        if (properties.isEmpty()) {
            val jsonAnyGetter = AnnotationSpec
                .builder(ClassName("com.fasterxml.jackson.annotation", "JsonAnyGetter"))
                .useSiteTarget(AnnotationSpec.UseSiteTarget.GET)
                .build()
            val jsonAnySetter = AnnotationSpec
                .builder(ClassName("com.fasterxml.jackson.annotation", "JsonAnySetter"))
                .build()
            properties.add(
                PropertySpec
                    .builder("additionalProperties", additionalPropertiesType)
                    .initializer("additionalProperties")
                    .addAnnotation(jsonAnyGetter)
                    .addAnnotation(jsonAnySetter)
                    .build()

            )
        }
        addProperties(properties)
    }
}