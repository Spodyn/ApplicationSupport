"use client"

import { createContext, useContext } from "react"

interface ShellContextValue {
  openMobileNav: () => void
}

const ShellContext = createContext<ShellContextValue | null>(null)

export const ShellProvider = ShellContext.Provider

export function useShell() {
  const ctx = useContext(ShellContext)
  return ctx
}
