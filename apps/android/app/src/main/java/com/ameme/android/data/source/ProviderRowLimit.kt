package com.ameme.android.data.source

internal class ProviderRowLimit(
    private val maximumRows: Int,
    private val sourceName: String,
) {
    private var rowsSeen = 0

    init {
        require(maximumRows > 0) { "Provider row limit must be positive" }
    }

    fun observeRow() {
        rowsSeen += 1
        require(rowsSeen <= maximumRows) {
            "$sourceName ignored the requested row limit"
        }
    }
}
