package com.ameme.android.contracts

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractBundleTest {
    private val contractsDir: Path = locateContractsDirectory()
    private val schemaText = readUtf8(contractsDir.resolve("schemas/ameme-domain.schema.json"))
    private val bundleText = readUtf8(contractsDir.resolve("examples/synthetic-day.json"))

    @Test
    fun repositorySyntheticBundle_roundTripsWithoutSemanticLoss() {
        val originalJson = ContractCodec.json.parseToJsonElement(bundleText)
        val bundle = ContractCodec.decodeBundle(bundleText)
        val encodedJson = ContractCodec.json.parseToJsonElement(ContractCodec.encodeBundle(bundle))

        assertEquals(1, bundle.exampleVersion)
        assertEquals(21, bundle.objects.size)
        assertEquals(originalJson, encodedJson)
    }

    @Test
    fun repositorySyntheticBundle_validatesAgainstFrozenSchemaDefinitions() {
        val bundle = ContractCodec.decodeBundle(bundleText)
        val issues = ContractValidator(schemaText).validate(bundle)

        assertTrue("Unexpected validation issues: $issues", issues.isEmpty())
    }

    @Test
    fun repositoryNegativeMutations_areRejected() {
        val bundle = ContractCodec.decodeBundle(bundleText)
        val negative = ContractCodec.json.parseToJsonElement(
            readUtf8(contractsDir.resolve("examples/invalid-contracts.json")),
        ).jsonObject
        val validator = ContractValidator(schemaText)

        negative.getValue("cases").jsonArray.forEach { caseElement ->
            val case = caseElement.jsonObject
            val objectType = case.getValue("object_type").jsonPrimitive.content
            val source = bundle.objects.first { it.objectType == objectType }
            val path = case.getValue("path").jsonPrimitive.content
            val mutatedPayload = source.payload.toMutableMap().apply {
                when (case.getValue("operation").jsonPrimitive.content) {
                    "remove" -> remove(path)
                    "set" -> set(path, case.getValue("value"))
                    else -> error("Unsupported negative fixture operation")
                }
            }
            val issues = validator.validate(source.copy(payload = JsonObject(mutatedPayload)))

            assertTrue("Negative case ${case.getValue("name")} was accepted", issues.isNotEmpty())
        }
    }

    private fun locateContractsDirectory(): Path {
        val start = Path.of("").toAbsolutePath().normalize()
        return generateSequence(start) { it.parent }
            .map { it.resolve("packages/contracts") }
            .firstOrNull { Files.isDirectory(it) }
            ?: error("Could not find packages/contracts above $start")
    }

    private fun readUtf8(path: Path): String = String(Files.readAllBytes(path), Charsets.UTF_8)
}
