plugins { kotlin("jvm") }

kotlin { jvmToolchain(17) }

dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")
    // Только API: на Android реализацию даёт lazysodium-android (тот же пакет
    // com.goterl.lazysodium), в тестах — lazysodium-java с нативами под JVM.
    // lazysodium-java 5.2.0 требует JVM 21 — берём 5.1.4 (тот же Ristretto255.Native).
    compileOnly("com.goterl:lazysodium-java:5.1.4")
    testImplementation("com.goterl:lazysodium-java:5.1.4")
    testImplementation("net.java.dev.jna:jna:5.17.0")
    testImplementation("org.json:json:20250517")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnit() }
