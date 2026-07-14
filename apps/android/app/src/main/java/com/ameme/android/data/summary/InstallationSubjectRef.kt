package com.ameme.android.data.summary

import android.content.Context
import java.util.UUID

object InstallationSubjectRef {
    private const val PREFERENCES = "ameme_non_content_identity"
    private const val KEY = "inference_subject_ref"

    fun getOrCreate(context: Context): String {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        preferences.getString(KEY, null)?.let { return it }
        val created = "install_${UUID.randomUUID().toString().replace("-", "")}"
        check(preferences.edit().putString(KEY, created).commit()) {
            "Could not persist the local inference subject reference"
        }
        return created
    }
}
