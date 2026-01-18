package com.ivor.kriptex.deliverypolicy.adversarial

import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSafetyContractTransientViolationTest {

    @Test
    fun healed_violation_still_fails_the_run_and_reports_earliest() {
        val runner = ConversationScenarioRunner(
            safetyContract = { stepIndex, stepLabel, actor, _, _, _, _, _, _ ->
                if (actor == ConversationScenario.Actor.A && stepIndex == 1) {
                    listOf(
                        ConversationSafetyContract.ViolationEvent(
                            contractName = "transient_rule",
                            stepIndex = stepIndex,
                            severity = ConversationSafetyContract.ViolationSeverity.ERROR,
                            details = "synthetic transient violation at $stepLabel",
                            relatedMessageIds = setOf("m1"),
                        ),
                    )
                } else {
                    emptyList()
                }
            },
        )

        val scenario = conversationScenario("transient_heals") {
            val c = "c_transient"
            sendOutbound(
                from = ConversationScenario.Actor.A,
                conversationId = c,
                peerId = "B",
                protocol = ConversationScenario.OutboundProtocol.User(
                    messageId = "m1",
                    createdAtElapsedMs = 1L,
                    payload = byteArrayOf(1),
                ),
            )
            // A second step ensures the violation is classified as healed.
            sendOutbound(
                from = ConversationScenario.Actor.A,
                conversationId = c,
                peerId = "B",
                protocol = ConversationScenario.OutboundProtocol.User(
                    messageId = "m2",
                    createdAtElapsedMs = 2L,
                    payload = byteArrayOf(2),
                ),
            )
        }

        val err = runCatching { runner.runScenario(scenario) }.exceptionOrNull()
        assertTrue("expected failure", err is AssertionError)
        val msg = (err as AssertionError).message.orEmpty()
        assertTrue(msg.contains("safety_contract_run_failed"))
        assertTrue(msg.contains("earliestViolation(actor=A contract=transient_rule stepIndex=1)"))
        assertTrue(msg.contains("contract=transient_rule"))
        assertTrue(msg.contains("healed=true"))
    }

    @Test
    fun multiple_violations_are_aggregated_not_overwritten() {
        val runner = ConversationScenarioRunner(
            safetyContract = { stepIndex, _, actor, _, _, _, _, _, _ ->
                if (actor != ConversationScenario.Actor.A) {
                    emptyList()
                } else {
                    when (stepIndex) {
                        1 -> listOf(
                            ConversationSafetyContract.ViolationEvent(
                                contractName = "rule_one",
                                stepIndex = 1,
                                severity = ConversationSafetyContract.ViolationSeverity.ERROR,
                                details = "first",
                            ),
                        )

                        2 -> listOf(
                            ConversationSafetyContract.ViolationEvent(
                                contractName = "rule_two",
                                stepIndex = 2,
                                severity = ConversationSafetyContract.ViolationSeverity.ERROR,
                                details = "second",
                            ),
                        )

                        else -> emptyList()
                    }
                }
            },
        )

        val scenario = conversationScenario("transient_multi") {
            val c = "c_multi"
            sendOutbound(from = ConversationScenario.Actor.A, conversationId = c, peerId = "B", protocol = ConversationScenario.OutboundProtocol.User("m1", 1L, byteArrayOf(1)))
            sendOutbound(from = ConversationScenario.Actor.A, conversationId = c, peerId = "B", protocol = ConversationScenario.OutboundProtocol.User("m2", 2L, byteArrayOf(2)))
        }

        val err = runCatching { runner.runScenario(scenario) }.exceptionOrNull()
        assertTrue("expected failure", err is AssertionError)
        val msg = (err as AssertionError).message.orEmpty()
        assertTrue(msg.contains("totalViolations=2"))
        assertTrue(msg.contains("earliestViolation(actor=A contract=rule_one stepIndex=1)"))
        assertTrue(msg.contains("contract=rule_one"))
        assertTrue(msg.contains("contract=rule_two"))
    }

    @Test
    fun restore_does_not_reset_violation_history() {
        val runner = ConversationScenarioRunner(
            safetyContract = { stepIndex, _, actor, _, _, _, _, _, _ ->
                if (actor == ConversationScenario.Actor.A && stepIndex == 1) {
                    listOf(
                        ConversationSafetyContract.ViolationEvent(
                            contractName = "rule_before_restore",
                            stepIndex = 1,
                            severity = ConversationSafetyContract.ViolationSeverity.ERROR,
                            details = "before restore",
                        ),
                    )
                } else {
                    emptyList()
                }
            },
        )

        val scenario = conversationScenario("transient_restore") {
            val c = "c_restore"
            sendOutbound(from = ConversationScenario.Actor.A, conversationId = c, peerId = "B", protocol = ConversationScenario.OutboundProtocol.User("m1", 1L, byteArrayOf(1)))
            snapshotAndRestore(
                actors = setOf(ConversationScenario.Actor.A),
                targets = setOf(
                    ConversationScenario.RestoreTarget.MESSAGE_STORE,
                    ConversationScenario.RestoreTarget.LEDGER,
                    ConversationScenario.RestoreTarget.OUTBOX,
                ),
            )
            sendOutbound(from = ConversationScenario.Actor.A, conversationId = c, peerId = "B", protocol = ConversationScenario.OutboundProtocol.User("m2", 2L, byteArrayOf(2)))
        }

        val err = runCatching { runner.runScenario(scenario) }.exceptionOrNull()
        assertTrue("expected failure", err is AssertionError)
        val msg = (err as AssertionError).message.orEmpty()
        assertTrue(msg.contains("earliestViolation(actor=A contract=rule_before_restore stepIndex=1)"))
    }
}
