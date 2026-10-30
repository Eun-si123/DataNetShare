package com.datanet.share.common

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Source-level backup policy guard; actual OEM transfer behavior needs device testing. */
class BackupTransferPolicyTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun resource(path: String): Element {
        val locations = listOf(
            "android/app/src/main/$path",
            "../android/app/src/main/$path",
            "app/src/main/$path",
            "src/main/$path"
        )
        val file = locations.map(::File).firstOrNull(File::isFile)
            ?: error("Missing maintained Android resource: $path")
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    @Test fun cloudBackupAndDeviceTransferBothExcludeAllAppData() {
        val manifest = resource("AndroidManifest.xml")
        val app = manifest.getElementsByTagName("application").item(0) as Element
        assertEquals("false", app.getAttributeNS(androidNs, "allowBackup"))
        assertEquals("false", app.getAttributeNS(androidNs, "fullBackupContent"))
        assertEquals("@xml/data_extraction_rules",
            app.getAttributeNS(androidNs, "dataExtractionRules"))

        val rules = resource("res/xml/data_extraction_rules.xml")
        assertEquals("data-extraction-rules", rules.tagName)
        for (sectionName in listOf("cloud-backup", "device-transfer")) {
            val sections = rules.getElementsByTagName(sectionName)
            assertEquals("Exactly one $sectionName policy", 1, sections.length)
            val section = sections.item(0) as Element
            assertEquals("No backup includes allowed in $sectionName",
                0, section.getElementsByTagName("include").length)
            val excludes = section.getElementsByTagName("exclude")
            val domains = (0 until excludes.length).map {
                val exclude = excludes.item(it) as Element
                assertEquals("Exclude whole directory recursively", ".",
                    exclude.getAttribute("path"))
                exclude.getAttribute("domain")
            }.toSet()
            assertEquals(
                setOf(
                    "root", "file", "database", "sharedpref",
                    "device_root", "device_file", "device_database", "device_sharedpref",
                    "external"
                ), domains
            )
            assertEquals("No duplicate exclusions", 9, excludes.length)
        }
        assertTrue("No root-level include rules",
            rules.getElementsByTagName("include").length == 0)
    }
}
