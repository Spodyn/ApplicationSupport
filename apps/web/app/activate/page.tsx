"use client"

import { useT } from "@/lib/i18n/provider"
import { CredentialForm } from "@/components/auth/credential-form"

export default function ActivatePage() {
  const t = useT()
  return (
    <main className="grid min-h-svh place-items-center bg-muted/30 px-4 py-10">
      <div className="flex w-full max-w-md flex-col items-center gap-6">
        <div className="space-y-1 text-center">
          <p className="text-sm font-medium text-primary">Unified Support Inbox</p>
          <p className="text-sm text-muted-foreground">{t("Bezpieczna aktywacja konta")}</p>
        </div>
        <CredentialForm />
      </div>
    </main>
  )
}
