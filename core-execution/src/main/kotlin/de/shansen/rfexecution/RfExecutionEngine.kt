package de.shansen.rfexecution

import de.shansen.rfproject.RfExecutionPlan
import de.shansen.rfproject.RfExecutionStep

enum class RfStepDisposition {
    EXECUTED,
    SKIPPED
}

data class RfTaskOperationResult(
    val error: String,
    val message: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

data class RfStepOutcome(
    val step: RfExecutionStep,
    val disposition: RfStepDisposition,
    val result: RfTaskOperationResult? = null,
    val reason: String? = null
)

data class RfExecutionRun(val outcomes: List<RfStepOutcome>) {
    val resultsByTaskId: Map<String, RfTaskOperationResult> = outcomes
        .filter { it.disposition == RfStepDisposition.EXECUTED && it.result != null }
        .associate { it.step.id to requireNotNull(it.result) }
}

fun interface RfTaskExecutor {
    fun execute(step: RfExecutionStep): RfTaskOperationResult
}

/**
 * Executes an RFIDGear plan in TaskCollection order.
 *
 * CurrentTaskIndex is treated as the stable task ID, not as a collection index.
 * A conditional step executes only when the already-executed source task returned
 * exactly the configured RFIDGear error name.
 */
class RfExecutionEngine {
    fun execute(plan: RfExecutionPlan, executor: RfTaskExecutor): RfExecutionRun {
        val outcomes = mutableListOf<RfStepOutcome>()
        val results = mutableMapOf<String, RfTaskOperationResult>()

        for (step in plan.steps) {
            val condition = step.condition
            if (condition != null) {
                val sourceResult = results[condition.sourceTaskId]
                if (sourceResult == null) {
                    outcomes += RfStepOutcome(
                        step = step,
                        disposition = RfStepDisposition.SKIPPED,
                        reason = "Condition source task '${condition.sourceTaskId}' has no execution result."
                    )
                    continue
                }

                if (sourceResult.error != condition.expectedError) {
                    outcomes += RfStepOutcome(
                        step = step,
                        disposition = RfStepDisposition.SKIPPED,
                        reason = "Condition not met: ${condition.sourceTaskId} returned ${sourceResult.error}, expected ${condition.expectedError}."
                    )
                    continue
                }
            }

            val result = executor.execute(step)
            results[step.id] = result
            outcomes += RfStepOutcome(
                step = step,
                disposition = RfStepDisposition.EXECUTED,
                result = result
            )
        }

        return RfExecutionRun(outcomes)
    }
}

/**
 * Non-hardware executor used by UI preview/tests. No project payload or key material
 * is copied into the result; only the configured synthetic error per task ID is returned.
 */
class RfDryRunExecutor(
    private val resultByTaskId: Map<String, String> = emptyMap(),
    private val defaultError: String = "NoError"
) : RfTaskExecutor {
    override fun execute(step: RfExecutionStep): RfTaskOperationResult =
        RfTaskOperationResult(error = resultByTaskId[step.id] ?: defaultError)
}
