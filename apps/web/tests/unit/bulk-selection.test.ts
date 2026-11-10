import { describe, expect, it, vi } from "vitest"
import type { InboxCase } from "@/lib/domain/inbox"
import { collectMatchingCaseIds } from "@/lib/services/bulk-selection"

const item = (id: string) => ({ id }) as InboxCase
describe("explicit all-matching selection", () => {
  it("freezes distinct IDs across overlapping pages and preserves the active filters", async () => {
    const list = vi.fn().mockResolvedValueOnce({items:[item("a"),item("b")],nextCursor:"second"})
      .mockResolvedValueOnce({items:[item("b"),item("c")]})
    const filters = {mine:true,search:"source text",status:"new" as const}
    expect(await collectMatchingCaseIds(list, filters)).toEqual(["a","b","c"])
    expect(list.mock.calls).toEqual([[undefined,filters],["second",filters]])
  })
  it("rejects rather than truncates a result larger than 1000 IDs", async () => {
    const list = vi.fn().mockResolvedValue({items:Array.from({length:1001},(_,i)=>item(String(i)))})
    await expect(collectMatchingCaseIds(list, {})).rejects.toThrow("1000")
    expect(list).toHaveBeenCalledTimes(1)
  })
  it("bounds fresh empty/overlapping cursors as well as detecting a repeated cursor", async () => {
    let number = 0
    const endless = vi.fn().mockImplementation(async () => ({items:[item("same")],nextCursor:`cursor-${++number}`}))
    await expect(collectMatchingCaseIds(endless, {})).rejects.toThrow("1000")
    expect(endless).toHaveBeenCalledTimes(21)
    const repeated = vi.fn().mockResolvedValue({items:[],nextCursor:"same"})
    await expect(collectMatchingCaseIds(repeated, {})).rejects.toThrow("zaznaczenia")
    expect(repeated).toHaveBeenCalledTimes(2)
  })
})
