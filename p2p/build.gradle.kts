plugins { kotlin("jvm") }

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":crypto"))
    api(project(":mesh")) // путь канала через mesh-сеть
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")
    compileOnly("com.goterl:lazysodium-java:5.1.4")
    testImplementation("com.goterl:lazysodium-java:5.1.4")
    testImplementation("net.java.dev.jna:jna:5.17.0")
    testImplementation("org.json:json:20250517")
    testImplementation(kotlin("test-junit"))
}

// Векторы общие с :crypto — один генератор, один файл.
sourceSets.test { resources.srcDir("../crypto/src/test/resources") }

tasks.test {
    useJUnit()
    systemProperty("mys.root", rootDir.path)
}
