package de.shansen.rfexecution

import de.shansen.rfproject.RfExecutionCondition
import de.shansen.rfproject.RfExecutionPlan
import de.shansen.rfproject.RfExecutionStep
import kotlin.test.Test
import kotlin.test.assertEquals

class RfExecutionEngineTest {
    @Test
    fun executesInCollectionOrderAndUsesStableIdsForConditions() {
        val plan = RfExecutionPlan(
            listOf(
                step(position = 0, id = "100", operation = "AuthenticateApplication"),
                step(
                    position = 1,
                    id = "7",
                    operation = "WriteData",
                    condition = RfExecutionCondition("100", "AuthFailure")
                ),
                step(position = 2, id = "42", operation = "ReadData")
            )
        )

        val run = RfExecutionEngine().execute(
            plan,
            RfDryRunExecutor(resultByTaskId = mapOf("100" to "AuthFailure"))
        )

        assertEquals(listOf("100", "7", "42"), run.outcomes.map { it.step.id })
        assertEquals(RfStepDisposition.EXECUTED, run.outcomes[0].disposition)
        assertEquals(RfStepDisposition.EXECUTED, run.outcomes[1].disposition)
        assertEquals(RfStepDisposition.EXECUTED, run.outcomes[2].disposition)
    }

    @Test
    fun skipsConditionalTaskWhenErrorDoesNotMatch() {
        val plan = RfExecutionPlan(
            listOf(
                step(position = 0, id = "A", operation = "AuthenticateApplication"),
                step(
                    position = 1,
                    id = "B",
                    operation = "WriteData",
                    condition = RfExecutionCondition("A", "AuthFailure")
                )
            )
        )

        val run = RfExecutionEngine().execute(plan, RfDryRunExecutor(defaultError = "NoError"))

        assertEquals(RfStepDisposition.EXECUTED, run.outcomes[0].disposition)
        assertEquals(RfStepDisposition.SKIPPED, run.outcomes[1].disposition)
    }

    @Test
    fun skipsConditionWhoseSourceHasNoResult() {
        val plan = RfExecutionPlan(
            listOf(
                step(
                    position = 0,
                    id = "B",
                    operation = "WriteData",
                    condition = RfExecutionCondition("A", "AuthFailure")
                )
            )
        )

        val run = RfExecutionEngine().execute(plan, RfDryRunExecutor())
        assertEquals(RfStepDisposition.SKIPPED, run.outcomes.single().disposition)
    }

    private fun step(
        position: Int,
        id: String,
        operation: String,
        condition: RfExecutionCondition? = null
    ) = RfExecutionStep(
        position = position,
        id = id,
        modelType = "MifareDesfireSetupViewModel",
        operation = operation,
        description = null,
        condition = condition
    )
}
