import { beforeEach, describe, expect, it, vi } from "vitest"
import { browserApiTransport } from "@/lib/services/api/http-transport"

describe("browser API transport", () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    document.cookie = "XSRF-TOKEN=e2e-csrf; Path=/"
  })

  it("passes FormData through without forcing a JSON content type", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({
        attachmentId: "018f0000-0000-7000-8000-000000000096",
        filename: "evidence.txt",
        contentType: "text/plain",
        sizeBytes: 4,
        scanStatus: "CLEAN",
        scanError: null,
      }), {
        status: 201,
        headers: { "content-type": "application/json" },
      }),
    )
    vi.stubGlobal("fetch", fetchMock)
    const body = new FormData()
    body.append("file", new File(["test"], "evidence.txt", { type: "text/plain" }))

    await browserApiTransport.request({
      method: "POST",
      path: "/api/v1/cases/018f0000-0000-7000-8000-000000000001/attachments",
      body,
    })

    const init = fetchMock.mock.calls[0]?.[1] as RequestInit
    const headers = new Headers(init.headers)
    expect(headers.get("Content-Type")).toBeNull()
    expect(headers.get("X-XSRF-TOKEN")).toBe("e2e-csrf")
    expect(init.body).toBe(body)
  })

  it("forwards generated command headers alongside transport-managed headers", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ messageId: "018f0000-0000-7000-8000-000000000095", deliveryStatus: "QUEUED" }), {
        status: 202,
        headers: { "content-type": "application/json" },
      }),
    )
    vi.stubGlobal("fetch", fetchMock)

    await browserApiTransport.request({
      method: "POST",
      path: "/api/v1/cases/018f0000-0000-7000-8000-000000000001/messages",
      headers: { "Idempotency-Key": "send-95" },
      body: { body: "Hello from support", bodyFormat: "PLAIN_TEXT" },
    })

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const init = fetchMock.mock.calls[0]?.[1] as RequestInit
    const headers = new Headers(init.headers)
    expect(headers.get("Idempotency-Key")).toBe("send-95")
    expect(headers.get("X-XSRF-TOKEN")).toBe("e2e-csrf")
    expect(headers.get("Content-Type")).toBe("application/json")
    expect(headers.get("Accept")).toBe("application/json")
  })
})
