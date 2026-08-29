import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import {
  RealtimeStompClient,
  createRealtimeWebSocketUrl,
  type RealtimeStompClientOptions,
} from "@/lib/realtime/stomp-client"

class FakeWebSocket {
  static instances: FakeWebSocket[] = []

  readonly url: string
  readyState = 0
  sent: string[] = []
  closedWith: { code?: number; reason?: string } | null = null
  onopen: ((event: Event) => void) | null = null
  onmessage: ((event: MessageEvent) => void) | null = null
  onclose: ((event: CloseEvent) => void) | null = null
  onerror: ((event: Event) => void) | null = null

  constructor(url: string) {
    this.url = url
    FakeWebSocket.instances.push(this)
  }

  open() {
    this.readyState = 1
    this.onopen?.(new Event("open"))
  }

  message(data: string) {
    this.onmessage?.(new MessageEvent("message", { data }))
  }

  close(code?: number, reason?: string) {
    this.closedWith = { code, reason }
    this.readyState = 3
    this.onclose?.(new CloseEvent("close", { code: code ?? 1000, reason: reason ?? "" }))
  }

  send(data: string) {
    this.sent.push(data)
  }
}

function createClient(options: RealtimeStompClientOptions = {}) {
  return new RealtimeStompClient({
    location: { protocol: "https:", host: "support.example" },
    webSocketFactory: (url) => new FakeWebSocket(url),
    ...options,
  })
}

describe("RealtimeStompClient", () => {
  beforeEach(() => {
    FakeWebSocket.instances = []
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it("builds a same-origin websocket URL without query credentials", () => {
    expect(createRealtimeWebSocketUrl({ protocol: "https:", host: "support.example" }))
      .toBe("wss://support.example/ws")
    expect(createRealtimeWebSocketUrl({ protocol: "http:", host: "localhost:3100" }))
      .toBe("ws://localhost:3100/ws")
  })

  it("uses STOMP 1.2 heartbeat negotiation without login, passcode or tokens", () => {
    const client = createClient({ heartbeatOutgoingMs: 10_000, heartbeatIncomingMs: 10_000 })
    client.start()

    const socket = FakeWebSocket.instances[0]
    expect(socket.url).toBe("wss://support.example/ws")
    socket.open()

    expect(socket.sent).toHaveLength(1)
    expect(socket.sent[0])
      .toContain("CONNECT\naccept-version:1.2\nheart-beat:10000,10000\n\n\u0000")
    expect(socket.sent[0]).not.toMatch(/login|passcode|token/i)

    socket.message("CONNECTED\nversion:1.2\nheart-beat:10000,10000\n\n\u0000")
    expect(client.getState()).toBe("connected")

    client.stop()
  })

  it("closes on heartbeat timeout and reconnects without blocking the client", () => {
    const states: string[] = []
    const client = createClient({
      heartbeatOutgoingMs: 0,
      heartbeatIncomingMs: 100,
      reconnectDelayMs: 250,
    })
    client.subscribe((state) => states.push(state))
    client.start()

    const first = FakeWebSocket.instances[0]
    first.open()
    first.message("CONNECTED\nversion:1.2\nheart-beat:100,0\n\n\u0000")
    expect(client.getState()).toBe("connected")

    vi.advanceTimersByTime(401)
    expect(first.closedWith?.code).toBe(4000)
    expect(client.getState()).toBe("disconnected")

    vi.advanceTimersByTime(250)
    expect(FakeWebSocket.instances).toHaveLength(2)
    expect(client.getState()).toBe("connecting")
    expect(states).toContain("disconnected")

    client.stop()
  })
})
