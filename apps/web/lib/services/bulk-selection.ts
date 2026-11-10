import type { InboxListQuery, InboxRepository } from "./inbox"

export const MAX_BULK_SELECTION = 1000
// The API adapter uses pages of 50. Allow one extra page to detect an excess;
// even fresh cursors with empty/overlapping pages must terminate in finite work.
const MAX_SELECTION_PAGES = 21

export async function collectMatchingCaseIds(list: InboxRepository["list"], query: InboxListQuery): Promise<string[]> {
  const ids = new Set<string>()
  const cursors = new Set<string>()
  let cursor: string | undefined
  for (let pageNumber = 0; pageNumber < MAX_SELECTION_PAGES; pageNumber++) {
    const page = await list(cursor, query)
    for (const item of page.items) ids.add(item.id)
    if (ids.size > MAX_BULK_SELECTION) throw new Error("Zbyt wiele spraw. Zawęź filtry do 1000 spraw.")
    cursor = page.nextCursor
    if (!cursor) return [...ids]
    if (cursors.has(cursor)) throw new Error("Nie udało się wczytać zaznaczenia.")
    cursors.add(cursor)
  }
  throw new Error("Zbyt wiele spraw. Zawęź filtry do 1000 spraw.")
}
