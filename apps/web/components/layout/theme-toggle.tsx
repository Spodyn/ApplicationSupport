"use client"

import { useSyncExternalStore } from "react"
import { useTheme } from "next-themes"
import { Moon, Sun } from "lucide-react"
import { Button } from "@/components/ui/button"

const subscribe = () => () => {}
const getClientSnapshot = () => true
const getServerSnapshot = () => false

export function ThemeToggle() {
  const { resolvedTheme, setTheme } = useTheme()
  const mounted = useSyncExternalStore(subscribe, getClientSnapshot, getServerSnapshot)

  const isDark = mounted && resolvedTheme === "dark"
  const nextTheme = isDark ? "light" : "dark"
  const label = isDark ? "Włącz tryb jasny" : "Włącz tryb ciemny"

  return (
    <Button
      type="button"
      variant="outline"
      size="sm"
      onClick={() => setTheme(nextTheme)}
      aria-label={label}
      title={label}
      className="min-w-8 gap-1.5 bg-card px-2 shadow-xs"
    >
      {isDark ? <Sun aria-hidden /> : <Moon aria-hidden />}
      <span className="hidden xl:inline">{isDark ? "Jasny" : "Ciemny"}</span>
    </Button>
  )
}
