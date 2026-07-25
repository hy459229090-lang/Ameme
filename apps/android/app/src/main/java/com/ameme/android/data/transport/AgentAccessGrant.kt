package com.ameme.android.data.transport

import java.time.Instant

/**
 * Android representation of the canonical AccessGrant contract.
 *
 * This is authorization metadata only: it contains no pairing secret, endpoint,
 * certificate private key, or memory content. A channel must authenticate the
 * peer separately and then authorize each minimal request against this object.
 */
data class AgentAccessGrant(
    val schemaVersion: Int,
    val grantId: String,
    val ownerId: String,
    val callerId: String,
    val purposes: Set<String>,
    val spaces: Set<String>,
    val dataTypes: Set<String>,
    val notBefore: Instant,
    val expiresAt: Instant,
    val status: AgentAccessGrantStatus,
    val keyFingerprint: String? = null,
    val createdAt: Instant,
    val revokedAt: Instant? = null,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "unsupported AccessGrant schema" }
        requireIdentifier(grantId, "grantId")
        requireIdentifier(ownerId, "ownerId")
        requireIdentifier(callerId, "callerId")
        requireScope(purposes, "purposes", MAX_PURPOSE_LENGTH)
        requireScope(spaces, "spaces", MAX_IDENTIFIER_LENGTH)
        requireScope(dataTypes, "dataTypes", MAX_DATA_TYPE_LENGTH)
        require(!expiresAt.isBefore(notBefore)) { "grant expiry must not precede notBefore" }
        require(expiresAt.isAfter(createdAt)) { "grant expiry must be in the future of creation" }
        keyFingerprint?.let { require(it.length <= MAX_KEY_FINGERPRINT_LENGTH) }
        if (status == AgentAccessGrantStatus.Revoked) {
            require(revokedAt != null) { "revoked grant requires revokedAt" }
        } else {
            require(revokedAt == null) { "only revoked grant may have revokedAt" }
        }
    }

    fun isActive(at: Instant = Instant.now()): Boolean =
        status == AgentAccessGrantStatus.Active &&
            !at.isBefore(notBefore) &&
            expiresAt.isAfter(at)

    /** Checks a minimal request scope; a request may never expand this Grant. */
    fun authorize(request: AgentAccessGrantRequest, at: Instant = Instant.now()) {
        authorizeScope(
            callerId = request.callerId,
            grantId = request.grantId,
            purpose = request.purpose,
            spaces = setOf(request.space),
            dataTypes = setOf(request.dataType),
            at = at,
        )
    }

    fun authorizeScope(
        callerId: String,
        grantId: String,
        purpose: String,
        spaces: Set<String>,
        dataTypes: Set<String>,
        at: Instant = Instant.now(),
    ) {
        if (callerId != this.callerId || grantId != this.grantId) {
            throw AgentAccessGrantException(AgentAccessGrantFailure.AuthRequired)
        }
        when {
            status == AgentAccessGrantStatus.Revoked ->
                throw AgentAccessGrantException(AgentAccessGrantFailure.GrantRevoked)
            !expiresAt.isAfter(at) || at.isBefore(notBefore) ->
                throw AgentAccessGrantException(AgentAccessGrantFailure.GrantExpired)
            purpose !in purposes ->
                throw AgentAccessGrantException(AgentAccessGrantFailure.PurposeDenied)
            !spaces.all(this.spaces::contains) ->
                throw AgentAccessGrantException(AgentAccessGrantFailure.SpaceDenied)
            !dataTypes.all(this.dataTypes::contains) ->
                throw AgentAccessGrantException(AgentAccessGrantFailure.DataTypeDenied)
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        private const val MAX_SCOPE_ITEMS = 8
        private const val MAX_IDENTIFIER_LENGTH = 128
        private const val MAX_PURPOSE_LENGTH = 80
        private const val MAX_DATA_TYPE_LENGTH = 64
        private const val MAX_KEY_FINGERPRINT_LENGTH = 256
        private val IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")

        private fun requireIdentifier(value: String, field: String) {
            require(IDENTIFIER.matches(value)) { "$field is invalid" }
            require('*' !in value) { "$field must not contain a wildcard" }
        }

        private fun requireScope(values: Set<String>, field: String, maxLength: Int) {
            require(values.isNotEmpty() && values.size <= MAX_SCOPE_ITEMS) {
                "$field must contain a bounded non-empty scope"
            }
            require(values.all { it.isNotBlank() && it.length <= maxLength && '*' !in it }) {
                "$field contains an invalid wildcard or value"
            }
        }
    }
}

