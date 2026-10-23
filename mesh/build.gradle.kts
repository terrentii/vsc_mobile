plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":crypto"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")
    // Как в :crypto: API lazysodium; реализацию даёт платформа (android/java).
    compileOnly("com.goterl:lazysodium-java:5.1.4")
    testImplementation(project(":p2p")) // настоящий handshake поверх mesh в тестах
    testImplementation("com.goterl:lazysodium-java:5.1.4")
    testImplementation("net.java.dev.jna:jna:5.17.0")
    testImplementation("org.json:json:20250517")
    testImplementation(kotlin("test-junit"))
}
sourceSets.test { resources.srcDir("../crypto/src/test/resources") }
tasks.test {
    useJUnit()
    systemProperty("mys.root", rootDir.path)
}
