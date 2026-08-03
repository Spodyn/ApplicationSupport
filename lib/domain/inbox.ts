import type {
  Channel,
  MessageDeliveryStatus,
  SlaState,
  User,
} from "@/lib/domain/types"

export type InboxStatus =
  | "new"
  | "verification"
  | "waiting_for_customer"
  | "partially_ignored"
  | "ignored"
  | "resolved"

export interface InboxCustomer {
  id: string
  name: string
  contactName: string
}

export interface InboxActivity {
  id: string
  label: string
  createdAt: string
  author?: string
}

export interface InboxCase {
  id: string
  reference: string
  subject: string
  platform: Channel
  sourceChannel: string
  customer: InboxCustomer
  status: InboxStatus
  owner?: User
  unreadForCurrentUser: boolean
  snoozedForCurrentUserUntil?: string
  currentUserRestrictedByIgnore: boolean
  lastMessagePreview: string
  createdAt: string
  updatedAt: string
  sla: {
    state: SlaState
    dueAt?: string
  }
  ignoreVotes: {
    current: number
    required: 2
    voters: string[]
  }
  metadata: {
    priority: "Niski" | "Średni" | "Wysoki" | "Krytyczny"
    category: string
    product: string
    environment: string
    tags: string[]
  }
  relatedCase?: {
    reference: string
    subject: string
  }
  waitingUntil?: string
  resolutionCategory?: string
  activity: InboxActivity[]
}

export interface InboxAttachment {
  id: string
  fileName: string
  size: string
  type: "image" | "document" | "archive"
}

export interface InboxMessage {
  id: string
  kind: "customer" | "support" | "system"
  sender?: string
  body: string
  createdAt: string
  edited?: boolean
  attachments?: InboxAttachment[]
  codeBlock?: {
    language: string
    content: string
  }
  deliveryStatus?: MessageDeliveryStatus
}

export const inboxStatusLabels: Record<InboxStatus, string> = {
  new: "Nowe",
  verification: "W trakcie weryfikacji",
  waiting_for_customer: "Dopytane",
  partially_ignored: "Częściowo zignorowane",
  ignored: "Zignorowane",
  resolved: "Rozwiązane",
}
