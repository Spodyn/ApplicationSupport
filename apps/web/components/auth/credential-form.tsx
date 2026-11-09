"use client"

import Link from "next/link"
import { useState, type FormEvent } from "react"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  useAcceptInvitation,
  useCompletePasswordReset,
} from "@/lib/services/auth-queries"

type CredentialMode = "invitation" | "reset"

export function CredentialForm() {
  const [mode, setMode] = useState<CredentialMode>("invitation")
  const [token, setToken] = useState("")
  const [password, setPassword] = useState("")
  const [confirmation, setConfirmation] = useState("")
  const [completed, setCompleted] = useState(false)
  const invitation = useAcceptInvitation()
  const reset = useCompletePasswordReset()
  const mutation = mode === "invitation" ? invitation : reset

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (password !== confirmation) return

    try {
      await mutation.mutateAsync({ token: token.trim(), password })
      setCompleted(true)
    } catch {
      // The mutation state renders a safe error without revealing account data.
    }
  }

  if (completed) {
    return (
      <Card className="w-full max-w-md">
        <CardHeader>
          <CardTitle><h1>Hasło ustawione</h1></CardTitle>
          <CardDescription>Możesz teraz zalogować się do Unified Support Inbox.</CardDescription>
        </CardHeader>
        <CardContent>
          <Link className="inline-flex h-9 w-full items-center justify-center rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground shadow-xs hover:bg-primary/90" href="/login">
            Przejdź do logowania
          </Link>
        </CardContent>
      </Card>
    )
  }

  const mismatch = confirmation.length > 0 && password !== confirmation

  return (
    <Card className="w-full max-w-md">
      <CardHeader>
        <CardTitle><h1>Ustaw hasło</h1></CardTitle>
        <CardDescription>Użyj jednorazowego tokenu otrzymanego od administratora.</CardDescription>
      </CardHeader>
      <CardContent>
        <form method="post" className="space-y-4" onSubmit={(event) => void submit(event)}>
          <fieldset className="grid grid-cols-2 gap-2">
            <legend className="sr-only">Rodzaj tokenu</legend>
            <Button type="button" variant={mode === "invitation" ? "default" : "outline"} onClick={() => setMode("invitation")}>
              Zaproszenie
            </Button>
            <Button type="button" variant={mode === "reset" ? "default" : "outline"} onClick={() => setMode("reset")}>
              Reset hasła
            </Button>
          </fieldset>
          <div className="space-y-2">
            <Label htmlFor="credential-token">Jednorazowy token</Label>
            <Input id="credential-token" autoComplete="off" required value={token} onChange={(event) => setToken(event.target.value)} />
          </div>
          <div className="space-y-2">
            <Label htmlFor="new-password">Nowe hasło</Label>
            <Input id="new-password" type="password" autoComplete="new-password" required minLength={12} maxLength={128} value={password} onChange={(event) => setPassword(event.target.value)} />
            <p className="text-xs text-muted-foreground">Od 12 do 128 znaków, w jednej linii.</p>
          </div>
          <div className="space-y-2">
            <Label htmlFor="confirm-password">Powtórz hasło</Label>
            <Input id="confirm-password" type="password" autoComplete="new-password" required minLength={12} maxLength={128} value={confirmation} onChange={(event) => setConfirmation(event.target.value)} />
          </div>
          {mismatch ? <p role="alert" className="text-sm text-destructive">Hasła nie są identyczne.</p> : null}
          {mutation.isError ? <p role="alert" className="text-sm text-destructive">Token jest nieprawidłowy, wygasł albo nie pasuje do wybranej operacji.</p> : null}
          <Button type="submit" className="w-full" disabled={mutation.isPending || mismatch}>
            {mutation.isPending ? "Zapisywanie…" : "Ustaw hasło"}
          </Button>
          <Link className="inline-flex h-9 w-full items-center justify-center rounded-md px-4 text-sm font-medium hover:bg-accent hover:text-accent-foreground" href="/login">
            Wróć do logowania
          </Link>
        </form>
      </CardContent>
    </Card>
  )
}
