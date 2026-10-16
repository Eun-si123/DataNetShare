plugins { kotlin("jvm") version "1.9.22" }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach { kotlinOptions.jvmTarget = "17" }
sourceSets {
    main {
        kotlin.srcDir("../android/app/src/main/java")
        kotlin.include("com/datanet/share/common/Socks5.kt", "com/datanet/share/common/Endpoint.kt",
            "com/datanet/share/common/Ipv4Packet.kt",
            "com/datanet/share/sender/Socks5Server.kt", "com/datanet/share/receiver/TcpFlow.kt",
            "com/datanet/share/receiver/UdpFlow.kt", "com/datanet/share/receiver/ReceiverSocketSetup.kt")
    }
    test { kotlin.srcDir("../android/app/src/test/java") }
}
dependencies { testImplementation("junit:junit:4.13.2") }
tasks.test { testLogging { events("passed", "failed", "skipped") } }
