package com.ameme.android.data.transport.channel

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class VerifiedPairingBootstrapClientHello internal constructor(
    internal val document: CanonicalJsonObject,
    val bootstrapId: String,
    val pairingId: String,
    val clientNonce: String,
    val clientKeyThumbprint: String,
    clientPublicKeyX963: ByteArray,
) : AutoCloseable {
    private val publicKey = clientPublicKeyX963.copyOf()

    fun clientPublicKeyCopy(): ByteArray = publicKey.copyOf()

    override fun close() = publicKey.fill(0)

    override fun toString(): String =
        "VerifiedPairingBootstrapClientHello(binding=<redacted>, key=<redacted>)"
}

class VerifiedPairingBootstrapServerHello internal constructor(
    val bootstrapId: String,
    val pairingId: String,
    val clientNonce: String,
    val clientKeyThumbprint: String,
    val serverNonce: String,
    val credentialId: String,
    credentialSecret: ByteArray,
    val credentialExpiresAt: Instant,
) : AutoCloseable {
    private val secret = credentialSecret.copyOf()

    fun credentialSecretCopy(): ByteArray = secret.copyOf()

    override fun close() = secret.fill(0)

    override fun toString(): String =
        "VerifiedPairingBootstrapServerHello(binding=<redacted>, credential=<redacted>)"
}

/**
 * Frozen, content-free bootstrap protocol for turning a short-lived QR bearer into a
 * device-key-bound long-lived channel credential.
 *
 * The bootstrap runs inside the existing TLS 1.3 certificate-pinned connection. Its secret is
 * used only for this exchange and never as an application-channel credential.
 */
object AgentPairingBootstrapV2Codec {
    const val PROTOCOL = "ameme.agent-pairing-bootstrap.v2"
    const val KEY_ALGORITHM = "p256-sha256"
    const val MAX_LINE_BYTES = 16_384

    private val identifierPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    private val noncePattern = Regex("nonce_[0-9a-f]{64}")
    private val digestPattern = Regex("sha256_[0-9a-f]{64}")
    private val proofPattern = Regex("hmac_[0-9a-f]{64}")
    private val clientHelloKeys = setOf(
        "bootstrap_protocol",
        "message_type",
        "bootstrap_id",
        "pairing_id",
        "expected_device_id",
        "tls_certificate_sha256",
        "client_key_algorithm",
        "client_public_key_b64",
        "client_key_thumbprint",
        "client_nonce",
        "sequence",
        "possession_signature_b64",
        "proof",
    )
    private val serverHelloKeys = setOf(
        "bootstrap_protocol",
        "message_type",
        "bootstrap_id",
        "pairing_id",
        "device_id",
        "tls_certificate_sha256",
        "client_key_thumbprint",
        "client_nonce",
        "server_nonce",
        "credential_id",
        "credential_secret",
        "credential_expires_at_ms",
        "sequence",
        "proof",
    )

    fun isClientHello(line: ByteArray): Boolean = runCatching {
        val document = parseObject(line)
        document.string("bootstrap_protocol") == PROTOCOL &&
            document.string("message_type") == "bootstrap_client_hello"
    }.getOrDefault(false)

    fun clientPossessionPayload(
        pairing: AndroidLocalNodePairingMaterial,
        bootstrapId: String,
        clientPublicKeyX963: ByteArray,
        clientNonce: String,
    ): ByteArray {
        val core = clientCore(
            pairing = pairing,
            bootstrapId = bootstrapId,
            clientPublicKeyX963 = clientPublicKeyX963,
            clientNonce = clientNonce,
        )
        return "client-key-possession\u0000".encodeToByteArray() +
            StrictCanonicalJson.canonicalBytes(core)
    }

    fun buildClientHello(
        pairing: AndroidLocalNodePairingMaterial,
        bootstrapId: String,
        bootstrapSecret: ByteArray,
        clientPublicKeyX963: ByteArray,
        clientNonce: String,
        possessionSignatureDer: ByteArray,
    ): ByteArray {
        requireSecret(bootstrapSecret)
        validateSignature(possessionSignatureDer)
        val core = clientCore(
            pairing = pairing,
            bootstrapId = bootstrapId,
            clientPublicKeyX963 = clientPublicKeyX963,
            clientNonce = clientNonce,
        )
        val signed = CanonicalJsonObject(
            core.values + (
                "possession_signature_b64" to StrictCanonicalJson.string(
                    Base64.getUrlEncoder().withoutPadding().encodeToString(possessionSignatureDer),
                )
            ),
        )
        return canonicalWithProof(signed, bootstrapSecret, "bootstrap-client-hello")
    }

