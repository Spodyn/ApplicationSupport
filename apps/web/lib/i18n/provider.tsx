"use client"

import { useCallback, useEffect, useState, type ReactNode } from "react"
import { I18nextProvider, useTranslation } from "react-i18next"
import { createI18n, language, LANGUAGE_COOKIE, translationOptions, type Language } from "./catalog"

export function LanguageProvider({ children, initialLanguage }: { children: ReactNode; initialLanguage: Language }) {
  const [instance] = useState(() => createI18n(initialLanguage))
  return <I18nextProvider i18n={instance}><LanguageDocument />{children}</I18nextProvider>
}
function LanguageDocument() {
  const { i18n } = useTranslation()
  useEffect(() => {
    const selected = language(i18n.language)
    document.documentElement.lang = selected
    document.querySelector<HTMLLinkElement>('link[rel="manifest"]')?.setAttribute("href", `/pwa/manifest/${selected}`)
  }, [i18n.language])
  return null
}
export function useT() {
  const { t } = useTranslation()
  return useCallback((key: string, values?: Record<string, unknown>): string => String(t(key, translationOptions(key, values))), [t])
}
export function LanguageSelector() {
  const { i18n } = useTranslation()
  const t = useT()
  return <label className="flex items-center justify-between gap-3 px-2 py-2 text-sm">
    <span>{t("Język interfejsu")}</span>
    <select aria-label={t("Język interfejsu")} value={language(i18n.language)} className="rounded-md border border-input bg-background px-2 py-1 text-foreground" onChange={(event) => {
      const next = language(event.target.value)
      document.cookie = `${LANGUAGE_COOKIE}=${next}; Path=/; Max-Age=31536000; SameSite=Lax${location.protocol === "https:" ? "; Secure" : ""}`
      void i18n.changeLanguage(next)
    }}>
      <option value="pl" lang="pl">Polski</option><option value="en" lang="en">English</option>
    </select>
  </label>
}
