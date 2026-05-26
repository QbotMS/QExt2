package com.qext2.primary.active

class ActiveMessageManager(private val logger: (String) -> Unit = {}) {

    private var current: ActiveMessage? = null
    private var suspended: ActiveMessage? = null
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
                    suspended = cur
                    logger("INTERRUPT suspended=${cur.id} by=${message.id} resumePolicy=${cur.resumePolicy}")
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
        val s = suspended ?: return null
        if (s.resumePolicy != ActiveMessageResumePolicy.RESUME_IF_STILL_VALID) {
            suspended = null
            return null
        }
        if (nowMs >= s.expiresAtMs) {
            logger("DROP id=${s.id} reason=expired_suspended")
            suspended = null
            return null
        }
        suspended = null
        current = s
        return s
    }

    fun clear() {
        synchronized(lock) {
            current = null
            suspended = null
        }
    }
}

sealed class ExpiryResult {
    data object None : ExpiryResult()
    data class Expired(val message: ActiveMessage) : ExpiryResult()
    data class Resumed(val message: ActiveMessage) : ExpiryResult()
}
