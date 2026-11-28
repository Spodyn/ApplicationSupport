"use client"

import Link from "next/link"
import { useT } from "@/lib/i18n/provider"
import { useCurrentUser, useInboxPreferences, useSaveInboxPreferences } from "@/lib/services/queries"
import { Button } from "@/components/ui/button"
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from "@/components/ui/card"

export function InboxPreferencesPage() {
  const t = useT()
  const user = useCurrentUser()
  const preference = useInboxPreferences(user.data?.id)
  const save = useSaveInboxPreferences(user.data?.id ?? "")
  return <main className="flex-1 overflow-y-auto p-4 sm:p-8">
    <div className="mx-auto max-w-2xl space-y-4">
      <Button variant="ghost" render={<Link href="/cases" />}>{t("Wróć do skrzynki")}</Button>
      <Card>
        <CardHeader>
          <CardTitle><h1>{t("Preferencje skrzynki")}</h1></CardTitle>
          <CardDescription>{t("Wybierz sposób wyświetlania spraw. Ustawienie jest zapisywane na Twoim koncie.")}</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          {preference.isPending ? <p role="status">{t("Wczytywanie…")}</p> : preference.isError ? <div role="alert">
            <p>{t("Nie udało się wczytać preferencji skrzynki.")}</p>
            <Button variant="outline" onClick={() => void preference.refetch()}>{t("Spróbuj ponownie")}</Button>
          </div> : <fieldset disabled={save.isPending} className="space-y-3">
            <legend className="sr-only">{t("Tryb skrzynki")}</legend>
            {([
              ["unified", "Wspólna skrzynka", "Wszystkie platformy na jednej liście spraw."],
              ["separate_by_platform", "Osobno według platformy", "Przełączaj platformy nad listą spraw."],
            ] as const).map(([mode, label, description]) => <label key={mode} className="flex cursor-pointer items-start gap-3 rounded-lg border p-4 has-checked:border-primary has-checked:bg-primary/5">
              <input type="radio" name="inbox-mode" value={mode} checked={preference.data === mode}
                className="mt-1 accent-primary" onChange={() => save.mutate(mode)} />
              <span><span className="block font-medium">{t(label)}</span><span className="text-sm text-muted-foreground">{t(description)}</span></span>
            </label>)}
          </fieldset>}
          {save.isPending && <p role="status" className="text-sm text-muted-foreground">{t("Zapisywanie…")}</p>}
          {save.isError && <p role="alert" className="text-sm text-destructive">{t("Nie udało się zapisać preferencji skrzynki.")}</p>}
        </CardContent>
      </Card>
    </div>
  </main>
}
