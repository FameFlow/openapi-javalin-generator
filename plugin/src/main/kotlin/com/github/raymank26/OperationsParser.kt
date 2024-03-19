package com.github.raymank26

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.responses.ApiResponses
import io.swagger.v3.oas.models.security.SecurityScheme
import org.gradle.configurationcache.extensions.capitalized
import io.swagger.v3.oas.models.security.SecurityScheme.Type as SecuritySchemeType

class OperationsParser(private val spec: OpenAPI) {

    private val refsBuilder = RefsBuilder()

    fun parseSpec(): SpecMetadata {
        val operations = spec.paths.flatMap { path ->
            val pathOperations = mutableListOf<OperationDescriptor>()
            val securitySchemes = parseSecuritySchemes(spec.components.securitySchemes ?: emptyMap())
            if (path.value.get != null) {
                pathOperations.add(parseOperation(path.key, path.value.get, "get", securitySchemes))
            }
            if (path.value.post != null) {
                pathOperations.add(parseOperation(path.key, path.value.post, "post", securitySchemes))
            }
            if (path.value.patch != null) {
                pathOperations.add(parseOperation(path.key, path.value.patch, "patch", securitySchemes))
            }
            if (path.value.put != null) {
                pathOperations.add(parseOperation(path.key, path.value.put, "put", securitySchemes))
            }
            if (path.value.delete != null) {
                pathOperations.add(parseOperation(path.key, path.value.delete, "delete", securitySchemes))
            }
            pathOperations
        }
        val namePrefix = spec.info.extensions?.get("x-name")?.toString() ?: ""
        return SpecMetadata(namePrefix, operations, refsBuilder.build())
    }

    private fun parseSecuritySchemes(securitySchemes: Map<String, SecurityScheme>):
            Map<String, com.github.raymank26.SecurityScheme> {

        return securitySchemes.mapValues { entry ->
            when {
                entry.value.type == SecuritySchemeType.HTTP && entry.value.scheme == "bearer" ->
                    com.github.raymank26.SecurityScheme.BearerToken

                entry.value.type == SecuritySchemeType.APIKEY && entry.value.`in` == SecurityScheme.In.HEADER ->
                    com.github.raymank26.SecurityScheme.SecurityHeader(entry.value.name)

                else -> error("Unsupported security scheme")
            }
        }
    }

    private fun parseOperation(
        path: String,
        operation: Operation,
        method: String,
        securitySchemes: Map<String, com.github.raymank26.SecurityScheme>,
    ): OperationDescriptor {

        val authScheme = operation.security?.firstOrNull()
            ?: spec.security?.firstOrNull()
        val securitySchemeName = authScheme?.keys?.first()

        val securityScheme = securitySchemeName
            ?.let {
                securitySchemes[securitySchemeName]
                    ?: error("Security scheme is not found by key = $securitySchemeName")
            }
        return OperationDescriptor(
            path = path,
            method = method,
            summary = operation.summary,
            operationId = operation.operationId,
            paramDescriptors = parseParameters(operation.parameters ?: emptyList(), securityScheme),
            requestBody = parseRequestBody(operation),
            responseBody = parseResponses(operation, operation.responses),
        )
    }

    private fun parseRequestBody(operation: Operation): RequestBody? {
        val requestBody = operation.requestBody ?: return null
        val required = requestBody.required ?: false
        val definitions = requestBody.content.mapNotNull { (mediaType, definition) ->
            val ref = definition.schema.`$ref`
            if (definition.schema.`$ref` == null) {
                // TODO: Need to support non ref types"
                return@mapNotNull null
            }
            val clsName = definition.schema.`$ref`.split("/").last()
            val schema = spec.components.schemas[clsName]!!
            val optionName = when (mediaType) {
                "application/json" -> RequestBodyMediaType.Json
                "application/xml" -> RequestBodyMediaType.Xml
                "application/x-www-form-urlencoded" -> RequestBodyMediaType.FormData
                "multipart/form-data" -> RequestBodyMediaType.MultipartFormData
                else -> error("Not implemented")
            }
            optionName to parseTypeDescriptor(ref, clsName, schema)
        }.toMap()
        val clsName = operation.operationId.capitalized() + "Request"
        val type = TypeDescriptor.OneOf(
            clsName, definitions
                .map { entry -> entry.key.clsName to listOf(entry.value) }.toMap()
        )

        return RequestBody(clsName, definitions, type, required)
    }

    private fun parseResponses(operation: Operation, responses: ApiResponses): ResponseBody {

        val clsName = operation.operationId.capitalized() + "Response"
        val codeToSealedOption = mutableMapOf<String, ResponseBodySealedOption>()
        val clsNameToTypeDescriptor = mutableMapOf<String, List<TypeDescriptor>>()

        responses.forEach { (code, response) ->
            val responseCls = response.content?.get("application/json")
            val headers = response.headers?.map {
                ResponseHeader(getTypePropertyDescriptor(it.key.decapitalized(), it.value.schema, it.value.required))
            } ?: emptyList()

            val descriptor = if (responseCls != null) {
                val ref = responseCls.schema.`$ref`
                val optionClsName = ref.split("/").last()
                val schema = spec.components.schemas[optionClsName]!!
                parseTypeDescriptor(ref, optionClsName, schema)
            } else null
            val headersDescriptorProvider = { optionClsName: String ->
                if (headers.isNotEmpty()) {
                    TypeDescriptor.Object(
                        clsName + optionClsName + "Headers",
                        headers.map { it.typePropertyDescriptor })
                } else null
            }

            val option = createResponseOption(code, descriptor, headersDescriptorProvider)
            codeToSealedOption[code] = option
            clsNameToTypeDescriptor[option.clsName] = listOfNotNull(descriptor, option.headers)
        }
        return ResponseBody(codeToSealedOption, clsName, TypeDescriptor.OneOf(clsName, clsNameToTypeDescriptor), false)
    }

