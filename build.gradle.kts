plugins {
    java
    // 蓝图写的 8.3.6 只支持 Gradle 8.x；本机只有 JDK 26，必须用 Gradle 9（见 docs/api-facts.md 第 9 节）
    id("com.gradleup.shadow") version "9.6.1"
}

group = "dev.catmaid"
version = "1.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.citizensnpcs.co/")
}

dependencies {
    // 坐标以 docs/api-facts.md 第 2 节实测结果为准（蓝图 0.4 原值已在 Phase 0 修正）
    compileOnly("io.papermc.paper:paper-api:26.2.build.135-stable")
    // citizens-main 内含全部 net.citizensnpcs.api.* 类，但它的传递依赖（libby-bukkit 等）
    // 在公开仓库里不存在；编译只需要 API 类，关掉传递解析。
    compileOnly("net.citizensnpcs:citizens-main:2.0.44-SNAPSHOT") { isTransitive = false }

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
    // P10 冲突修订（api-facts 26.3）：sqlite-jdbc 3.50.3.0 的原生库（Windows/Linux/macOS 的
    // sqlitejdbc 动态库）在 JNI_OnLoad 里按硬编码的 "org/sqlite/core/NativeDB" 做 RegisterNatives，
    // 导出表里也只有 Java_org_sqlite_* 符号——relocate 到 dev.catmaid.libs.sqlite 后 native 方法
    // 全部绑定失败（_open_utf8 UnsatisfiedLinkError，2026-11-13 测试服启动实测 + 纯 JVM 探针复现）。
    // 因此不再 relocate：Paper 的插件类加载器本身就把各插件的类隔离开了，
    // 不 relocate 也不会与 Citizens 自带的 sqlite 相互污染。
}
