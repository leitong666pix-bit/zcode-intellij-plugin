import java.io.File
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.10"
    id("org.jetbrains.intellij.platform") // 版本由 settings.gradle.kts 中的 settings 插件统一管理
}

group = "zcode.idea"
version = "0.2.0"

repositories {
    maven("https://maven.aliyun.com/repository/public")
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        // 本机开发零下载：用 -PlocalIdePath=<IDEA 安装目录>（配置在 gradle.properties，换机器请改/删）。
        // 未配置或路径无效时回落远程 2024.2 基线（CI 与其他贡献者机器）：
        // 在此编译通过即可保证 sinceBuild=242 全程可运行（JBHtmlPane 用的是 242/252 共有的两参构造，见 ChatUi.kt）
        val localIde = providers.gradleProperty("localIdePath").orNull?.trim()?.takeIf { File(it).isDirectory }
        if (localIde != null) {
            local(localIde)
        } else {
            intellijIdea("2024.2")
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
    pluginVerification {
        ides {
            // 最低支持基线 + 一个较新版本：CI 里跑 runPluginVerifier 兜底 API 兼容性
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.2")
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2")
        }
    }
}

tasks {
    test {
        useJUnitPlatform()
    }
}
