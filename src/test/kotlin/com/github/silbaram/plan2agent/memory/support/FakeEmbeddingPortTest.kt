package com.github.silbaram.plan2agent.memory.support

import com.github.silbaram.plan2agent.memory.application.port.out.ActiveEmbeddingTarget
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingPort
import com.github.silbaram.plan2agent.memory.application.port.out.EmbeddingProviderState
import com.github.silbaram.plan2agent.memory.application.port.out.ProviderContractViolationException
import com.github.silbaram.plan2agent.memory.application.port.out.ProviderNotConfiguredException
import com.github.silbaram.plan2agent.memory.application.port.out.ProviderUnavailableException
import com.github.silbaram.plan2agent.memory.config.EmbeddingProviderConfiguration
import com.github.silbaram.plan2agent.memory.domain.EmbeddingSetId
import com.github.silbaram.plan2agent.memory.domain.V2EmbeddingProfile
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset.offset
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class FakeEmbeddingPortTest {
    @Test
    fun `returns deterministic finite normalized 384 dimensional vectors and records query document modes`() {
        val target = ActiveEmbeddingTarget(
            profile = V2EmbeddingProfile.fixed,
            embeddingSetId = EmbeddingSetId("test-embedding-set"),
        )
        val fake = FakeEmbeddingPort(activeEmbeddingTarget = target)

        val firstQuery = fake.embedQuery("결제 취소 정책")
        val repeatedQuery = fake.embedQuery("결제 취소 정책")
        val independentlyCreatedQuery = FakeEmbeddingPort(activeEmbeddingTarget = target)
            .embedQuery("결제 취소 정책")
        val document = fake.embedDocuments(listOf("결제 취소 정책")).single()

        assertThat(firstQuery.target).isSameAs(target)
        assertThat(firstQuery.embedding.values).isEqualTo(repeatedQuery.embedding.values)
        assertThat(firstQuery.embedding.values).isEqualTo(independentlyCreatedQuery.embedding.values)
        assertThat(firstQuery.embedding.values).hasSize(V2EmbeddingProfile.fixed.dimension)
        assertThat(firstQuery.embedding.values).allMatch { it.isFinite() }
        assertThat(l2Norm(firstQuery.embedding.values)).isCloseTo(1.0, offset(0.000001))
        assertThat(document.embedding.values).isNotEqualTo(firstQuery.embedding.values)
        assertThat(fake.requests.map(FakeEmbeddingRequest::mode))
            .containsExactly(FakeEmbeddingMode.QUERY, FakeEmbeddingMode.QUERY, FakeEmbeddingMode.DOCUMENT)
    }

    @Test
    fun `synthetic Korean fixture deterministically ranks the relevant passage above contrast passage`() {
        val fake = FakeEmbeddingPort()
        val fixture = KoreanRetrievalRankingFixture().also { it.installInto(fake) }
        val candidateDocuments = fixture.documentsInExpectedRankOrder.reversed()

        val query = fake.embedQuery(fixture.query)
        val rankedDocuments = candidateDocuments
            .zip(fake.embedDocuments(candidateDocuments))
            .sortedByDescending { (_, result) -> cosine(query.embedding.values, result.embedding.values) }
            .map { (document, _) -> document }

        assertThat(rankedDocuments).containsExactlyElementsOf(fixture.documentsInExpectedRankOrder)
    }

    @Test
    fun `supports provider lifecycle states plus mode-specific provider failures`() {
        val notConfigured = FakeEmbeddingPort(providerState = EmbeddingProviderState.NOT_CONFIGURED)

        assertThat(notConfigured.providerState).isEqualTo(EmbeddingProviderState.NOT_CONFIGURED)
        assertThrows<ProviderNotConfiguredException> { notConfigured.embedQuery("결제 취소 정책") }

        listOf(EmbeddingProviderState.INITIALIZING, EmbeddingProviderState.UNAVAILABLE).forEach { state ->
            val unavailableProvider = FakeEmbeddingPort(providerState = state)

            assertThat(unavailableProvider.providerState).isEqualTo(state)
            assertThrows<ProviderUnavailableException> { unavailableProvider.embedQuery("결제 취소 정책") }
        }

        val fake = FakeEmbeddingPort()
            .fail(FakeEmbeddingMode.QUERY, FakeEmbeddingFailure.RETRYABLE_UNAVAILABLE)

        assertThat(fake.providerState).isEqualTo(EmbeddingProviderState.READY)
        val unavailable = assertThrows<ProviderUnavailableException> { fake.embedQuery("결제 취소 정책") }
        assertThat(unavailable.retryable).isTrue()

        fake.clearFailure(FakeEmbeddingMode.QUERY)
            .fail(FakeEmbeddingMode.DOCUMENT, FakeEmbeddingFailure.CONTRACT_VIOLATION)
        val contractViolation = assertThrows<ProviderContractViolationException> {
            fake.embedDocuments(listOf("결제 취소 정책"))
        }
        assertThat(contractViolation.retryable).isFalse()
    }

    @Test
    fun `production embedding configuration does not automatically register the test fake`() {
        ApplicationContextRunner()
            .withUserConfiguration(EmbeddingProviderConfiguration::class.java)
            .withPropertyValues("p2a.embedding.provider=none")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(FakeEmbeddingPort::class.java)
                assertThat(context.getBeansOfType(EmbeddingPort::class.java).values)
                    .noneMatch { it is FakeEmbeddingPort }
            }
    }

    private fun l2Norm(values: List<Float>): Double =
        kotlin.math.sqrt(values.sumOf { value -> value.toDouble() * value.toDouble() })

    private fun cosine(left: List<Float>, right: List<Float>): Double =
        left.zip(right).sumOf { (leftValue, rightValue) -> leftValue.toDouble() * rightValue.toDouble() }
}
