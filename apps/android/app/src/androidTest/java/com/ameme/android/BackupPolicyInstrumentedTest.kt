package com.ameme.android

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class BackupPolicyInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun systemBackupAndDeviceTransfer_excludeEveryAppDataDomain() {
        assertFalse(context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)

        val exclusions = mutableMapOf<String, MutableSet<String>>()
        var section: String? = null
        context.resources.getXml(R.xml.data_extraction_rules).use { parser ->
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "cloud-backup", "device-transfer" -> section = parser.name
                        "exclude" -> section?.let { activeSection ->
                            exclusions.getOrPut(activeSection) { mutableSetOf() }
                                .add(parser.getAttributeValue(null, "domain"))
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.name == section) section = null
                }
                parser.next()
            }
        }

        assertEquals(EXCLUDED_DOMAINS, exclusions["cloud-backup"])
        assertEquals(EXCLUDED_DOMAINS, exclusions["device-transfer"])
    }

    private companion object {
        val EXCLUDED_DOMAINS = setOf(
            "root",
            "file",
            "database",
            "sharedpref",
            "external",
            "device_root",
            "device_file",
            "device_database",
            "device_sharedpref",
        )
    }
}
