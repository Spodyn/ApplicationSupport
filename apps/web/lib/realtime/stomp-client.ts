export type RealtimeConnectionState = "connecting" | "connected" | "disconnected"

export interface RealtimeLocation {
  protocol: string
  host: string
}

interface WebSocketLike {
  readonly readyState: number
  onopen: ((event: Event) => void) | null
  onmessage: ((event: MessageEvent) => void) | null
  onclose: ((event: CloseEvent) => void) | null
  onerror: ((event: Event) => void) | null
  send(data: string): void
  close(code?: number, reason?: string): void
}

export interface RealtimeStompClientOptions {
  location?: RealtimeLocation
  webSocketFactory?: (url: string) => WebSocketLike
  reconnectDelayMs?: number
  heartbeatOutgoingMs?: number
  heartbeatIncomingMs?: number
}

type StateListener = (state: RealtimeConnectionState) => void
export type RealtimeFrameListener = (destination: string, body: unknown) => void

type DestinationSubscription = {
  id: string
  references: number
}

const WEBSOCKET_OPEN = 1
const HEARTBEAT_GRACE_MULTIPLIER = 3

export function createRealtimeWebSocketUrl(location: RealtimeLocation): string {
  const protocol = location.protocol === "https:" ? "wss:" : "ws:"
  return `${protocol}//${location.host}/ws`
}

export class RealtimeStompClient {
  private readonly options: Required<
    Pick<
      RealtimeStompClientOptions,
      "reconnectDelayMs" | "heartbeatOutgoingMs" | "heartbeatIncomingMs"
    >
  > & RealtimeStompClientOptions

  private readonly listeners = new Set<StateListener>()
  private readonly frameListeners = new Set<RealtimeFrameListener>()
  private readonly destinationSubscriptions = new Map<string, DestinationSubscription>()
  private socket: WebSocketLike | null = null
  private state: RealtimeConnectionState = "disconnected"
  private shouldRun = false
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private outgoingHeartbeat: ReturnType<typeof setInterval> | null = null
  private incomingWatchdog: ReturnType<typeof setInterval> | null = null
  private lastServerActivity = 0
  private frameBuffer = ""
  private nextSubscriptionId = 1

  constructor(options: RealtimeStompClientOptions = {}) {
    this.options = {
      ...options,
      reconnectDelayMs: options.reconnectDelayMs ?? 3_000,
      heartbeatOutgoingMs: options.heartbeatOutgoingMs ?? 10_000,
      heartbeatIncomingMs: options.heartbeatIncomingMs ?? 10_000,
    }
  }

  getState(): RealtimeConnectionState {
    return this.state
  }

  subscribe(listener: StateListener): () => void {
    this.listeners.add(listener)
    listener(this.state)
    return () => this.listeners.delete(listener)
  }

  subscribeFrames(listener: RealtimeFrameListener): () => void {
    this.frameListeners.add(listener)
    return () => this.frameListeners.delete(listener)
  }

  subscribeDestination(destination: string): () => void {
    const normalized = normalizeDestination(destination)
    const existing = this.destinationSubscriptions.get(normalized)
    if (existing) {
      existing.references += 1
    } else {
      const subscription = {
        id: `usi-sub-${this.nextSubscriptionId++}`,
        references: 1,
      }
      this.destinationSubscriptions.set(normalized, subscription)
      if (this.state === "connected" && this.socket?.readyState === WEBSOCKET_OPEN) {
        this.sendSubscribe(this.socket, normalized, subscription.id)
      }
    }

    let active = true
    return () => {
      if (!active) return
      active = false
      const current = this.destinationSubscriptions.get(normalized)
      if (!current) return
      current.references -= 1
      if (current.references > 0) return
      this.destinationSubscriptions.delete(normalized)
      if (this.state === "connected" && this.socket?.readyState === WEBSOCKET_OPEN) {
        this.sendUnsubscribe(this.socket, current.id)
      }
    }
  }

  start(): void {
    if (this.shouldRun) return
    this.shouldRun = true
    this.connect()
  }

  stop(): void {
    this.shouldRun = false
    this.clearReconnect()
    this.clearHeartbeats()
    const socket = this.socket
    this.socket = null
    if (socket) {
      try {
        socket.close(1000, "Realtime provider stopped")
      } catch {
        // The transport may already be closed.
      }
    }
    this.setState("disconnected")
  }

  private connect(): void {
    if (!this.shouldRun || this.socket) return
    this.clearReconnect()
    this.setState("connecting")

    try {
      const location = this.options.location ?? window.location
      const factory =
        this.options.webSocketFactory ??
        ((url: string): WebSocketLike => new WebSocket(url))
      const socket = factory(createRealtimeWebSocketUrl(location))
      this.socket = socket
      this.frameBuffer = ""

      socket.onopen = () => {
        if (this.socket !== socket) return
        socket.send(
          `CONNECT\naccept-version:1.2\nheart-beat:${this.options.heartbeatOutgoingMs},${this.options.heartbeatIncomingMs}\n\n\u0000`,
        )
      }
      socket.onmessage = (event) => this.handleMessage(socket, event.data)
      socket.onerror = () => this.handleDisconnect(socket)
      socket.onclose = () => this.handleDisconnect(socket)
    } catch {
      this.handleConnectFailure()
    }
  }

