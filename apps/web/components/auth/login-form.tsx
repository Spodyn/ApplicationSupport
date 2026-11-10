"use client"

import { useT } from "@/lib/i18n/provider"
import { useState, type FormEvent } from "react"
import { useRouter } from "next/navigation"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { AuthenticationRequiredError } from "@/lib/services/current-user"
import { useLogin } from "@/lib/services/auth-queries"

export function LoginForm() {
  const t = useT()
  const router = useRouter()
  const login = useLogin()
  const [email, setEmail] = useState("")
  const [password, setPassword] = useState("")

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    try {
      await login.mutateAsync({ email, password })
      router.replace("/cases")
    } catch {
      // Mutation state renders the generic error without exposing account state.
    } finally {
      setPassword("")
    }
  }

  const message =
    login.error instanceof AuthenticationRequiredError
      ? t("Nieprawidłowy e-mail lub hasło.")
      : login.error
        ? t("Nie udało się zalogować. Spróbuj ponownie.")
        : null

  return (
    <Card className="w-full max-w-md">
      <CardHeader>
        <CardTitle>
          <h1>{t("Zaloguj się")}</h1>
        </CardTitle>
        <CardDescription>
          {t("Użyj konta Unified Support Inbox, aby przejść do skrzynki wsparcia.")} </CardDescription>
      </CardHeader>
      <CardContent>
        <form method="post" className="space-y-4" onSubmit={(event) => void submit(event)}>
          <div className="space-y-2">
            <Label htmlFor="email">{t("E-mail")}</Label>
            <Input
              id="email"
              name="email"
              type="email"
              autoComplete="username"
              required
              maxLength={320}
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="password">{t("Hasło")}</Label>
            <Input
              id="password"
              name="password"
              type="password"
              autoComplete="current-password"
              required
              maxLength={128}
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </div>
          {message ? (
            <p role="alert" className="text-sm text-destructive">
              {message}
            </p>
          ) : null}
          <Button type="submit" className="w-full" disabled={login.isPending}>
            {login.isPending ? t("Logowanie…") : t("Zaloguj się")}
          </Button>
        </form>
      </CardContent>
    </Card>
  )
}
