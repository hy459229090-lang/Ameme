package com.ameme.android.ui.screens

import java.time.LocalDate

internal class SearchRequestIdentity(
    val repositoryIdentity: Any,
    val query: String,
    val date: LocalDate?,
) {
    override fun equals(other: Any?): Boolean = other is SearchRequestIdentity &&
        repositoryIdentity === other.repositoryIdentity &&
        query == other.query &&
        date == other.date

    override fun hashCode(): Int = 31 * (31 * System.identityHashCode(repositoryIdentity) + query.hashCode()) +
        (date?.hashCode() ?: 0)
}

internal data class SearchRequestToken(
    val generation: Long,
    val identity: SearchRequestIdentity,
)

internal class SearchRequestCoordinator {
    private var generation = 0L
    private var current: SearchRequestToken? = null

    fun begin(identity: SearchRequestIdentity): SearchRequestToken = SearchRequestToken(
        generation = ++generation,
        identity = identity,
    ).also { current = it }

    fun current(identity: SearchRequestIdentity): SearchRequestToken? = current?.takeIf { it.identity == identity }

    fun isCurrent(token: SearchRequestToken, identity: SearchRequestIdentity): Boolean =
        current == token && token.identity == identity

    inline fun commitIfCurrent(
        token: SearchRequestToken,
        identity: SearchRequestIdentity,
        commit: () -> Unit,
    ): Boolean {
        if (!isCurrent(token, identity)) return false
        commit()
        return true
    }
}
