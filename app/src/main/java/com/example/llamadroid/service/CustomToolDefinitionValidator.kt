package com.example.llamadroid.service

import org.json.JSONArray
import org.json.JSONObject

data class CustomToolDefinitionValidation(val valid: Boolean, val message: String)

object CustomToolDefinitionValidator {
    private val namePattern = Regex("[a-z][a-z0-9_]{1,63}")
    private val placeholderPattern = Regex("\\{([A-Za-z][A-Za-z0-9_]*)}")

    fun validate(
        name: String,
        description: String,
        commandTemplate: String,
        parametersJson: String,
        requiredJson: String,
        exampleUsage: String
    ): CustomToolDefinitionValidation = runCatching {
        require(namePattern.matches(name.trim())) { "Name must use 2-64 lowercase letters, numbers, or underscores." }
        require(description.trim().length in 4..500) { "Description must be between 4 and 500 characters." }
        require(commandTemplate.isNotBlank()) { "An execution template is required." }
        require(exampleUsage.isNotBlank()) { "An example tool call is required." }
        val params = JSONObject(parametersJson)
        val parameterObject = params.optJSONObject("properties")
            ?.takeIf { params.optString("type").equals("object", ignoreCase = true) }
            ?: params
        val paramNames = parameterObject.keys().asSequence().toSet()
        require(paramNames.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}")) }) { "Parameter names are invalid." }
        val required = JSONArray(requiredJson)
        val requiredNames = (0 until required.length()).map(required::getString)
        require(requiredNames.distinct().size == requiredNames.size) { "Required parameter names must be unique." }
        require(requiredNames.all { it in paramNames }) { "Every required parameter must exist in the parameter schema." }
        val placeholders = placeholderPattern.findAll(commandTemplate).map { it.groupValues[1] }.toSet()
        require(placeholders.all { it in paramNames }) { "Every {placeholder} must exist in the parameter schema." }
        if (AgentRuntimeSupport.inferCustomToolExecutionMode(commandTemplate) == CustomToolExecutionMode.ARGV) {
            AgentRuntimeSupport.tokenizeArgvTemplate(commandTemplate, placeholders.associateWith { "test" })
        }
        CustomToolDefinitionValidation(true, "Definition is valid.")
    }.getOrElse { CustomToolDefinitionValidation(false, it.message ?: "Definition is invalid.") }

    /**
     * Keeps JSON Schema types, arrays, enums and nested constraints intact for providers that
     * support them. Legacy flat parameter maps continue through AgentTool.parameters.
     */
    fun canonicalSchemaJson(parametersJson: String, requiredJson: String): String? = runCatching {
        val schema = JSONObject(parametersJson)
        val properties = schema.optJSONObject("properties")
        if (!schema.optString("type").equals("object", ignoreCase = true) || properties == null) {
            return@runCatching null
        }
        val required = JSONArray(requiredJson)
        schema.put("required", required)
        schema.toString()
    }.getOrNull()
}
