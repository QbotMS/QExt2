package pl.qbot.karoo.core

data class FieldOutput(
    val name: String,
    val value: String,
    val color: FieldColor,
    val status: FieldStatus,
    val reason: String,
    val raw: Map<String, Any?> = emptyMap()
)
