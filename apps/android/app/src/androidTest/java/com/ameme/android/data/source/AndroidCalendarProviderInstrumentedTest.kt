package com.ameme.android.data.source

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidCalendarProviderInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation

    @Test
    fun realProviderCatalogRequiresReadPermissionAndSupportsPreQueryCancellation() {
        val denied = AndroidCalendarProviderDataSource(context.contentResolver) { false }
        assertTrue(runCatching { denied.listCalendars() }.exceptionOrNull() is SecurityException)

        automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_CALENDAR)
        val source = AndroidCalendarProviderDataSource(context)
        assertTrue(runCatching { source.listCalendars() }.isSuccess)

        val cancellation = CalendarImportCancellation().apply { cancel() }
        assertTrue(
            runCatching { source.listCalendars(cancellation) }.exceptionOrNull() is CalendarImportCancelled,
        )
    }
}