/**
 * Local, user-approved scope bound to a pairing. The caller and Grant IDs are
 * still bound by [AgentAccessGrant] when a request arrives; this policy only
 * records the maximum scope that the user approved for the pairing.
 */
data class AgentAccessGrantPolicy(
    val schemaVersion: Int,
    val ownerId: String,
    val purposes: Set<String>,
    val spaces: Set<String>,
    val dataTypes: Set<String>,
    val notBefore: Instant,
    val expiresAt: Instant,
    val status: AgentAccessGrantStatus,
    val createdAt: Instant,
) {
    init {
        require(schemaVersion == AgentAccessGrant.CURRENT_SCHEMA_VERSION)
        require(ownerId.matches(IDENTIFIER))
        require(purposes.isNotEmpty() && purposes.size <= 8 && purposes.all { it.length <= 80 && '*' !in it })
        require(spaces.isNotEmpty() && spaces.size <= 8 && spaces.all { it.length <= 128 && '*' !in it })
        require(dataTypes.isNotEmpty() && dataTypes.size <= 8 && dataTypes.all { it.length <= 64 && '*' !in it })
        require(expiresAt >= notBefore && expiresAt > createdAt)
    }

    fun isActive(at: Instant = Instant.now()): Boolean =
        status == AgentAccessGrantStatus.Active && at >= notBefore && at < expiresAt

    fun bind(callerId: String, grantId: String): AgentAccessGrant = AgentAccessGrant(
        schemaVersion = schemaVersion,
        grantId = grantId,
        ownerId = ownerId,
        callerId = callerId,
        purposes = purposes,
        spaces = spaces,
        dataTypes = dataTypes,
        notBefore = notBefore,
        expiresAt = expiresAt,
        status = status,
        createdAt = createdAt,
    )

    companion object {
        private val IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")

        fun default(
            ownerId: String,
            createdAt: Instant,
            expiresAt: Instant,
        ): AgentAccessGrantPolicy = AgentAccessGrantPolicy(
            schemaVersion = AgentAccessGrant.CURRENT_SCHEMA_VERSION,
            ownerId = ownerId,
            purposes = setOf("autonomous_memory"),
            spaces = setOf("space_personal"),
            dataTypes = setOf("event"),
            notBefore = createdAt,
            expiresAt = expiresAt,
            status = AgentAccessGrantStatus.Active,
            createdAt = createdAt,
        )
    }
}

enum class AgentAccessGrantStatus(val wireValue: String) {
    Active("active"),
    Revoked("revoked"),
    Expired("expired"),
}

data class AgentAccessGrantRequest(
    val callerId: String,
    val grantId: String,
    val purpose: String,
    val space: String,
    val dataType: String,
    val operation: String,
) {
    init {
        require(callerId.matches(IDENTIFIER))
        require(grantId.matches(IDENTIFIER))
        require(purpose.isNotBlank() && purpose.length <= 80)
        require(space.isNotBlank() && space.length <= 128)
        require(dataType.isNotBlank() && dataType.length <= 64)
        require(operation.matches(IDENTIFIER))
    }

    private companion object {
        val IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    }
}

enum class AgentAccessGrantFailure(val wireValue: String) {
    AuthRequired("AUTH_REQUIRED"),
    GrantRevoked("GRANT_REVOKED"),
    GrantExpired("GRANT_EXPIRED"),
    PurposeDenied("PURPOSE_DENIED"),
    SpaceDenied("SPACE_DENIED"),
    DataTypeDenied("DATA_TYPE_DENIED"),
}

class AgentAccessGrantException(
    val failure: AgentAccessGrantFailure,
) : IllegalStateException(failure.wireValue)
