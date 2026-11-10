plugins {
    java
    // 蓝图写的 8.3.6 只支持 Gradle 8.x；本机只有 JDK 26，必须用 Gradle 9（见 docs/api-facts.md 第 9 节）
    id("com.gradleup.shadow") version "9.6.1"
}

group = "dev.catmaid"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.citizensnpcs.co/")
}

dependencies {
    // 坐标以 docs/api-facts.md 第 2 节实测结果为准（蓝图 0.4 原值已在 Phase 0 修正）
    compileOnly("io.papermc.paper:paper-api:26.2.build.135-stable")
    compileOnly("net.citizensnpcs:citizens-main:2.0.44-SNAPSHOT")

    // BetterModel 不做编译期依赖：运行时 jar 与 Maven Central 的 1.15.2 API 不一致，
    // 且它是 paper-plugin（classloader 隔离）。对它的唯一调用集中在 maid/ModelApplier 内走反射，
    // 见 docs/api-facts.md 第 2 节与附录 A。

    implementation("org.xerial:sqlite-jdbc:3.50.3.0") // 唯一被 shade 的依赖
}

// 本机只有 JDK 26，toolchain 25 需联网自动下载（不稳定）。
// 改用 --release 25 产出 Java 25 字节码，运行期与蓝图一致。
tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
}

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("org.sqlite", "dev.catmaid.libs.sqlite")
}
