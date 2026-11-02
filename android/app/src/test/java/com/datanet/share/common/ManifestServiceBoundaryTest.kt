package com.datanet.share.common

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Static guard for Android service exposure; not an on-device permission test. */
class ManifestServiceBoundaryTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun manifest(): Element {
        // Host proxy-tests and Android unit tests have different working directories.
        val paths = listOf(
            "android/app/src/main/AndroidManifest.xml",
            "../android/app/src/main/AndroidManifest.xml",
            "app/src/main/AndroidManifest.xml",
            "src/main/AndroidManifest.xml"
        )
        val source = paths.map(::File).firstOrNull(File::isFile)
            ?: error("Maintained AndroidManifest.xml not found from test working directory")
        val parser = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        return parser.newDocumentBuilder().parse(source).documentElement
    }

    private fun service(app: Element, name: String): Element {
        val nodes = app.getElementsByTagName("service")
        val matches = (0 until nodes.length).map { nodes.item(it) as Element }
            .filter { it.getAttributeNS(androidNs, "name") == name }
        assertEquals("Expected exactly one $name service declaration", 1, matches.size)
        return matches.single()
    }

    @Test fun foregroundNetworkServicesRemainPrivateToThisApp() {
        val root = manifest()
        val app = root.getElementsByTagName("application").item(0) as Element
        assertEquals("false", app.getAttributeNS(androidNs, "allowBackup"))
        assertEquals("false", app.getAttributeNS(androidNs, "usesCleartextTraffic"))

        val sender = service(app, ".sender.SenderService")
        val receiver = service(app, ".receiver.ReceiverVpnService")
        assertEquals("false", sender.getAttributeNS(androidNs, "exported"))
        assertEquals("false", receiver.getAttributeNS(androidNs, "exported"))
        assertEquals("android.permission.BIND_VPN_SERVICE",
            receiver.getAttributeNS(androidNs, "permission"))
        val actions = receiver.getElementsByTagName("action")
        assertTrue((0 until actions.length).any {
            (actions.item(it) as Element).getAttributeNS(androidNs, "name") == "android.net.VpnService"
        })
    }
}