    fun verifyClientHello(
        line: ByteArray,
        pairing: AndroidLocalNodePairingMaterial,
        expectedBootstrapId: String,
        bootstrapSecret: ByteArray,
    ): VerifiedPairingBootstrapClientHello {
        requireSecret(bootstrapSecret)
        val document = parseObject(line)
        exact(document, clientHelloKeys)
        if (
            document.string("bootstrap_protocol") != PROTOCOL ||
            document.string("message_type") != "bootstrap_client_hello" ||
            document.string("bootstrap_id") != identifier(expectedBootstrapId) ||
            document.string("pairing_id") != pairing.pairingId ||
            document.string("expected_device_id") != pairing.expectedDeviceId ||
            document.string("tls_certificate_sha256") != pairing.tlsCertificateSha256 ||
            document.string("client_key_algorithm") != KEY_ALGORITHM ||
            document.long("sequence") != 0L
        ) {
            fail(AgentLocalNodeChannelErrorCode.CHANNEL_BINDING_FAILED)
        }
        val clientNonce = nonce(document.string("client_nonce"))
        val publicKey = decodeBase64Url(
            document.string("client_public_key_b64"),
            expectedSize = P256_X963_PUBLIC_KEY_BYTES,
        )
        if (publicKey.firstOrNull() != 0x04.toByte()) {
            publicKey.fill(0)
            invalid()
        }
        val thumbprint = digest(document.string("client_key_thumbprint"))
        val expectedThumbprint = sha256(publicKey)
        if (!MessageDigest.isEqual(thumbprint.encodeToByteArray(), expectedThumbprint.encodeToByteArray())) {
            publicKey.fill(0)
            fail(AgentLocalNodeChannelErrorCode.CHANNEL_BINDING_FAILED)
        }
        verifyProof(document, bootstrapSecret, "bootstrap-client-hello")
        val signature = decodeBase64Url(
            document.string("possession_signature_b64"),
            minimumSize = 64,
            maximumSize = 80,
        )
        val unsigned = CanonicalJsonObject(
            document.values - "proof" - "possession_signature_b64",
        )
        val possession = "client-key-possession\u0000".encodeToByteArray() +
            StrictCanonicalJson.canonicalBytes(unsigned)
        try {
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(p256PublicKey(publicKey))
            verifier.update(possession)
            if (!verifier.verify(signature)) {
                fail(AgentLocalNodeChannelErrorCode.CHANNEL_AUTH_FAILED)
            }
        } finally {
            signature.fill(0)
            possession.fill(0)
        }
        return VerifiedPairingBootstrapClientHello(
            document = document,
            bootstrapId = expectedBootstrapId,
            pairingId = pairing.pairingId,
            clientNonce = clientNonce,
            clientKeyThumbprint = thumbprint,
            clientPublicKeyX963 = publicKey,
        ).also { publicKey.fill(0) }
    }

    fun buildServerHello(
        clientHello: VerifiedPairingBootstrapClientHello,
        pairing: AndroidLocalNodePairingMaterial,
        bootstrapSecret: ByteArray,
        serverNonce: String,
        credentialId: String,
        credentialSecret: String,
        credentialExpiresAt: Instant,
    ): ByteArray {
        requireSecret(bootstrapSecret)
        decodeBase64Url(credentialSecret, expectedSize = 32).fill(0)
        require(credentialExpiresAt.toEpochMilli() > 0)
        val document = StrictCanonicalJson.obj(
            "bootstrap_protocol" to StrictCanonicalJson.string(PROTOCOL),
            "message_type" to StrictCanonicalJson.string("bootstrap_server_hello"),
            "bootstrap_id" to StrictCanonicalJson.string(identifier(clientHello.bootstrapId)),
            "pairing_id" to StrictCanonicalJson.string(pairing.pairingId),
            "device_id" to StrictCanonicalJson.string(pairing.expectedDeviceId),
            "tls_certificate_sha256" to StrictCanonicalJson.string(pairing.tlsCertificateSha256),
            "client_key_thumbprint" to StrictCanonicalJson.string(
                digest(clientHello.clientKeyThumbprint),
            ),
            "client_nonce" to StrictCanonicalJson.string(nonce(clientHello.clientNonce)),
            "server_nonce" to StrictCanonicalJson.string(nonce(serverNonce)),
            "credential_id" to StrictCanonicalJson.string(identifier(credentialId)),
            "credential_secret" to StrictCanonicalJson.string(credentialSecret),
            "credential_expires_at_ms" to StrictCanonicalJson.string(
                credentialExpiresAt.toEpochMilli().toString(),
            ),
            "sequence" to StrictCanonicalJson.integer(0),
        )
        return canonicalWithProof(document, bootstrapSecret, "bootstrap-server-hello")
    }

