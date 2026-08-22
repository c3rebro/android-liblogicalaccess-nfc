package de.shansen.rfproject

enum class RfValidationSeverity { INFO, WARNING, ERROR }

data class RfValidationIssue(
    val severity: RfValidationSeverity,
    val code: String,
    val message: String,
    val taskPosition: Int? = null
)

data class RfValidationReport(val issues: List<RfValidationIssue>) {
    val hasErrors: Boolean get() = issues.any { it.severity == RfValidationSeverity.ERROR }
}

data class RfExecutionPlan(val steps: List<RfExecutionStep>)

data class RfExecutionStep(
    val position: Int,
    val id: String,
    val modelType: String,
    val operation: String?,
    val description: String?,
    val condition: RfExecutionCondition?
)

data class RfExecutionCondition(
    val sourceTaskId: String,
    val expectedError: String
)

object RfProjectValidator {
    val knownModelTypes: Set<String> = setOf(
        "GenericChipTaskViewModel",
        "CommonTaskViewModel",
        "MifareClassicSetupViewModel",
        "MifareDesfireSetupViewModel",
        "MifareUltralightSetupViewModel"
    )

    fun validate(project: RfProject): RfValidationReport {
        val issues = mutableListOf<RfValidationIssue>()
        if (project.manifestVersion.isNullOrBlank()) {
            issues += RfValidationIssue(RfValidationSeverity.WARNING, "manifest.missing", "ManifestVersion is missing.")
        }
        if (project.tasks.isEmpty()) {
            issues += RfValidationIssue(RfValidationSeverity.WARNING, "tasks.empty", "TaskCollection is empty.")
        }

        val controls = project.tasks.map { it.control() }
        val positionsById = mutableMapOf<String, Int>()
        controls.forEachIndexed { position, control ->
            val existing = positionsById.putIfAbsent(control.id, position)
            if (existing != null) {
                issues += RfValidationIssue(
                    RfValidationSeverity.ERROR,
                    "task.id.duplicate",
                    "Task id '${control.id}' is used by positions $existing and $position.",
                    position
                )
            }
        }

        project.tasks.forEachIndexed { position, task ->
            val control = controls[position]
            if (task.typeName !in knownModelTypes) {
                issues += RfValidationIssue(
                    RfValidationSeverity.WARNING,
                    "task.type.unknown",
                    "Task model '${task.typeName}' is not mapped by the Android runtime yet.",
                    position
                )
            }

            if (control.taskType.isNullOrBlank() || control.taskType.equals("None", ignoreCase = true)) {
                issues += RfValidationIssue(
                    RfValidationSeverity.WARNING,
                    "task.operation.missing",
                    "Task '${control.id}' has no executable SelectedTaskType.",
                    position
                )
            }

            if (control.isConditional) {
                val sourceId = control.executeConditionTaskId
                if (sourceId.isNullOrBlank()) {
                    issues += RfValidationIssue(
                        RfValidationSeverity.ERROR,
                        "task.condition.source.missing",
                        "Conditional task '${control.id}' has no SelectedExecuteConditionTaskIndex.",
                        position
                    )
                } else {
                    val sourcePosition = positionsById[sourceId]
                    if (sourcePosition == null) {
                        issues += RfValidationIssue(
                            RfValidationSeverity.ERROR,
                            "task.condition.source.unknown",
                            "Conditional task '${control.id}' references unknown task id '$sourceId'.",
                            position
                        )
                    } else if (sourcePosition >= position) {
                        issues += RfValidationIssue(
                            RfValidationSeverity.WARNING,
                            "task.condition.forward-reference",
                            "Task '${control.id}' depends on task '$sourceId' at position $sourcePosition, which has not normally executed yet.",
                            position
                        )
                    }
                }
            }
        }

        return RfValidationReport(issues)
    }
}

object RfExecutionPlanCompiler {
    fun compile(project: RfProject): RfExecutionPlan {
        val report = RfProjectValidator.validate(project)
        if (report.hasErrors) {
            val summary = report.issues.filter { it.severity == RfValidationSeverity.ERROR }
                .joinToString("; ") { it.message }
            throw RfProjectFormatException("Project cannot be compiled into an execution plan: $summary")
        }

        return RfExecutionPlan(project.tasks.map { task ->
            val control = task.control()
            RfExecutionStep(
                position = task.position,
                id = control.id,
                modelType = task.typeName,
                operation = control.taskType,
                description = control.description,
                condition = if (control.isConditional && !control.executeConditionTaskId.isNullOrBlank()) {
                    RfExecutionCondition(control.executeConditionTaskId, control.executeConditionError)
                } else null
            )
        })
    }
}
