plugins {
  id("java")
  id("org.jetbrains.kotlin.jvm") version "1.9.24"
  id("org.jetbrains.intellij.platform") version "2.0.0-rc1"
}

group = "ai.vespa.schemals"
version = "1.0-SNAPSHOT"

repositories {
  mavenCentral()

  mavenLocal()
  maven {
    url = uri("file://${System.getProperty("user.home")}/.m2/repository")
    metadataSources {
      mavenPom()
      artifact()
    }
  }

  intellijPlatform {
    defaultRepositories()
  }
}

dependencies {
  implementation("com.yahoo.vespa:config-model:8-SNAPSHOT")
  implementation("com.yahoo.vespa:searchlib:8-SNAPSHOT")
  implementation("com.yahoo.vespa:container-search:8-SNAPSHOT")
  implementation("com.yahoo.vespa:config-model-api:8-SNAPSHOT")
  implementation("org.junit.jupiter:junit-jupiter-api:5.8.2")
  implementation("org.junit.jupiter:junit-jupiter-engine:5.8.2")

  intellijPlatform {
    intellijIdeaUltimate("2024.1")
    //local("/Applications/IntelliJ IDEA.app")
    instrumentationTools()
  }
}

intellijPlatform {
  pluginConfiguration {
    name = "Vespa Schema Language Support"
  }
}

java.sourceSets["main"].java {
  srcDir("../../language-server/src")
  srcDir("../../language-server/target/generated-sources/ccc/")
}

interface InjectFileSystem {
  @get:Inject val fs: FileSystemOperations
}

tasks {
  // Set the JVM compatibility versions
  withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
  }
  withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
  }

  prepareSandbox {
    val fromPath = "../../language-server/target/schema-language-server-jar-with-dependencies.jar"
    val toPath = pluginDirectory.get()

    // see: https://docs.gradle.org/8.7/userguide/configuration_cache.html#config_cache:requirements:disallowed_types
    val injected = project.objects.newInstance<InjectFileSystem>()
    doLast {
      injected.fs.copy {
        from(fromPath)
        into(toPath)
      }
    }
  }

  patchPluginXml {
    sinceBuild.set("232")
    untilBuild.set("242.*")
  }

  signPlugin {
    certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
    privateKey.set(System.getenv("PRIVATE_KEY"))
    password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
  }

  publishPlugin {
    token.set(System.getenv("PUBLISH_TOKEN"))
  }
}
