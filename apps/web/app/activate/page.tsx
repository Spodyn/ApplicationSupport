import { CredentialForm } from "@/components/auth/credential-form"

export default function ActivatePage() {
  return (
    <main className="grid min-h-svh place-items-center bg-muted/30 px-4 py-10">
      <div className="flex w-full max-w-md flex-col items-center gap-6">
        <div className="space-y-1 text-center">
          <p className="text-sm font-medium text-primary">Unified Support Inbox</p>
          <p className="text-sm text-muted-foreground">Bezpieczna aktywacja konta</p>
        </div>
        <CredentialForm />
      </div>
    </main>
  )
}
