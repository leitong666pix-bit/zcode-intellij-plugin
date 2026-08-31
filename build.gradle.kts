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
        if (System.getenv("CI") == "true") {
            // CI（GitHub Actions）：远程拉取平台依赖（约 1-2GB，有缓存）。
            // 基线取最低支持版本 2024.2：在此编译通过即可保证 sinceBuild=242 全程可运行
            // （JBHtmlPane 用的是 242/252 共有的两参构造，见 ChatUi.kt）。
            intellijIdea("2024.2")
        } else {
            // 本机开发：直接用本地安装的 IDEA，零下载
            local("D:/IntelliJ/IntelliJ IDEA 2025.2.4")
        }
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
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }
}

tasks {
    test {
        useJUnitPlatform()
    }
}
