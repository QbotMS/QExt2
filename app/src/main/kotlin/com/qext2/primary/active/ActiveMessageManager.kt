package com.qext2.primary.active

class ActiveMessageManager(private val logger: (String) -> Unit = {}) {

    private var current: ActiveMessage? = null
    private val suspendedQueue = ArrayDeque<ActiveMessage>()
    private val lock = Any()

    fun show(message: ActiveMessage): Boolean {
        synchronized(lock) {
            val cur = current

            if (cur != null && cur.expiresAtMs > System.currentTimeMillis()) {
                if (message.priority.ordinal <= cur.priority.ordinal) {
                    logger("SHOW ignored id=${message.id} prio=${message.priority} active=${cur.id} active_prio=${cur.priority}")
                    return false
                }
                if (cur.resumePolicy != ActiveMessageResumePolicy.DROP_ON_INTERRUPT) {
                    suspendedQueue.addLast(cur)
                    logger("INTERRUPT suspended=${cur.id} by=${message.id} resumePolicy=${cur.resumePolicy} queueSize=${suspendedQueue.size}")
                } else {
                    logger("DROP dropped=${cur.id} by=${message.id}")
                }
            }

            current = message
            logger("SHOW id=${message.id} prio=${message.priority} severity=${message.severity}")
            return true
        }
    }

    fun getCurrent(nowMs: Long): ActiveMessage? {
        synchronized(lock) {
            val msg = current ?: return null
            if (nowMs >= msg.expiresAtMs) return null
            return msg
        }
    }

    fun hideExpired(nowMs: Long): ExpiryResult {
        synchronized(lock) {
            val msg = current
            if (msg != null && nowMs >= msg.expiresAtMs) {
                current = null
                val resumed = tryResume(nowMs)
                if (resumed != null) {
                    logger("RESUME id=${resumed.id}")
                    return ExpiryResult.Resumed(resumed)
                }
                logger("EXPIRE id=${msg.id}")
                return ExpiryResult.Expired(msg)
            }
            return ExpiryResult.None
        }
    }

    private fun tryResume(nowMs: Long): ActiveMessage? {
        suspendedQueue.removeAll { nowMs >= it.expiresAtMs }
        val candidate = suspendedQueue
            .filter { it.resumePolicy == ActiveMessageResumePolicy.RESUME_IF_STILL_VALID }
            .maxByOrNull { it.priority.ordinal }
        if (candidate != null) {
            suspendedQueue.remove(candidate)
            current = candidate
            return candidate
        }
        suspendedQueue.clear()
        return null
    }

    fun clear() {
        synchronized(lock) {
            current = null
            suspendedQueue.clear()
        }
    }
}

sealed class ExpiryResult {
    data object None : ExpiryResult()
    data class Expired(val message: ActiveMessage) : ExpiryResult()
    data class Resumed(val message: ActiveMessage) : ExpiryResult()
}
