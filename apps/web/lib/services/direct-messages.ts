export type DirectRecipient = { id: string; displayName: string }
export type DirectConversation = {
  id: string
  recipient: DirectRecipient
  recipientActive: boolean
  updatedAt: string
  unreadCount: number
}
export type DirectMessage = { id: string; senderId: string; body: string; createdAt: string }
export type DirectPage<T> = { items: T[]; nextCursor?: string }
export type DirectSendInput = { conversationId: string; body: string; idempotencyKey: string }

export interface DirectMessagesRepository {
  recipients(search: string, cursor?: string): Promise<DirectPage<DirectRecipient>>
  conversations(search: string, cursor?: string): Promise<DirectPage<DirectConversation>>
  conversation(id: string): Promise<DirectConversation>
  start(recipientId: string): Promise<DirectConversation>
  messages(id: string, cursor?: string): Promise<DirectPage<DirectMessage>>
  send(input: DirectSendInput): Promise<void>
  markRead(id: string, messageId: string): Promise<void>
}
