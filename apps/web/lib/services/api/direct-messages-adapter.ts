import { createApiClient, type DirectConversation, type DirectMessage, type DirectRecipient } from "@usi/api-client/generated"
import type { DirectMessagesRepository } from "../direct-messages"
import { browserApiTransport } from "./http-transport"

const api = createApiClient(browserApiTransport)
const recipient = ({ id, displayName }: DirectRecipient) => ({ id, displayName })
const conversation = (item: DirectConversation) => ({
  id: item.id,
  recipient: recipient(item.recipient),
  recipientActive: item.recipientActive,
  updatedAt: item.updatedAt,
  unreadCount: item.unreadCount,
})
const message = ({ id, senderId, body, createdAt }: DirectMessage) => ({ id, senderId, body, createdAt })

export const apiDirectMessagesRepository: DirectMessagesRepository = {
  async recipients(search, cursor) {
    const page = await api.listDirectRecipients({ search, cursor })
    return { items: page.items.map(recipient), nextCursor: page.nextCursor ?? undefined }
  },
  async conversations(search, cursor) {
    const page = await api.listDirectConversations({ search, cursor })
    return { items: page.items.map(conversation), nextCursor: page.nextCursor ?? undefined }
  },
  async conversation(id) {
    return conversation(await api.getDirectConversation({ id }))
  },
  async start(recipientId) {
    return conversation(await api.startDirectConversation({ body: { recipientId } }))
  },
  async messages(id, cursor) {
    const page = await api.listDirectMessages({ id, cursor })
    return { items: page.items.map(message), nextCursor: page.nextCursor ?? undefined }
  },
  async send({ conversationId, body, idempotencyKey }) {
    await api.sendDirectMessage({ id: conversationId, "Idempotency-Key": idempotencyKey, body: { body } })
  },
  markRead(id, messageId) {
    return api.markDirectConversationRead({ id, body: { messageId } })
  },
}
