plugins { kotlin("jvm") version "1.9.25" }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach { kotlinOptions.jvmTarget = "17" }
sourceSets {
    main {
        kotlin.srcDir("../android/app/src/main/java")
        kotlin.include("com/datanet/share/ConnectionControls.kt",
            "com/datanet/share/common/Socks5.kt", "com/datanet/share/common/Endpoint.kt",
            "com/datanet/share/common/Ipv4Packet.kt", "com/datanet/share/common/PairingPayload.kt",
            "com/datanet/share/common/ProxyPasswordPolicy.kt",
            "com/datanet/share/common/UpstreamRoutePolicy.kt",
            "com/datanet/share/common/DiagnosticJournal.kt",
            "com/datanet/share/common/P2pPasswordCipher.kt",
            "com/datanet/share/sender/WifiDirectProfile.kt",
            "com/datanet/share/sender/Socks5Server.kt",
            "com/datanet/share/sender/PeerUsageLedger.kt", "com/datanet/share/receiver/TcpFlow.kt",
            "com/datanet/share/receiver/UdpFlow.kt", "com/datanet/share/receiver/ReceiverSocketSetup.kt",
            "com/datanet/share/receiver/TunWriteRetry.kt",
            "com/datanet/share/receiver/UdpFlowPolicy.kt",
            "com/datanet/share/receiver/TcpCapacityPolicy.kt",
            "com/datanet/share/receiver/FlowAdmission.kt",
            "com/datanet/share/receiver/SenderProbeRetryPolicy.kt",
            "com/datanet/share/receiver/SenderHealthProbe.kt")
    }
    test { kotlin.srcDir("../android/app/src/test/java") }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    // Exercise the same QR bitstream codec used by the Android scanner/encoder.
    testImplementation("com.google.zxing:core:3.5.4")
}
tasks.test { testLogging { events("passed", "failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL } }
