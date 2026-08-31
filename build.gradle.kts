plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.10"
    id("org.jetbrains.intellij.platform") // 版本由 settings.gradle.kts 中的 settings 插件统一管理
}

group = "zcode.idea"
version = "0.1.0"

repositories {
    maven("https://maven.aliyun.com/repository/public")
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        local("D:/IntelliJ/IntelliJ IDEA 2025.2.4")
        // 本机未安装该版本 IDE 时，可注释上一行并启用下一行（需联网下载约 1-2GB）
        // intellijIdea("2024.3")
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // IntelliJ Platform 的 JUnit5 测试环境初始化器（Logger 工厂）依赖 JUnit4 的类
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "zcode.idea"
        name = "ZCode Assistant"
        version = project.version.toString()
        description = "ZCode AI 编码助手：在 IDEA 内与 zcode CLI 会话（选区上下文、工具审批、diff 查看）"
        vendor {
            name = "zcode-idea-plugin"
        }
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }
}

tasks {
    test {
        useJUnitPlatform()
    }
}
