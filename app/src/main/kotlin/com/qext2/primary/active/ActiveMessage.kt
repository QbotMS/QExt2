package com.qext2.primary.active

data class ActiveMessage(
    val id: String,
    val title: String,
    val line1: String,
    val line2: String?,
    val severity: ActiveMessageSeverity,
    val priority: ActiveMessagePriority = ActiveMessagePriority.INFO,
    val resumePolicy: ActiveMessageResumePolicy = ActiveMessageResumePolicy.DROP_ON_INTERRUPT,
    val createdAtMs: Long,
    val expiresAtMs: Long,
)

enum class ActiveMessageSeverity {
    INFO, WARNING, CRITICAL
}

enum class ActiveMessagePriority {
    INFO_LOW, INFO, WARNING, CRITICAL
}

enum class ActiveMessageResumePolicy {
    DROP_ON_INTERRUPT,
    RESUME_IF_STILL_VALID,
    STICKY_UNTIL_ACK,
}
