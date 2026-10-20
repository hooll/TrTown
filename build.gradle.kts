import io.izzel.taboolib.gradle.*
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

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
        install(I18n)
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
