import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.coroutines.core)
    implementation(libs.postgresql)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.sqlite.jdbc)
}

tasks.test {
    // Postgres integration tests run only when DBX_PG_HOST is set (see README).
    listOf("DBX_PG_HOST", "DBX_PG_PORT", "DBX_PG_DB", "DBX_PG_USER", "DBX_PG_PASSWORD").forEach { key ->
        System.getenv(key)?.let { environment(key, it) }
    }
}
