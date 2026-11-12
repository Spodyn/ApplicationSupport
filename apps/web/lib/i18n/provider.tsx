"use client"

import { createContext, useCallback, useContext, useLayoutEffect, useState, type ReactNode } from "react"
import { I18nextProvider, useTranslation } from "react-i18next"
import { createI18n, DEFAULT_LANGUAGE, language, translationOptions, type Language } from "./catalog"

type LanguagePreference = {
  accountId: string | null
  selectedLanguage: Language
  activate: (accountId: string | null) => void
  setLanguage: (selected: Language) => void
}

const LanguageContext = createContext<LanguagePreference | null>(null)

function preferenceKey(accountId: string) {
  return "usi-language:" + accountId
}

function readLanguage(accountId: string | null): Language {
  if (!accountId) return DEFAULT_LANGUAGE
  try {
    return language(localStorage.getItem(preferenceKey(accountId)) ?? undefined)
  } catch {
    return DEFAULT_LANGUAGE
  }
}

function syncDocument(selected: Language) {
  document.documentElement.lang = selected
  document.querySelector<HTMLLinkElement>('link[rel="manifest"]')?.setAttribute("href", "/pwa/manifest/" + selected)
}

export function LanguageProvider({ children, initialLanguage }: { children: ReactNode; initialLanguage: Language }) {
  const [instance] = useState(() => createI18n(initialLanguage))
  const [accountId, setAccountId] = useState<string | null>(null)
  const [selectedLanguage, setSelectedLanguage] = useState(initialLanguage)

  useLayoutEffect(() => { syncDocument(selectedLanguage) }, [selectedLanguage])

  const applyLanguage = useCallback((selected: Language) => {
    syncDocument(selected)
    void instance.changeLanguage(selected)
    setSelectedLanguage(selected)
  }, [instance])

  const activate = useCallback((next: string | null) => {
    if (next === accountId) return
    applyLanguage(readLanguage(next))
    setAccountId(next)
  }, [accountId, applyLanguage])

  const setLanguage = (selected: Language) => {
    if (accountId) {
      try {
        localStorage.setItem(preferenceKey(accountId), selected)
      } catch {
        // The current interface can still switch if browser storage is unavailable.
      }
    }
    applyLanguage(selected)
  }

  return (
    <I18nextProvider i18n={instance}>
      <LanguageContext.Provider value={{ accountId, selectedLanguage, activate, setLanguage }}>
        {children}
      </LanguageContext.Provider>
    </I18nextProvider>
  )
}

export function useLanguagePreference() {
  const value = useContext(LanguageContext)
  if (!value) throw new Error("LanguageProvider is required")
  return value
}

// The server renders the English session shell. Account content mounts only
// after the authenticated identity and its browser preference are available.
export function LanguageScope({ accountId, children }: { accountId: string | null; children: ReactNode }) {
  const { accountId: activeId, activate } = useLanguagePreference()
  useLayoutEffect(() => { activate(accountId) }, [accountId, activate])
  return activeId === accountId ? children : null
}

export function useT() {
  const { t } = useTranslation()
  return useCallback((key: string, values?: Record<string, unknown>): string => String(t(key, translationOptions(key, values))), [t])
}

export function LanguageSelector() {
  const { selectedLanguage, setLanguage } = useLanguagePreference()
  const t = useT()
  return (
    <label className="flex items-center justify-between gap-3 px-2 py-2 text-sm">
      <span>{t("Język interfejsu")}</span>
      <select value={selectedLanguage} className="rounded-md border border-input bg-background px-2 py-1 text-foreground" onChange={(event) => setLanguage(language(event.target.value))}>
        <option value="en" lang="en">English</option>
        <option value="pl" lang="pl">Polski</option>
      </select>
    </label>
  )
}
