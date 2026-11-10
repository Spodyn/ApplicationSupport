"use client"

import { useT } from "@/lib/i18n/provider"
import { LoginForm } from "@/components/auth/login-form"
import Link from "next/link"

export default function LoginPage() {
  const t = useT()
  return (
    <main className="grid min-h-svh place-items-center bg-muted/30 px-4 py-10">
      <div className="flex w-full max-w-md flex-col items-center gap-6">
        <div className="space-y-1 text-center">
          <p className="text-sm font-medium text-primary">Unified Support Inbox</p>
          <p className="text-sm text-muted-foreground">{t("Jedna skrzynka. Wszystkie kanały.")}</p>
        </div>
        <LoginForm />
        <p className="text-center text-sm text-muted-foreground">
          {t("Masz token zaproszenia lub resetu?")}{" "}
          <Link className="font-medium text-primary underline-offset-4 hover:underline" href="/activate">
            {t("Ustaw hasło")} </Link>
        </p>
      </div>
    </main>
  )
}
