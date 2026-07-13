package com.github.silbaram.plan2agent.memory.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.mock.env.MockEnvironment

class SchemaManagementPolicyTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(SchemaManagementPolicy::class.java)
        .withPropertyValues(
            "spring.flyway.enabled=true",
            "spring.jpa.generate-ddl=false",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.sql.init.mode=never",
        )

    @Test
    fun `accepts Flyway-only schema management`() {
        enforceFlywayOnlySchemaManagement(safeEnvironment())
    }

    @Test
    fun `registers the guard in the Spring context`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
        }
    }

    @Test
    fun `stops Spring context creation before an unsafe override can be used`() {
        contextRunner
            .withPropertyValues("spring.jpa.hibernate.ddl-auto=update")
            .run { context ->
                assertThat(context)
                    .hasFailed()
                assertThat(context.startupFailure)
                    .hasMessage(
                        "spring.jpa.hibernate.ddl-auto=update can mutate the database; only none or validate is allowed",
                    )
            }
    }

    @Test
    fun `rejects Hibernate schema mutation modes`() {
        listOf("create", "create-drop", "update").forEach { mode ->
            val environment = safeEnvironment()
                .withProperty("spring.jpa.hibernate.ddl-auto", mode)

            assertThatIllegalStateException()
                .isThrownBy { enforceFlywayOnlySchemaManagement(environment) }
                .withMessageContaining("can mutate the database")
        }
    }

    @Test
    fun `rejects standard JPA and raw Hibernate schema generation overrides`() {
        listOf(
            "spring.jpa.properties.hibernate.hbm2ddl.auto" to "update",
            "spring.jpa.properties.jakarta.persistence.schema-generation.database.action" to "create",
            "spring.jpa.properties.javax.persistence.schema-generation.database.action" to "drop-and-create",
        ).forEach { (propertyName, mode) ->
            val environment = safeEnvironment().withProperty(propertyName, mode)

            assertThatIllegalStateException()
                .isThrownBy { enforceFlywayOnlySchemaManagement(environment) }
                .withMessageContaining(propertyName)
        }
    }

    @Test
    fun `rejects non-Flyway schema initialization`() {
        listOf(
            "spring.flyway.enabled" to "false",
            "spring.jpa.generate-ddl" to "true",
            "spring.sql.init.mode" to "always",
        ).forEach { (propertyName, value) ->
            val environment = safeEnvironment().withProperty(propertyName, value)

            assertThatIllegalStateException()
                .isThrownBy { enforceFlywayOnlySchemaManagement(environment) }
                .withMessageContaining(propertyName)
        }
    }

    private fun safeEnvironment(): MockEnvironment =
        MockEnvironment()
            .withProperty("spring.flyway.enabled", "true")
            .withProperty("spring.jpa.generate-ddl", "false")
            .withProperty("spring.jpa.hibernate.ddl-auto", "none")
            .withProperty("spring.sql.init.mode", "never")
}
