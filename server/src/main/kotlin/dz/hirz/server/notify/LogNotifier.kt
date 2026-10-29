package dz.hirz.server.notify

import org.slf4j.LoggerFactory

/** End-to-end CI only: pushes are logged (kind only, never content), not sent. */
object LogNotifier : Notifier {
    private val log = LoggerFactory.getLogger(LogNotifier::class.java)

    override suspend fun send(to: PushTarget, message: AlertMessage): SendResult {
        log.info("push {} to member {}", message.kind, to.memberId)
        return SendResult.Ok("log")
    }
}