    private fun createResponseOption(
        code: String,
        descriptor: TypeDescriptor?,
        headersProvider: (String) -> TypeDescriptor.Object?
    ): ResponseBodySealedOption {
        return when (descriptor) {
            is TypeDescriptor.Object -> ResponseBodySealedOption.Parametrized(
                descriptor.clsName!!,
                headersProvider(descriptor.clsName)
            )

            is TypeDescriptor.Array -> ResponseBodySealedOption.Parametrized(
                descriptor.clsName!!,
                headersProvider(descriptor.clsName)
            )

            else -> {
                val clsName = when (code) {
                    "200" -> "Ok"
                    "201" -> "Created"
                    "404" -> "NotFound"
                    "302" -> "Redirect"
                    "422" -> "UnprocessableContent"
                    "402" -> "PaymentRequired"
                    else -> error("Cannot infer name from code = $code")
                }
                ResponseBodySealedOption.JustStatus(clsName, headersProvider(clsName))
            }
        }
    }

    private fun parseTypeDescriptor(
        ref: String?,
        clsName: String?,
        schema: Schema<Any>?
    ): TypeDescriptor {
        val result = when (schema?.type) {
            "array" -> {
                val itemRef = schema.items.`$ref`
                if (itemRef != null) {
                    val itemClsName = itemRef.split("/").last()
                    val itemSchema = spec.components.schemas[itemClsName]!!
                    refsBuilder.addRef(itemRef, parseTypeDescriptor(itemRef, itemClsName, itemSchema))
                    TypeDescriptor.Array(clsName, TypeDescriptor.RefType(itemRef))
                } else {
                    TypeDescriptor.Array(null, TypeDescriptor.StringType)
                }
            }

            "object" -> {
                val requiredProperties = schema.required?.toSet() ?: emptySet()
                val properties = (schema.properties ?: emptyMap()).map { (name, property) ->
                    getTypePropertyDescriptor(name, property, requiredProperties.contains(name))
                }
                TypeDescriptor.Object(clsName, properties)
            }

            null -> TypeDescriptor.Object(clsName, emptyList())
            else -> error("not supported type = " + schema.type)
        }
        if (ref != null) {
            refsBuilder.addRef(ref, result)
        }
        return result
    }

    private fun getTypePropertyDescriptor(
        name: String,
        property: Schema<Any>,
        required: Boolean?,
    ) = TypePropertyDescriptor(
        name = name,
        type = getPropertyType(property),
        format = property.format,
        required = required ?: false
    )

    private fun getPropertyType(property: Schema<*>): TypeDescriptor {
        if (property.`$ref` != null) {
            val clsName = property.`$ref`.split("/").last()
            val itemSchema = spec.components.schemas[clsName]!!
            parseTypeDescriptor(property.`$ref`, clsName, itemSchema)
            return TypeDescriptor.RefType(property.`$ref`)
        }
        return when (val type = property.type) {
            "integer" -> if (property.format == "int64") TypeDescriptor.Int64Type else TypeDescriptor.IntType
            "string" -> if (property.format == "binary") TypeDescriptor.FileUploadType else TypeDescriptor.StringType
            "number" -> TypeDescriptor.FloatType
            "boolean" -> TypeDescriptor.BooleanType
            "array" -> {
                TypeDescriptor.Array(null, getPropertyType(property.items))
            }
            "object" -> {
                parseTypeDescriptor(null, null, property.contentSchema)
            }
            else -> error("not supported type = $type")
        }
    }

    private fun parseParameters(
        parameters: List<Parameter>,
        securityScheme: com.github.raymank26.SecurityScheme?
    ): List<ParamDescriptor> {

        val baseParameters = parameters.map { parameter ->
            ParamDescriptor(
                name = parameter.name,
                place = parameter.`in`,
                typePropertyDescriptor = getTypePropertyDescriptor(
                    parameter.name,
                    parameter.schema,
                    parameter.required
                )
            )
        }
        val authParam = when (securityScheme) {
            com.github.raymank26.SecurityScheme.BearerToken -> securityHeaderParam("Authorization")
            is com.github.raymank26.SecurityScheme.SecurityHeader -> securityHeaderParam(securityScheme.headerName)
            null -> null
        }
        return if (authParam != null) {
            listOf(authParam) + baseParameters
        } else {
            baseParameters
        }
    }

    private fun securityHeaderParam(headerName: String) = ParamDescriptor(
        name = headerName,
        place = "header",
        typePropertyDescriptor = TypePropertyDescriptor(
            name = headerName,
            type = TypeDescriptor.StringType,
            format = null,
            required = true
        )
    )
}

data class SpecMetadata(
    val namePrefix: String,
    val operations: List<OperationDescriptor>,
    val refs: Map<String, TypeDescriptor>,
)