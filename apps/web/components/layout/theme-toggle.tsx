"use client"

import { useT } from "@/lib/i18n/provider"
import { useSyncExternalStore } from "react"
import { useTheme } from "next-themes"
import { Moon, Sun } from "lucide-react"
import { Button } from "@/components/ui/button"

const subscribe = () => () => {}
const getClientSnapshot = () => true
const getServerSnapshot = () => false

export function ThemeToggle() {
  const t = useT()
  const { resolvedTheme, setTheme } = useTheme()
  const mounted = useSyncExternalStore(subscribe, getClientSnapshot, getServerSnapshot)

  const isDark = mounted && resolvedTheme === "dark"
  const nextTheme = isDark ? "light" : "dark"
  const label = isDark ? t("Włącz tryb jasny") : t("Włącz tryb ciemny")

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
      <span className="hidden xl:inline">{isDark ? t("Jasny") : t("Ciemny")}</span>
    </Button>
  )
}
