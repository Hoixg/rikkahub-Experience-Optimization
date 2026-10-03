package me.rerere.rikkahub.service

import kotlinx.coroutines.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ScheduledSessionReservationTest {
    @Test fun userQueueAndPendingApprovalHavePriorityOverScheduledOwnership() = runBlocking {
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), this, {})
        val reservations = mutableSetOf<Uuid>()
        session.messageQueue.enqueue(listOf(UIMessagePart.Text("User first")))
        assertFalse(session.reserveForScheduledTask(reservations, emptySet()))
        session.messageQueue.takeNext()
        val approval = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool("call", "tool", "{}", approvalState = ToolApprovalState.Pending)))
        session.updateConversation(Conversation.ofId(id).updateCurrentMessages(listOf(approval)))
        assertFalse(session.reserveForScheduledTask(reservations, emptySet()))
        session.updateConversation(Conversation.ofId(id))
        assertFalse(session.reserveForScheduledTask(reservations, setOf(id)))
        assertTrue(session.reserveForScheduledTask(reservations, emptySet()))
        assertFalse(session.reserveForScheduledTask(reservations, emptySet()))
    }
    @Test fun reservingBusySessionDoesNotCancelItsExistingGeneration() = runBlocking {
        val id = Uuid.random()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val session = ConversationSession(id, Conversation.ofId(id), scope, {})
            val job = Job()
            session.setJob(job)
            assertFalse(session.reserveForScheduledTask(mutableSetOf(), emptySet()))
            assertTrue(job.isActive)
            job.cancel()
            session.cleanup()
        } finally { scope.cancel() }
    }
}
