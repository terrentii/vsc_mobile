plugins { kotlin("jvm") }

kotlin { jvmToolchain(17) }

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    // org.json встроен в Android; на JVM — только компиляция и тесты.
    // Пользоваться лишь API, которое есть и в старом платформенном org.json.
    compileOnly("org.json:json:20250517")
    testImplementation("org.json:json:20250517")
    testImplementation(kotlin("test-junit"))
}

tasks.test {
    useJUnit()
    systemProperty("mys.root", rootDir.path)
}
