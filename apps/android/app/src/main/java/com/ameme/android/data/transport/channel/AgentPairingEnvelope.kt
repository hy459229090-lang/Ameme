package com.ameme.android.data.transport.channel

import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Canonical, short-lived QR/deep-link bootstrap payload.
 *
 * The bootstrap secret can only provision a separately generated channel credential; it is never
 * accepted by the application channel. The server persists a wrapped copy solely to enforce
 * expiry, atomic consumption, and same-device retry.
 */
object AgentPairingEnvelope {
    const val PREFIX = "ameme-pairing-v2:"
    const val MAX_PAYLOAD_CHARACTERS = 16_384
    private val maxLifetime = Duration.ofMinutes(10)
    private val maxPairingLifetime = Duration.ofDays(31)

    class Parsed(
        val pairing: AndroidLocalNodePairingMaterial,
        val bootstrapId: String,
        val bootstrapSecret: ByteArray,
        val expiresAt: Instant,
        val pairingExpiresAt: Instant,
    ) : AutoCloseable {
        override fun close() = bootstrapSecret.fill(0)
    }

    fun encode(
        pairing: AndroidLocalNodePairingMaterial,
        bootstrapId: String,
        bootstrapSecret: String,
        expiresAt: Instant,
        pairingExpiresAt: Instant,
    ): String {
        require(bootstrapId.matches(Regex("boot_[0-9a-f]{32}")))
        val secret = decodeSecret(bootstrapSecret)
        try {
            require(expiresAt.toEpochMilli() > 0)
            require(pairingExpiresAt >= expiresAt)
            val value = StrictCanonicalJson.obj(
                "bootstrap_id" to StrictCanonicalJson.string(bootstrapId),
                "bootstrap_secret" to StrictCanonicalJson.string(bootstrapSecret),
                "envelope_version" to StrictCanonicalJson.integer(2),
                "expires_at_ms" to StrictCanonicalJson.string(expiresAt.toEpochMilli().toString()),
                "pairing" to pairing.document(),
                "pairing_expires_at_ms" to StrictCanonicalJson.string(pairingExpiresAt.toEpochMilli().toString()),
            )
            val payload = PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(StrictCanonicalJson.canonicalBytes(value))
            require(payload.length <= MAX_PAYLOAD_CHARACTERS)
            return payload
        } finally {
            secret.fill(0)
        }
    }

    fun parse(payload: String, now: Instant = Instant.now()): Parsed {
        require(payload.startsWith(PREFIX) && payload.length <= MAX_PAYLOAD_CHARACTERS)
        val encoded = payload.removePrefix(PREFIX)
        require(encoded.isNotEmpty() && encoded.length % 4 != 1 && encoded.all(::isBase64UrlCharacter))
        val json = try {
            Base64.getUrlDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("invalid_pairing_envelope")
        }
        val root = StrictCanonicalJson.parseLine(json, MAX_PAYLOAD_CHARACTERS)
        require(StrictCanonicalJson.canonicalBytes(root).contentEquals(json))
        val objectValue = root as? CanonicalJsonObject ?: invalid()
        require(
            objectValue.values.keys ==
                setOf(
                    "bootstrap_id",
                    "bootstrap_secret",
                    "envelope_version",
                    "expires_at_ms",
                    "pairing",
                    "pairing_expires_at_ms",
                ),
        )
        require((objectValue.values["envelope_version"] as? CanonicalJsonInteger)?.value?.toInt() == 2)
        val bootstrapId = (objectValue.values["bootstrap_id"] as? CanonicalJsonString)?.value ?: invalid()
        require(bootstrapId.matches(Regex("boot_[0-9a-f]{32}")))
        val expiryText = (objectValue.values["expires_at_ms"] as? CanonicalJsonString)?.value ?: invalid()
        require(expiryText.isNotEmpty() && expiryText.all { it in '0'..'9' })
        val expiryMillis = expiryText.toLongOrNull() ?: invalid()
        val pairingExpiryText =
            (objectValue.values["pairing_expires_at_ms"] as? CanonicalJsonString)?.value ?: invalid()
        require(pairingExpiryText.isNotEmpty() && pairingExpiryText.all { it in '0'..'9' })
        val pairingExpiryMillis = pairingExpiryText.toLongOrNull() ?: invalid()
        val secretText = (objectValue.values["bootstrap_secret"] as? CanonicalJsonString)?.value ?: invalid()
        val secret = decodeSecret(secretText)
        val pairingValue = objectValue.values["pairing"] as? CanonicalJsonObject ?: run {
            secret.fill(0)
            invalid()
        }
        val pairing = try {
            AndroidLocalNodeChannelCodec.parsePairingMaterial(StrictCanonicalJson.canonicalBytes(pairingValue))
        } catch (failure: Throwable) {
            secret.fill(0)
            throw failure
        }
        val expiresAt = Instant.ofEpochMilli(expiryMillis)
        val nowMillis = now.toEpochMilli()
        if (expiryMillis <= nowMillis) {
            secret.fill(0)
            throw IllegalArgumentException("expired_pairing_envelope")
        }
        if (Duration.between(now, expiresAt) > maxLifetime) {
            secret.fill(0)
            throw IllegalArgumentException("pairing_envelope_lifetime_exceeded")
        }
        val pairingExpiresAt = Instant.ofEpochMilli(pairingExpiryMillis)
        if (pairingExpiresAt < expiresAt || Duration.between(now, pairingExpiresAt) > maxPairingLifetime) {
            secret.fill(0)
            throw IllegalArgumentException("invalid_pairing_expiry")
        }
        return Parsed(pairing, bootstrapId, secret, expiresAt, pairingExpiresAt)
    }

    private fun decodeSecret(value: String): ByteArray {
        require(value.isNotEmpty() && value.length % 4 != 1 && value.all(::isBase64UrlCharacter))
        val decoded = try { Base64.getUrlDecoder().decode(value) } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("invalid_pairing_secret")
        }
        require(decoded.size == 32)
        return decoded
    }

    private fun isBase64UrlCharacter(value: Char): Boolean =
        value in 'A'..'Z' || value in 'a'..'z' || value in '0'..'9' || value == '-' || value == '_'

    private fun invalid(): Nothing = throw IllegalArgumentException("invalid_pairing_envelope")
}
