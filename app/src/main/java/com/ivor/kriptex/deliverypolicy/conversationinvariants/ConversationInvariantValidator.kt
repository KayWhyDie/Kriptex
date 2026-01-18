package com.ivor.kriptex.deliverypolicy.conversationinvariants

import com.ivor.kriptex.deliverypolicy.conversationfacade.ConversationView

enum class ConversationInvariantSeverity {
    ERROR,
    WARNING,
}

data class ConversationInvariantViolation(
    val id: String,
    val conversationId: String,
    val severity: ConversationInvariantSeverity,
    /** Human-readable description; must not include payloads/keys/identities. */
    val message: String,
    /** Small, non-sensitive key/value context (enum names, counts, etc). */
    val details: Map<String, String> = emptyMap(),
) {
    companion object {
        fun error(
            id: String,
            conversationId: String,
            message: String,
            details: Map<String, String> = emptyMap(),
        ): ConversationInvariantViolation {
            return ConversationInvariantViolation(
                id = id,
                conversationId = conversationId,
                severity = ConversationInvariantSeverity.ERROR,
                message = message,
                details = details,
            )
        }

        fun warning(
            id: String,
            conversationId: String,
            message: String,
            details: Map<String, String> = emptyMap(),
        ): ConversationInvariantViolation {
            return ConversationInvariantViolation(
                id = id,
                conversationId = conversationId,
                severity = ConversationInvariantSeverity.WARNING,
                message = message,
                details = details,
            )
        }
    }
}

/**
 * Stateless validator for invariants over [ConversationView].
 */
class ConversationInvariantValidator {

    fun validate(view: ConversationView): List<ConversationInvariantViolation> {
        return ConversationInvariants.check(view)
    }

    fun validateTransition(previous: ConversationView, next: ConversationView): List<ConversationInvariantViolation> {
        val out = ArrayList<ConversationInvariantViolation>()
        out.addAll(ConversationInvariants.check(next))
        out.addAll(ConversationInvariants.checkTransition(previous, next))
        return out
    }

    fun validateSequence(views: List<ConversationView>): List<ConversationInvariantViolation> {
        if (views.isEmpty()) return emptyList()
        val out = ArrayList<ConversationInvariantViolation>()
        out.addAll(validate(views.first()))
        for (i in 1 until views.size) {
            out.addAll(validateTransition(views[i - 1], views[i]))
        }
        return out
    }
}
