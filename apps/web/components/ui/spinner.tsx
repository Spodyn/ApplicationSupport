"use client"

import { useT } from "@/lib/i18n/provider"
import { cn } from "@/lib/utils"
import { Loader2Icon } from "lucide-react"

function Spinner({ className, ...props }: React.ComponentProps<"svg">) {
  const t = useT()
  return (
    <Loader2Icon data-slot="spinner" role="status" aria-label={t("Wczytywanie")} className={cn("size-4 animate-spin", className)} {...props} />
  )
}

export { Spinner }
