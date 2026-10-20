import io.izzel.taboolib.gradle.*
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    java
    id("io.izzel.taboolib") version "2.0.39"
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
}

group = "cn.trtown"
version = "0.1.0"

repositories {
    maven("https://repo.tabooproject.org/repository/releases/")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://maven.aliyun.com/repository/central")
    mavenCentral()
}

taboolib {
    env {
        install(Basic)
        install(Bukkit)
        install(BukkitUI)
        install(Database)
        install(MinecraftChat)
    }
    description {
        name = "TrTown"
        dependencies {
            name("Dominion")
            name("PlaceholderAPI").optional(true)
        }
    }
    version {
        taboolib = "6.3.0-0e3a911"
    }
}

dependencies {
    compileOnly("dev.folia:folia-api:26.3.build.3-alpha")
    compileOnly("cn.lunadeer:DominionAPI:4.9.6")
    compileOnly("me.clip:placeholderapi:2.12.3")
    compileOnly(kotlin("stdlib"))

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
    // 仓储单测直接用驱动自带的 DataSource，不依赖 TabooLib 运行期下载
    testImplementation("org.xerial:sqlite-jdbc:3.53.4.0")
    testImplementation("com.h2database:h2:2.5.252")
}

// folia-api 26.3 自身编译目标是 Java 25（Gradle 会拒绝低于 25 的消费者），
// 因此本项目的字节码目标必须为 25，而不是 PLAN 里写的 21。
java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

/**
 * 可复现构建：TabooLib 的 `taboolibMainTask` 会重写 jar 并把条目时间戳写成当前时间，
 * 导致相同源码两次构建的 md5 不同（内容逐条 CRC 相同）。
 *
 * 这个任务在打包完成后原地重写 jar：条目按名称排序、时间戳固定，
 * 精简掉平台相关字段。写回前会校验"条目集合 + CRC + 大小"与原文件完全一致，
 * 不一致则直接失败，绝不替换产物。
 */
val normalizeJar = tasks.register("normalizeJar") {
    description = "让 jar 字节可复现（固定条目顺序与时间戳）"
    group = "build"
    dependsOn(tasks.named("taboolibMainTask"))

    val jarFile = layout.buildDirectory.file("libs/TrTown-${project.version}.jar")
    inputs.file(jarFile)
    outputs.file(jarFile)

    doLast {
        val target = jarFile.get().asFile
        if (!target.isFile) throw GradleException("找不到待规范化的 jar: $target")
        val fixedTime = 315532800000L // 1980-01-01T00:00:00Z，DOS 时间的最小可表示值

        val original = ZipFile(target)
        val expected = original.entries().toList()
            .associate { it.name to Triple(it.crc, it.size, it.compressedSize) }
        val bytes = expected.keys.associateWith { name ->
            original.getInputStream(original.getEntry(name)).use { it.readBytes() }
        }
        original.close()

        val tmp = File(target.parentFile, target.name + ".normalize.tmp")
        ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            expected.keys.sorted().forEach { name ->
                val entry = ZipEntry(name).apply { time = fixedTime }
                zip.putNextEntry(entry)
                zip.write(bytes.getValue(name))
                zip.closeEntry()
            }
        }

        // 校验：条目集合与内容必须与原 jar 完全一致，否则拒绝替换
        ZipFile(tmp).use { check ->
            val actual = check.entries().toList()
                .associate { it.name to Triple(it.crc, it.size, it.compressedSize) }
            if (actual.keys != expected.keys) {
                tmp.delete()
                throw GradleException("规范化后条目集合变化，已放弃替换")
            }
            actual.forEach { (name, meta) ->
                if (meta.first != expected.getValue(name).first || meta.second != expected.getValue(name).second) {
                    tmp.delete()
                    throw GradleException("规范化后 $name 内容变化（CRC/大小不符），已放弃替换")
                }
            }
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        logger.lifecycle("[normalizeJar] ${target.name} 已规范化：${expected.size} 个条目")
    }
}

// 只要 TabooLib 重写了 jar（build / test / jar 都可能触发），就立刻规范化一次
tasks.named("taboolibMainTask") { finalizedBy(normalizeJar) }
tasks.named("assemble") { finalizedBy(normalizeJar) }

/** AC-25 性能基准：只跑 @Tag("perf") 的用例 */
tasks.register<Test>("perfTest") {
    description = "PAPI 变量性能基准（AC-25）"
    group = "verification"
    useJUnitPlatform {
        includeTags("perf")
    }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    testLogging {
        events("passed", "failed")
        showStandardStreams = true
    }
}
