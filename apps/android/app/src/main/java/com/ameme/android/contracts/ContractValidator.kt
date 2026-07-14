package com.ameme.android.contracts

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ValidationIssue(
    val path: String,
    val message: String,
)

/**
 * Offline validator for the JSON Schema keywords used by the repository MVP contract.
 * It deliberately rejects unknown object properties when the source schema does.
 */
class ContractValidator(schemaText: String) {
    private val schema = ContractCodec.json.parseToJsonElement(schemaText).jsonObject
    private val definitions = schema.getValue("\$defs").jsonObject

    fun validate(bundle: ContractBundle): List<ValidationIssue> = bundle.objects.flatMapIndexed { index, item ->
        validate(item, "\$.objects[$index]")
    }

    fun validate(item: ContractObject, path: String = "\$"): List<ValidationIssue> {
        val definitionName = definitionByObjectType[item.objectType]
            ?: return listOf(ValidationIssue("$path.object_type", "unsupported object type ${item.objectType}"))
        return validateNode(item.payload, definitions.getValue(definitionName).jsonObject, "$path.object")
    }

    private fun validateNode(
        value: JsonElement,
        node: JsonObject,
        path: String,
    ): List<ValidationIssue> {
        node["\$ref"]?.jsonPrimitive?.contentOrNull?.let { ref ->
            val name = ref.removePrefix("#/\$defs/")
            val target = definitions[name]
                ?: return listOf(ValidationIssue(path, "unknown schema reference $ref"))
            return validateNode(value, target.jsonObject, path)
        }

        val issues = mutableListOf<ValidationIssue>()
        node["const"]?.let { expected ->
            if (value != expected) issues += ValidationIssue(path, "expected const $expected")
        }
        node["enum"]?.jsonArray?.let { allowed ->
            if (value !in allowed) issues += ValidationIssue(path, "value is outside allowed enum")
        }

        when (node["type"]?.jsonPrimitive?.contentOrNull) {
            "object" -> validateObject(value, node, path, issues)
            "array" -> validateArray(value, node, path, issues)
            "string" -> validateString(value, node, path, issues)
            "integer" -> if (value !is JsonPrimitive || value.intOrNull == null) {
                issues += ValidationIssue(path, "expected integer")
            }
            "number" -> if (value !is JsonPrimitive || value.doubleOrNull == null) {
                issues += ValidationIssue(path, "expected number")
            } else {
                val number = value.doubleOrNull ?: 0.0
                val minimum = node["minimum"]?.jsonPrimitive?.doubleOrNull
                val maximum = node["maximum"]?.jsonPrimitive?.doubleOrNull
                if (minimum != null && number < minimum) issues += ValidationIssue(path, "below minimum $minimum")
                if (maximum != null && number > maximum) issues += ValidationIssue(path, "above maximum $maximum")
            }
            "boolean" -> if (value !is JsonPrimitive || value.booleanOrNull == null) {
                issues += ValidationIssue(path, "expected boolean")
            }
        }
        return issues
    }

    private fun validateObject(
        value: JsonElement,
        node: JsonObject,
        path: String,
        issues: MutableList<ValidationIssue>,
    ) {
        if (value !is JsonObject) {
            issues += ValidationIssue(path, "expected object")
            return
        }
        val required = node["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        required.filterNot(value::containsKey).forEach { key ->
            issues += ValidationIssue("$path.$key", "missing required field $key")
        }
        val properties = node["properties"]?.jsonObject.orEmpty()
        value.forEach { (key, child) ->
            val childSchema = properties[key]
            if (childSchema != null) {
                issues += validateNode(child, childSchema.jsonObject, "$path.$key")
            } else if (node["additionalProperties"]?.jsonPrimitive?.booleanOrNull == false) {
                issues += ValidationIssue("$path.$key", "unknown properties are not allowed")
            }
        }
        node["minProperties"]?.jsonPrimitive?.intOrNull?.let { minimum ->
            if (value.size < minimum) issues += ValidationIssue(path, "fewer than $minimum properties")
        }
    }

    private fun validateArray(
        value: JsonElement,
        node: JsonObject,
        path: String,
        issues: MutableList<ValidationIssue>,
    ) {
        if (value !is JsonArray) {
            issues += ValidationIssue(path, "expected array")
            return
        }
        node["minItems"]?.jsonPrimitive?.intOrNull?.let { minimum ->
            if (value.size < minimum) issues += ValidationIssue(path, "fewer than $minimum items")
        }
        if (node["uniqueItems"]?.jsonPrimitive?.booleanOrNull == true && value.distinct().size != value.size) {
            issues += ValidationIssue(path, "items must be unique")
        }
        node["items"]?.jsonObject?.let { itemSchema ->
            value.forEachIndexed { index, child ->
                issues += validateNode(child, itemSchema, "$path[$index]")
            }
        }
    }

    private fun validateString(
        value: JsonElement,
        node: JsonObject,
        path: String,
        issues: MutableList<ValidationIssue>,
    ) {
        if (value !is JsonPrimitive || !value.isString) {
            issues += ValidationIssue(path, "expected string")
            return
        }
        val text = value.content
        node["minLength"]?.jsonPrimitive?.intOrNull?.let { minimum ->
            if (text.length < minimum) issues += ValidationIssue(path, "shorter than $minimum characters")
        }
        node["maxLength"]?.jsonPrimitive?.intOrNull?.let { maximum ->
            if (text.length > maximum) issues += ValidationIssue(path, "longer than $maximum characters")
        }
        node["pattern"]?.jsonPrimitive?.contentOrNull?.let { pattern ->
            if (!Regex(pattern).matches(text)) issues += ValidationIssue(path, "does not match required pattern")
        }
    }

    private companion object {
        val definitionByObjectType = mapOf(
            "acquisition_contract" to "AcquisitionContract",
            "source_object" to "SourceObjectEnvelope",
            "observation" to "Observation",
            "user_addendum" to "UserAddendum",
            "event_candidate" to "EventCandidate",
            "event" to "Event",
            "event_revision" to "EventRevision",
            "episode" to "Episode",
            "episode_revision" to "EpisodeRevision",
            "artifact" to "Artifact",
            "day_ledger" to "DayLedger",
            "summary" to "Summary",
            "feedback_event" to "FeedbackEvent",
            "access_grant" to "AccessGrant",
            "recall_query" to "RecallQuery",
            "recall_page" to "RecallPage",
            "context_pack" to "ContextPack",
            "lineage_edge" to "LineageEdge",
            "sync_envelope" to "SyncEnvelope",
            "deletion_job" to "DeletionJob",
            "export_job" to "ExportJob",
        )
    }
}
