package de.shansen.rfproject

enum class RfProjectContainer {
    XML,
    RFPRJ_ARCHIVE
}

data class RfProject(
    val manifestVersion: String?,
    val tasks: List<RfProjectTask>,
    val container: RfProjectContainer,
    val rootElement: String,
    val sourceName: String? = null
)

data class RfProjectTask(
    val position: Int,
    val elementName: String,
    val typeName: String,
    val node: RfProjectNode
) {
    fun field(name: String): RfProjectNode? = node.children.firstOrNull { it.name == name }

    fun text(name: String): String? = field(name)?.text?.trim()?.takeIf { it.isNotEmpty() }

    fun control(): RfTaskControl = RfTaskControl(
        id = text("CurrentTaskIndex") ?: position.toString(),
        description = text("SelectedTaskDescription"),
        taskType = text("SelectedTaskType"),
        executeConditionTaskId = text("SelectedExecuteConditionTaskIndex"),
        executeConditionError = text("SelectedExecuteConditionErrorLevel") ?: "Empty"
    )
}

data class RfTaskControl(
    val id: String,
    val description: String?,
    val taskType: String?,
    val executeConditionTaskId: String?,
    val executeConditionError: String
) {
    val isConditional: Boolean
        get() = !executeConditionError.equals("Empty", ignoreCase = true)
}

data class RfProjectNode(
    val name: String,
    val qualifiedName: String,
    val namespaceUri: String?,
    val attributes: List<RfProjectAttribute>,
    val children: List<RfProjectNode>,
    val text: String?
) {
    fun child(name: String): RfProjectNode? = children.firstOrNull { it.name == name }
}

data class RfProjectAttribute(
    val name: String,
    val qualifiedName: String,
    val namespaceUri: String?,
    val value: String
)

class RfProjectFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
