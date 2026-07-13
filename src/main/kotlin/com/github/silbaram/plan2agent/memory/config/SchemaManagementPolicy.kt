package com.github.silbaram.plan2agent.memory.config

import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

private val NON_MUTATING_DDL_MODES = setOf("none", "validate")

@Configuration(proxyBeanMethods = false)
class SchemaManagementPolicy {
    @Bean
    fun flywayOnlySchemaManagementGuard(environment: Environment): BeanFactoryPostProcessor =
        BeanFactoryPostProcessor {
            enforceFlywayOnlySchemaManagement(environment)
        }
}

internal fun enforceFlywayOnlySchemaManagement(environment: Environment) {
    check(environment.getProperty("spring.flyway.enabled", Boolean::class.java, true)) {
        "spring.flyway.enabled must be true because db/migration SQL files are the only supported schema-management path"
    }
    check(!environment.getProperty("spring.jpa.generate-ddl", Boolean::class.java, false)) {
        "spring.jpa.generate-ddl must be false; manage the schema with Flyway SQL migrations"
    }
    requireNonMutatingMode(environment, "spring.jpa.hibernate.ddl-auto")
    requireNonMutatingMode(environment, "spring.jpa.properties.hibernate.hbm2ddl.auto")
    requireNonMutatingMode(environment, "spring.jpa.properties.jakarta.persistence.schema-generation.database.action")
    requireNonMutatingMode(environment, "spring.jpa.properties.javax.persistence.schema-generation.database.action")

    val sqlInitMode = environment.getProperty("spring.sql.init.mode")?.trim()?.lowercase()
    check(sqlInitMode == "never") {
        "spring.sql.init.mode must be never; manage the schema with Flyway SQL migrations"
    }
}

private fun requireNonMutatingMode(environment: Environment, propertyName: String) {
    val mode = environment.getProperty(propertyName)?.trim()?.lowercase() ?: return
    check(mode in NON_MUTATING_DDL_MODES) {
        "$propertyName=$mode can mutate the database; only none or validate is allowed"
    }
}
