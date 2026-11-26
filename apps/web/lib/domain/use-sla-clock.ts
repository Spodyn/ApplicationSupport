"use client"

import { useSyncExternalStore } from "react"

let now = 0
let timer: ReturnType<typeof setInterval> | undefined
const listeners = new Set<() => void>()

function tick() {
  now = performance.now()
  for (const listener of listeners) listener()
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  if (timer === undefined) {
    tick()
    timer = setInterval(tick, 1_000)
    document.addEventListener("visibilitychange", tick)
  }
  return () => {
    listeners.delete(listener)
    if (listeners.size === 0) {
      clearInterval(timer)
      timer = undefined
      document.removeEventListener("visibilitychange", tick)
    }
  }
}

const idleSubscribe = () => () => {}
const readClock = () => now
const readServer = () => 0

export function useSlaClock(active: boolean) {
  return useSyncExternalStore(active ? subscribe : idleSubscribe, active ? readClock : readServer, readServer)
}
