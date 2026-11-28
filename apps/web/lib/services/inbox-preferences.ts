export type InboxMode = "unified" | "separate_by_platform"
export interface InboxPreferencesRepository {
  get(): Promise<InboxMode>
  save(mode: InboxMode): Promise<InboxMode>
}
