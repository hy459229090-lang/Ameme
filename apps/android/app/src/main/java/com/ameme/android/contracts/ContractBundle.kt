package com.ameme.android.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class ContractBundle(
    @SerialName("example_version") val exampleVersion: Int,
    val description: String,
    val objects: List<ContractObject>,
)

@Serializable
data class ContractObject(
    @SerialName("object_type") val objectType: String,
    @SerialName("object") val payload: JsonObject,
)

object ContractCodec {
    val json: Json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
    }

    fun decodeBundle(text: String): ContractBundle = json.decodeFromString(text)

    fun encodeBundle(bundle: ContractBundle): String = json.encodeToString(bundle)
}