    fun verifyServerHello(
        line: ByteArray,
        expectedClientHello: VerifiedPairingBootstrapClientHello,
        pairing: AndroidLocalNodePairingMaterial,
        bootstrapSecret: ByteArray,
    ): VerifiedPairingBootstrapServerHello {
        requireSecret(bootstrapSecret)
        val document = parseObject(line)
        exact(document, serverHelloKeys)
        if (
            document.string("bootstrap_protocol") != PROTOCOL ||
            document.string("message_type") != "bootstrap_server_hello" ||
            document.string("bootstrap_id") != expectedClientHello.bootstrapId ||
            document.string("pairing_id") != pairing.pairingId ||
            document.string("device_id") != pairing.expectedDeviceId ||
            document.string("tls_certificate_sha256") != pairing.tlsCertificateSha256 ||
            document.string("client_key_thumbprint") != expectedClientHello.clientKeyThumbprint ||
            document.string("client_nonce") != expectedClientHello.clientNonce ||
            document.long("sequence") != 0L
        ) {
            fail(AgentLocalNodeChannelErrorCode.CHANNEL_BINDING_FAILED)
        }
        val serverNonce = nonce(document.string("server_nonce"))
        val credentialId = identifier(document.string("credential_id"))
        val credentialSecretText = document.string("credential_secret")
        val decodedSecret = decodeBase64Url(credentialSecretText, expectedSize = 32)
        val credentialExpiryText = document.string("credential_expires_at_ms")
        if (credentialExpiryText.isEmpty() || credentialExpiryText.any { it !in '0'..'9' }) {
            decodedSecret.fill(0)
            invalid()
        }
        val credentialExpiresAt = runCatching {
            Instant.ofEpochMilli(credentialExpiryText.toLong())
        }.getOrElse {
            decodedSecret.fill(0)
            invalid()
        }
        verifyProof(document, bootstrapSecret, "bootstrap-server-hello")
        return VerifiedPairingBootstrapServerHello(
            bootstrapId = expectedClientHello.bootstrapId,
            pairingId = pairing.pairingId,
            clientNonce = expectedClientHello.clientNonce,
            clientKeyThumbprint = expectedClientHello.clientKeyThumbprint,
            serverNonce = serverNonce,
            credentialId = credentialId,
            credentialSecret = credentialSecretText.encodeToByteArray(),
            credentialExpiresAt = credentialExpiresAt,
        ).also { decodedSecret.fill(0) }
    }

    private fun clientCore(
        pairing: AndroidLocalNodePairingMaterial,
        bootstrapId: String,
        clientPublicKeyX963: ByteArray,
        clientNonce: String,
    ): CanonicalJsonObject {
        AndroidLocalNodeChannelCodec.validatePairingMaterial(pairing)
        identifier(bootstrapId)
        if (
            clientPublicKeyX963.size != P256_X963_PUBLIC_KEY_BYTES ||
            clientPublicKeyX963.firstOrNull() != 0x04.toByte()
        ) {
            invalid()
        }
        return StrictCanonicalJson.obj(
            "bootstrap_protocol" to StrictCanonicalJson.string(PROTOCOL),
            "message_type" to StrictCanonicalJson.string("bootstrap_client_hello"),
            "bootstrap_id" to StrictCanonicalJson.string(bootstrapId),
            "pairing_id" to StrictCanonicalJson.string(pairing.pairingId),
            "expected_device_id" to StrictCanonicalJson.string(pairing.expectedDeviceId),
            "tls_certificate_sha256" to StrictCanonicalJson.string(pairing.tlsCertificateSha256),
            "client_key_algorithm" to StrictCanonicalJson.string(KEY_ALGORITHM),
            "client_public_key_b64" to StrictCanonicalJson.string(
                Base64.getUrlEncoder().withoutPadding().encodeToString(clientPublicKeyX963),
            ),
            "client_key_thumbprint" to StrictCanonicalJson.string(sha256(clientPublicKeyX963)),
            "client_nonce" to StrictCanonicalJson.string(nonce(clientNonce)),
            "sequence" to StrictCanonicalJson.integer(0),
        )
    }

