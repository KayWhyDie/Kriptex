package com.ivor.kriptex.deliverypolicy.adversarial.fuzzer

import org.junit.Assert.fail
import org.junit.Test

class DeterministicProtocolFuzzerTest {

    @Test
    fun bounded_deterministic_protocol_fuzzer_finds_no_invariant_violations() {
        val config = DeterministicProtocolFuzzer.Config(
            maxLen = 7,
            maxSequences = 8_000,
            maxMillis = 12_000,
            reorderWindowSizes = listOf(2, 3),
            reorderAcrossCutWindowSizes = listOf(2, 3),
            maxCutDepth = 2,
            maxConsecutiveDuplicates = 2,
        )

        var checked = 0
        for (seq in DeterministicProtocolFuzzer.enumerate(config)) {
            checked++
            try {
                DeterministicProtocolFuzzer.run(seq, config, sequenceName = "fuzz_$checked")
            } catch (e: AssertionError) {
                val kind = DeterministicProtocolFuzzer.classifyFailure(e.message)
                val minimized = DeterministicProtocolFuzzer.minimizeFailing(
                    actions = seq,
                    failureKind = kind,
                    failurePredicate = { candidate ->
                        try {
                            DeterministicProtocolFuzzer.run(candidate, config, sequenceName = "min")
                            false
                        } catch (_: AssertionError) {
                            true
                        }
                    },
                )

                val msg = buildString {
                    appendLine("Deterministic fuzzer found a failing sequence.")
                    appendLine("Checked sequences: $checked")
                    appendLine()
                    appendLine("MINIMIZED ACTION SEQUENCE (${minimized.size}):")
                    minimized.forEachIndexed { i, a -> appendLine("  ${i + 1}. ${format(a)}") }
                    appendLine()
                    appendLine("REGRESSION SNIPPET:")
                    appendLine(regressionSnippet(minimized, testName = "regression_fuzz_${minimized.size}_steps"))
                    appendLine()
                    appendLine("ORIGINAL FAILURE:")
                    appendLine(e.message)
                }

                fail(msg)
            }
        }
    }

    private fun format(a: DeterministicProtocolFuzzer.Action): String {
        return when (a) {
            DeterministicProtocolFuzzer.Action.SEND_OUTBOUND -> "SEND_OUTBOUND"
            DeterministicProtocolFuzzer.Action.RECEIVE_INBOUND -> "RECEIVE_INBOUND"
            DeterministicProtocolFuzzer.Action.ACK -> "ACK"
            DeterministicProtocolFuzzer.Action.DUPLICATE_LAST -> "DUPLICATE_LAST"
            DeterministicProtocolFuzzer.Action.DROP_LAST -> "DROP_LAST"
            is DeterministicProtocolFuzzer.Action.REORDER_WINDOW -> "REORDER_WINDOW(${a.size})"
            DeterministicProtocolFuzzer.Action.CUT_START -> "CUT_START"
            DeterministicProtocolFuzzer.Action.CUT_END -> "CUT_END"
            is DeterministicProtocolFuzzer.Action.REORDER_ACROSS_CUTS -> "REORDER_ACROSS_CUTS(${a.windowSize})"
            DeterministicProtocolFuzzer.Action.SNAPSHOT_RESTORE -> "SNAPSHOT_RESTORE"
            DeterministicProtocolFuzzer.Action.VISIBILITY_TOGGLE -> "VISIBILITY_TOGGLE"
            DeterministicProtocolFuzzer.Action.DELIVERY_MODE_TOGGLE -> "DELIVERY_MODE_TOGGLE"
        }
    }

    private fun regressionSnippet(actions: List<DeterministicProtocolFuzzer.Action>, testName: String): String {
        val rendered = actions.joinToString(",\n") { a ->
            when (a) {
                DeterministicProtocolFuzzer.Action.SEND_OUTBOUND -> "DeterministicProtocolFuzzer.Action.SEND_OUTBOUND"
                DeterministicProtocolFuzzer.Action.RECEIVE_INBOUND -> "DeterministicProtocolFuzzer.Action.RECEIVE_INBOUND"
                DeterministicProtocolFuzzer.Action.ACK -> "DeterministicProtocolFuzzer.Action.ACK"
                DeterministicProtocolFuzzer.Action.DUPLICATE_LAST -> "DeterministicProtocolFuzzer.Action.DUPLICATE_LAST"
                DeterministicProtocolFuzzer.Action.DROP_LAST -> "DeterministicProtocolFuzzer.Action.DROP_LAST"
                is DeterministicProtocolFuzzer.Action.REORDER_WINDOW -> "DeterministicProtocolFuzzer.Action.REORDER_WINDOW(${a.size})"
                DeterministicProtocolFuzzer.Action.CUT_START -> "DeterministicProtocolFuzzer.Action.CUT_START"
                DeterministicProtocolFuzzer.Action.CUT_END -> "DeterministicProtocolFuzzer.Action.CUT_END"
                is DeterministicProtocolFuzzer.Action.REORDER_ACROSS_CUTS -> "DeterministicProtocolFuzzer.Action.REORDER_ACROSS_CUTS(${a.windowSize})"
                DeterministicProtocolFuzzer.Action.SNAPSHOT_RESTORE -> "DeterministicProtocolFuzzer.Action.SNAPSHOT_RESTORE"
                DeterministicProtocolFuzzer.Action.VISIBILITY_TOGGLE -> "DeterministicProtocolFuzzer.Action.VISIBILITY_TOGGLE"
                DeterministicProtocolFuzzer.Action.DELIVERY_MODE_TOGGLE -> "DeterministicProtocolFuzzer.Action.DELIVERY_MODE_TOGGLE"
            }
        }

        return """
@Test
fun $testName() {
    DeterministicProtocolFuzzer.run(
        actions = listOf(
            $rendered
        ),
        config = DeterministicProtocolFuzzer.Config(maxLen = ${actions.size})
    )
}
""".trim()
    }
}
