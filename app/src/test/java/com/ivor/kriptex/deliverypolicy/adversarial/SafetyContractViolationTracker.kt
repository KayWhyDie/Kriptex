package com.ivor.kriptex.deliverypolicy.adversarial

/**
 * Test-only lifecycle tracking for safety contract violations.
 *
 * Tracks per (actor, contractName):
 * - firstViolationStep
 * - violationCount
 * - healed (true if it stops violating by the final step)
 */
class SafetyContractViolationTracker {

    data class ContractState(
        val contractName: String,
        val firstViolationStep: Int,
        var violationCount: Int,
        var lastViolationStep: Int,
    ) {
        fun healed(finalStepIndex: Int): Boolean = lastViolationStep < finalStepIndex
    }

    data class ContractSummary(
        val contractName: String,
        val firstViolationStep: Int,
        val violationCount: Int,
        val healed: Boolean,
    )

    data class ActorSummary(
        val actor: ConversationScenario.Actor,
        val totalViolations: Int,
        val contracts: List<ContractSummary>,
    )

    data class RunSummary(
        val totalViolations: Int,
        val earliest: Earliest?,
        val byActor: List<ActorSummary>,
    ) {
        data class Earliest(
            val actor: ConversationScenario.Actor,
            val contractName: String,
            val stepIndex: Int,
        )
    }

    private val statesByActor: MutableMap<ConversationScenario.Actor, LinkedHashMap<String, ContractState>> = linkedMapOf(
        ConversationScenario.Actor.A to LinkedHashMap(),
        ConversationScenario.Actor.B to LinkedHashMap(),
    )

    private val totalViolationsByActor: MutableMap<ConversationScenario.Actor, Int> = linkedMapOf(
        ConversationScenario.Actor.A to 0,
        ConversationScenario.Actor.B to 0,
    )

    private var earliest: RunSummary.Earliest? = null

    fun record(actor: ConversationScenario.Actor, events: List<ConversationSafetyContract.ViolationEvent>) {
        if (events.isEmpty()) return

        val map = statesByActor.getValue(actor)
        var total = totalViolationsByActor.getValue(actor)

        for (e in events) {
            total++

            if (earliest == null || e.stepIndex < earliest!!.stepIndex) {
                earliest = RunSummary.Earliest(actor = actor, contractName = e.contractName, stepIndex = e.stepIndex)
            }

            val existing = map[e.contractName]
            if (existing == null) {
                map[e.contractName] = ContractState(
                    contractName = e.contractName,
                    firstViolationStep = e.stepIndex,
                    violationCount = 1,
                    lastViolationStep = e.stepIndex,
                )
            } else {
                existing.violationCount += 1
                if (e.stepIndex > existing.lastViolationStep) existing.lastViolationStep = e.stepIndex
            }
        }

        totalViolationsByActor[actor] = total
    }

    fun hasAnyViolations(): Boolean = totalViolationsByActor.values.any { it > 0 }

    fun buildSummary(finalStepIndex: Int): RunSummary {
        val actorSummaries = statesByActor.entries.map { (actor, contracts) ->
            val perContract = contracts.values
                .sortedWith(compareBy<ContractState> { it.firstViolationStep }.thenBy { it.contractName })
                .map {
                    ContractSummary(
                        contractName = it.contractName,
                        firstViolationStep = it.firstViolationStep,
                        violationCount = it.violationCount,
                        healed = it.healed(finalStepIndex),
                    )
                }

            ActorSummary(
                actor = actor,
                totalViolations = totalViolationsByActor.getValue(actor),
                contracts = perContract,
            )
        }

        return RunSummary(
            totalViolations = actorSummaries.sumOf { it.totalViolations },
            earliest = earliest,
            byActor = actorSummaries,
        )
    }
}
