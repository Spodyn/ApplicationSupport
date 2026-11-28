"use client"

import { createContext, useContext, useState, useSyncExternalStore, type ReactNode } from "react"
import type { InboxSendInput } from "@/lib/services/inbox"

type Draft = {
  body: string; note: string; files: File[]; noteMode: boolean
  submission?: InboxSendInput; noteKey?: string; sending: boolean
}
const empty: Draft = { body: "", note: "", files: [], noteMode: false, sending: false }
function createStore() {
  const drafts = new Map<string, Draft>()
  const listeners = new Set<() => void>()
  return {
    get: (id: string) => drafts.get(id) ?? empty,
    subscribe(listener: () => void) { listeners.add(listener); return () => { listeners.delete(listener) } },
    update(id: string, patch: Partial<Draft>) {
      drafts.set(id, { ...(drafts.get(id) ?? empty), ...patch })
      listeners.forEach((listener) => listener())
    },
  }
}
const DraftContext = createContext<ReturnType<typeof createStore> | null>(null)

/** Memory only: survives navigation, never persists customer content or Files to browser storage.
 * Mounted for one authenticated account; sign-out/account changes discard the store. */
export function InboxDraftProvider({ children }: { children: ReactNode }) {
  const [store] = useState(createStore)
  return <DraftContext value={store}>{children}</DraftContext>
}

export function useInboxDraft(caseId: string) {
  const store = useContext(DraftContext)
  if (!store) throw new Error("InboxDraftProvider is required")
  const draft = useSyncExternalStore(store.subscribe, () => store.get(caseId), () => empty)
  return [draft, (patch: Partial<Draft>) => store.update(caseId, patch)] as const
}
