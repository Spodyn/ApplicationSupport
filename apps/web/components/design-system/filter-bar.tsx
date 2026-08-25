"use client"

import type { ReactNode } from "react"
import { Search, X } from "lucide-react"
import { cn } from "@/lib/utils"
import { Input } from "@/components/ui/input"
import { Button } from "@/components/ui/button"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"

/** Wartość sentinela reprezentująca brak filtra ("wszystkie"). */
export const ALL_VALUE = "__all__"

/** Kontener paska filtrów. */
export function FilterBar({
  children,
  className,
}: {
  children: ReactNode
  className?: string
}) {
  return (
    <div
      className={cn(
        "flex flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-center",
        className,
      )}
    >
      {children}
    </div>
  )
}

/** Pole wyszukiwania z ikoną i przyciskiem czyszczenia. */
export function SearchFilter({
  value,
  onChange,
  placeholder = "Szukaj…",
  className,
}: {
  value: string
  onChange: (value: string) => void
  placeholder?: string
  className?: string
}) {
  return (
    <div className={cn("relative w-full sm:w-64", className)}>
      <Search
        className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground"
        aria-hidden
      />
      <Input
        value={value}
        onChange={(event) => onChange(event.target.value)}
        placeholder={placeholder}
        className="h-8 pl-8"
        aria-label={placeholder}
      />
      {value && (
        <Button
          variant="ghost"
          size="icon-xs"
          className="absolute top-1/2 right-1 -translate-y-1/2"
          onClick={() => onChange("")}
          aria-label="Wyczyść wyszukiwanie"
        >
          <X />
        </Button>
      )}
    </div>
  )
}

export interface FilterOption {
  value: string
  label: string
}

/** Filtr rozwijany z opcją "wszystkie". */
export function FilterSelect({
  value,
  onChange,
  options,
  placeholder,
  allLabel = "Wszystkie",
  className,
}: {
  value: string
  onChange: (value: string) => void
  options: FilterOption[]
  placeholder: string
  allLabel?: string
  className?: string
}) {
  return (
    <Select
      value={value}
      onValueChange={(next) => onChange(String(next))}
    >
      <SelectTrigger className={cn("h-8 w-full sm:w-44", className)} aria-label={placeholder}>
        <SelectValue placeholder={placeholder} />
      </SelectTrigger>
      <SelectContent>
        <SelectItem value={ALL_VALUE}>{allLabel}</SelectItem>
        {options.map((option) => (
          <SelectItem key={option.value} value={option.value}>
            {option.label}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  )
}