  private handleMessage(socket: WebSocketLike, data: unknown): void {
    if (this.socket !== socket || typeof data !== "string") return
    this.lastServerActivity = Date.now()
    this.frameBuffer += data

    while (this.frameBuffer.startsWith("\n") || this.frameBuffer.startsWith("\r")) {
      this.frameBuffer = this.frameBuffer.slice(1)
    }

    let frameEnd = this.frameBuffer.indexOf("\u0000")
    while (frameEnd >= 0) {
      const frame = this.frameBuffer.slice(0, frameEnd)
      this.frameBuffer = this.frameBuffer.slice(frameEnd + 1)
      this.handleFrame(socket, frame)
      while (this.frameBuffer.startsWith("\n") || this.frameBuffer.startsWith("\r")) {
        this.frameBuffer = this.frameBuffer.slice(1)
      }
      frameEnd = this.frameBuffer.indexOf("\u0000")
    }
  }

  private handleFrame(socket: WebSocketLike, frame: string): void {
    const lines = frame.split("\n")
    const command = lines.shift()?.trim()
    if (command === "MESSAGE") {
      const headers = parseHeaders(lines)
      const separator = lines.indexOf("")
      const body = separator < 0 ? "" : lines.slice(separator + 1).join("\n")
      let decoded: unknown = null
      try { decoded = JSON.parse(body) } catch { return }
      const destination = headers.get("destination")
      if (destination) for (const listener of this.frameListeners) listener(destination, decoded)
      return
    }
    if (command !== "CONNECTED") return

    const headers = parseHeaders(lines)

    this.startHeartbeats(socket, headers.get("heart-beat"))
    this.resubscribeAll(socket)
    this.setState("connected")
  }

  private resubscribeAll(socket: WebSocketLike): void {
    for (const [destination, subscription] of this.destinationSubscriptions) {
      this.sendSubscribe(socket, destination, subscription.id)
    }
  }

  private sendSubscribe(socket: WebSocketLike, destination: string, id: string): void {
    socket.send(`SUBSCRIBE\nid:${id}\ndestination:${destination}\nack:auto\n\n\u0000`)
  }

  private sendUnsubscribe(socket: WebSocketLike, id: string): void {
    socket.send(`UNSUBSCRIBE\nid:${id}\n\n\u0000`)
  }

  private startHeartbeats(socket: WebSocketLike, serverHeartbeat?: string): void {
    this.clearHeartbeats()
    this.lastServerActivity = Date.now()

    const [serverOutgoing, serverIncoming] = parseHeartbeat(serverHeartbeat)
    const outgoing = negotiateHeartbeat(this.options.heartbeatOutgoingMs, serverIncoming)
    const incoming = negotiateHeartbeat(this.options.heartbeatIncomingMs, serverOutgoing)

    if (outgoing > 0) {
      this.outgoingHeartbeat = setInterval(() => {
        if (this.socket === socket && socket.readyState === WEBSOCKET_OPEN) {
          socket.send("\n")
        }
      }, outgoing)
    }

    if (incoming > 0) {
      const timeout = incoming * HEARTBEAT_GRACE_MULTIPLIER
      this.incomingWatchdog = setInterval(() => {
        if (this.socket !== socket) return
        if (Date.now() - this.lastServerActivity > timeout) {
          try {
            socket.close(4000, "Realtime heartbeat timeout")
          } finally {
            this.handleDisconnect(socket)
          }
        }
      }, incoming)
    }
  }

  private handleDisconnect(socket: WebSocketLike): void {
    if (this.socket !== socket) return
    this.socket = null
    this.clearHeartbeats()
    this.setState("disconnected")
    this.scheduleReconnect()
  }

  private handleConnectFailure(): void {
    this.socket = null
    this.clearHeartbeats()
    this.setState("disconnected")
    this.scheduleReconnect()
  }

  private scheduleReconnect(): void {
    if (!this.shouldRun || this.reconnectTimer) return
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      this.connect()
    }, this.options.reconnectDelayMs)
  }

  private clearReconnect(): void {
    if (!this.reconnectTimer) return
    clearTimeout(this.reconnectTimer)
    this.reconnectTimer = null
  }

  private clearHeartbeats(): void {
    if (this.outgoingHeartbeat) clearInterval(this.outgoingHeartbeat)
    if (this.incomingWatchdog) clearInterval(this.incomingWatchdog)
    this.outgoingHeartbeat = null
    this.incomingWatchdog = null
  }

  private setState(state: RealtimeConnectionState): void {
    if (this.state === state) return
    this.state = state
    for (const listener of this.listeners) listener(state)
  }
}

function normalizeDestination(value: string): string {
  const destination = value.trim()
  if (
    !destination ||
    destination.length > 255 ||
    (!destination.startsWith("/topic/") &&
      !destination.startsWith("/queue/") &&
      !destination.startsWith("/user/")) ||
    /[\r\n\u0000]/.test(destination)
  ) {
    throw new Error("Invalid STOMP subscription destination")
  }
  return destination
}

function parseHeaders(lines: string[]): Map<string, string> {
  const headers = new Map<string, string>()
  for (const line of lines) {
    if (line === "") break
    const separator = line.indexOf(":")
    if (separator <= 0) continue
    headers.set(line.slice(0, separator), line.slice(separator + 1))
  }
  return headers
}

function parseHeartbeat(value?: string): readonly [number, number] {
  if (!value) return [0, 0]
  const [outgoing, incoming] = value.split(",", 2).map((part) => Number(part))
  if (!Number.isFinite(outgoing) || !Number.isFinite(incoming)) return [0, 0]
  if (outgoing < 0 || incoming < 0) return [0, 0]
  return [outgoing, incoming]
}

function negotiateHeartbeat(clientValue: number, serverValue: number): number {
  if (clientValue <= 0 || serverValue <= 0) return 0
  return Math.max(clientValue, serverValue)
}