    private fun parseObject(line: ByteArray): CanonicalJsonObject = try {
        StrictCanonicalJson.parseLine(line, MAX_LINE_BYTES) as? CanonicalJsonObject ?: invalid()
    } catch (violation: AgentLocalNodeChannelViolation) {
        throw violation
    } catch (_: Exception) {
        fail(AgentLocalNodeChannelErrorCode.INVALID_CHANNEL_MESSAGE)
    }

    private fun exact(document: CanonicalJsonObject, keys: Set<String>) {
        if (document.values.keys != keys) invalid()
    }

    private fun canonicalWithProof(
        document: CanonicalJsonObject,
        secret: ByteArray,
        label: String,
    ): ByteArray {
        val proof = proof(secret, label, document)
        return StrictCanonicalJson.canonicalBytes(
            CanonicalJsonObject(document.values + ("proof" to StrictCanonicalJson.string(proof))),
        )
    }

    private fun verifyProof(document: CanonicalJsonObject, secret: ByteArray, label: String) {
        val supplied = (document.values["proof"] as? CanonicalJsonString)?.value
            ?: fail(AgentLocalNodeChannelErrorCode.CHANNEL_AUTH_FAILED)
        if (!proofPattern.matches(supplied)) {
            fail(AgentLocalNodeChannelErrorCode.CHANNEL_AUTH_FAILED)
        }
        val expected = proof(secret, label, CanonicalJsonObject(document.values - "proof"))
        if (!MessageDigest.isEqual(supplied.encodeToByteArray(), expected.encodeToByteArray())) {
            fail(AgentLocalNodeChannelErrorCode.CHANNEL_AUTH_FAILED)
        }
    }

    private fun proof(secret: ByteArray, label: String, document: CanonicalJsonObject): String {
        val message = label.encodeToByteArray() + byteArrayOf(0) +
            StrictCanonicalJson.canonicalBytes(document)
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret, "HmacSHA256"))
            "hmac_" + mac.doFinal(message).toHex()
        } finally {
            message.fill(0)
        }
    }

    private fun p256PublicKey(x963: ByteArray): java.security.PublicKey {
        if (x963.size != P256_X963_PUBLIC_KEY_BYTES || x963[0] != 0x04.toByte()) invalid()
        val parameters = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
        val x = BigInteger(1, x963.copyOfRange(1, 33))
        val y = BigInteger(1, x963.copyOfRange(33, 65))
        return KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(ECPoint(x, y), parameters),
        )
    }

    private fun decodeBase64Url(
        value: String,
        expectedSize: Int? = null,
        minimumSize: Int = expectedSize ?: 1,
        maximumSize: Int = expectedSize ?: MAX_LINE_BYTES,
    ): ByteArray {
        if (
            value.isEmpty() ||
            value.length % 4 == 1 ||
            value.any { !isBase64UrlCharacter(it) }
        ) {
            invalid()
        }
        val decoded = try {
            Base64.getUrlDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            invalid()
        }
        if (
            (expectedSize != null && decoded.size != expectedSize) ||
            decoded.size !in minimumSize..maximumSize
        ) {
            decoded.fill(0)
            invalid()
        }
        return decoded
    }

    private fun validateSignature(signature: ByteArray) {
        if (signature.size !in 64..80) invalid()
    }

    private fun requireSecret(secret: ByteArray) {
        if (secret.size != 32) fail(AgentLocalNodeChannelErrorCode.CHANNEL_AUTH_FAILED)
    }

    private fun identifier(value: String): String =
        value.takeIf(identifierPattern::matches) ?: invalid()

    private fun nonce(value: String): String =
        value.takeIf(noncePattern::matches) ?: invalid()

    private fun digest(value: String): String =
        value.takeIf(digestPattern::matches) ?: invalid()

    private fun sha256(value: ByteArray): String =
        "sha256_" + MessageDigest.getInstance("SHA-256").digest(value).toHex()

    private fun CanonicalJsonObject.string(key: String): String =
        (values[key] as? CanonicalJsonString)?.value ?: invalid()

    private fun CanonicalJsonObject.long(key: String): Long =
        (values[key] as? CanonicalJsonInteger)?.value?.longValueExact() ?: invalid()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun isBase64UrlCharacter(value: Char): Boolean =
        value in 'A'..'Z' || value in 'a'..'z' || value in '0'..'9' || value == '-' || value == '_'

    private fun invalid(): Nothing =
        fail(AgentLocalNodeChannelErrorCode.INVALID_CHANNEL_MESSAGE)

    private fun fail(code: AgentLocalNodeChannelErrorCode): Nothing =
        throw AgentLocalNodeChannelViolation(code)

    private const val P256_X963_PUBLIC_KEY_BYTES = 65
}
