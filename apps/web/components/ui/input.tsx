"use client"

import * as React from "react"
import { Input as InputPrimitive } from "@base-ui/react/input"
import { useT } from "@/lib/i18n/provider"

import { cn } from "@/lib/utils"

function Input({ className, type, onInvalid, onInput, ...props }: React.ComponentProps<"input">) {
  const t = useT()
  return (
    <InputPrimitive
      type={type}
      data-slot="input"
      onInvalid={(event) => {
        onInvalid?.(event)
        if (event.defaultPrevented) return
        const input = event.currentTarget
        input.setCustomValidity("")
        const validity = input.validity
        const message = validity.valueMissing ? t("To pole jest wymagane.")
          : validity.typeMismatch && type === "email" ? t("Wpisz prawidłowy adres e-mail.")
          : validity.tooShort ? t("Wpisz co najmniej {{min}} znaków.", { min: input.minLength })
          : validity.tooLong ? t("Wpisz maksymalnie {{max}} znaków.", { max: input.maxLength })
          : validity.rangeUnderflow ? t("Minimalna wartość: {{min}}.", { min: input.min })
          : validity.rangeOverflow ? t("Maksymalna wartość: {{max}}.", { max: input.max })
          : t("Sprawdź wartość tego pola.")
        input.setCustomValidity(message)
      }}
      onInput={(event) => { event.currentTarget.setCustomValidity(""); onInput?.(event) }}
      className={cn(
        "h-8 w-full min-w-0 rounded-lg border border-input bg-card px-2.5 py-1 text-base transition-colors outline-none file:inline-flex file:h-6 file:border-0 file:bg-transparent file:text-sm file:font-medium file:text-foreground placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/35 disabled:pointer-events-none disabled:cursor-not-allowed disabled:bg-muted disabled:opacity-60 aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20 md:text-sm dark:aria-invalid:border-destructive/70 dark:aria-invalid:ring-destructive/30",
        className
      )}
      {...props}
    />
  )
}

export { Input }
