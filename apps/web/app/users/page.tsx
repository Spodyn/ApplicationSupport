"use client"

import { userError } from "@/lib/i18n/errors"
import { useT } from "@/lib/i18n/provider"
import { useCallback, useMemo, useState, type FormEvent, type ReactNode } from "react"
import {
  MoreHorizontal,
  CheckCircle2,
  KeyRound,
  MailPlus,
  Pencil,
  Plus,
  RefreshCw,
  ShieldCheck,
  Trash2,
  UserX,
  Users,
} from "lucide-react"
import { PageHeader } from "@/components/layout/page-header"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Switch } from "@/components/ui/switch"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { DataTable, type DataTableColumn } from "@/components/design-system/data-table"
import {
  ALL_VALUE,
  FilterBar,
  FilterSelect,
  SearchFilter,
} from "@/components/design-system/filter-bar"
import { ConfirmDialog } from "@/components/design-system/confirm-dialog"
import { EmptyState, ErrorState } from "@/components/design-system/data-states"
import { UserAvatar } from "@/components/design-system/user-avatar"
import { notify } from "@/components/design-system/notify"
import {
  allAdministrationPermissions,
  administrationPermissionLabels,
  hasAdministrationPermission,
} from "@/lib/domain/administration"
import type {
  AdministrationPermission,
  AdministrationRole,
  AdministrationUser,
  AdministrationUserInput,
} from "@/lib/domain/administration"
import { formatDate, formatDateTime } from "@/lib/format"
import {
  useAdministrationUserActions,
  useAdministrationUsers,
  useCurrentAdministrationUser,
} from "@/lib/services/queries"

const roleLabels: Record<AdministrationRole, string> = {
  USER: "Użytkownik",
  ADMIN: "Administrator",
}

const emptyForm: AdministrationUserInput = {
  fullName: "",
  email: "",
  role: "USER",
  active: true,
  validFrom: new Date().toISOString().slice(0, 10),
  validUntil: undefined,
  permissions: [],
}

export default function UsersRoute() {
  const t = useT()
  const [search, setSearch] = useState("")
  const [role, setRole] = useState(ALL_VALUE)
  const [active, setActive] = useState(ALL_VALUE)
  const [editedUser, setEditedUser] = useState<AdministrationUser | null>(null)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [deactivatedUser, setDeactivatedUser] = useState<AdministrationUser | null>(null)
  const [deletedUser, setDeletedUser] = useState<AdministrationUser | null>(null)
  const [credentialResult, setCredentialResult] = useState<{
    kind: "invitation" | "reset"
    user: AdministrationUser
    token: string
    expiresAt: string
  } | null>(null)

  const query = useMemo(
    () => ({
      search: search || undefined,
      role: role === ALL_VALUE ? undefined : (role as AdministrationRole),
      active: active === ALL_VALUE ? undefined : active === "active",
    }),
    [active, role, search],
  )
  const usersQuery = useAdministrationUsers(query)
  const currentAdministrationUserQuery = useCurrentAdministrationUser()
  const actions = useAdministrationUserActions()
  const users = usersQuery.data ?? []
  const canManageUsers = actions.supported && hasAdministrationPermission(
    currentAdministrationUserQuery.data,
    "manage_users",
  )

  const issueCredential = useCallback(async (
    user: AdministrationUser,
    kind: "invitation" | "reset",
  ) => {
    try {
      const result = kind === "invitation"
        ? await actions.issueInvitation.mutateAsync(user.id)
        : await actions.issuePasswordReset.mutateAsync(user.id)
      setCredentialResult({ kind, user, ...result })
    } catch (error) {
      notify.error(t("Nie udało się wygenerować bezpiecznego linku"), getErrorMessage(error))
    }
  }, [actions.issueInvitation, actions.issuePasswordReset, t])

  const openCreate = () => {
    setEditedUser(null)
    setDialogOpen(true)
  }
  const openEdit = (user: AdministrationUser) => {
    setEditedUser(user)
    setDialogOpen(true)
  }

  const columns = useMemo<DataTableColumn<AdministrationUser>[]>(
    () => [
      {
        id: "user",
        header: t("Użytkownik"),
        className: "min-w-64",
        cell: (user) => (
          <div className="flex items-center gap-3">
            <UserAvatar user={user} showPresence />
            <div className="min-w-0">
              <p className="truncate font-medium">{user.fullName}</p>
              <p className="truncate text-xs text-muted-foreground">{user.email}</p>
            </div>
          </div>
        ),
      },
      {
        id: "role",
        header: t("Rola"),
        cell: (user) => (
          <Badge variant={user.role === "ADMIN" ? "default" : "secondary"}>
            {user.role === "ADMIN" && <ShieldCheck />}
            {t(roleLabels[user.role])}
          </Badge>
        ),
      },
      {
        id: "active",
        header: t("Status"),
        cell: (user) => (
          <Badge
            variant="secondary"
            className={
              user.active
                ? "border-transparent bg-success/10 text-success"
                : "border-transparent text-muted-foreground"
            }
          >
            {user.active ? <CheckCircle2 /> : <UserX />}
            {user.active ? t("Aktywny") : t("Nieaktywny")}
          </Badge>
        ),
      },
      {
        id: "validity",
        header: t("Okres ważności"),
        className: "min-w-40 text-xs",
        cell: (user) => (
          <div>
            <p>{user.validFrom ? t("od {{date}}", { date: formatDate(user.validFrom) }) : t("bez daty początkowej")}</p>
            <p className="text-muted-foreground">
              {user.validUntil ? t("do {{date}}", { date: formatDate(user.validUntil) }) : t("bezterminowo")}
            </p>
          </div>
        ),
      },
      {
        id: "cases",
        header: t("Aktywne case’y"),
        align: "center",
        cell: (user) => <span className="font-medium tabular-nums">{user.activeAssignedCases}</span>,
      },
      {
        id: "login",
        header: t("Ostatnie logowanie"),
        className: "min-w-40 text-xs text-muted-foreground",
        cell: (user) => user.lastLoginAt ? formatDateTime(user.lastLoginAt) : t("Jeszcze nigdy"),
      },
      {
        id: "actions",
        header: <span className="sr-only">{t("Akcje")}</span>,
        align: "end",
        cell: (user) => (
          <DropdownMenu>
            <DropdownMenuTrigger
              render={<Button variant="ghost" size="icon-sm" aria-label={t("Akcje dla {{value0}}", { value0: user.fullName })} />}
            >
              <MoreHorizontal />
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-44">
              <DropdownMenuItem disabled={!canManageUsers} onClick={() => openEdit(user)}>
                <Pencil /> {t("Edytuj")} </DropdownMenuItem>
              <DropdownMenuItem
                disabled={!canManageUsers}
                onClick={() => void issueCredential(user, "invitation")}
              >
                <MailPlus /> {t("Wygeneruj zaproszenie")} </DropdownMenuItem>
              <DropdownMenuItem
                disabled={!canManageUsers}
                onClick={() => void issueCredential(user, "reset")}
              >
                <KeyRound /> {t("Resetuj hasło")} </DropdownMenuItem>
              <DropdownMenuSeparator />
              <DropdownMenuItem
                variant="destructive"
                disabled={!user.active || !canManageUsers}
                onClick={() => setDeactivatedUser(user)}
              >
                <UserX /> {t("Dezaktywuj")} </DropdownMenuItem>
              <DropdownMenuItem
                variant="destructive"
                disabled={!canManageUsers || user.id === currentAdministrationUserQuery.data?.id}
                onClick={() => setDeletedUser(user)}
              >
                <Trash2 /> {t("Usuń użytkownika")} </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        ),
      },
    ],
    [canManageUsers, currentAdministrationUserQuery.data?.id, issueCredential, t],
  )

  const activeCount = users.filter((user) => user.active).length
  const adminCount = users.filter((user) => user.role === "ADMIN").length
  const assignedCount = users.reduce((sum, user) => sum + user.activeAssignedCases, 0)

  const resetFilters = () => {
    setSearch("")
    setRole(ALL_VALUE)
    setActive(ALL_VALUE)
  }

  const deactivate = async () => {
    if (!deactivatedUser) return
    try {
      await actions.deactivate.mutateAsync(deactivatedUser.id)
      notify.success(t("Użytkownik został dezaktywowany"), deactivatedUser.fullName)
      setDeactivatedUser(null)
    } catch (error) {
      notify.error(t("Nie udało się dezaktywować użytkownika"), getErrorMessage(error))
    }
  }

  const deleteUser = async () => {
    if (!deletedUser) return
    try {
      await actions.delete.mutateAsync(deletedUser.id)
      notify.success(t("Użytkownik został usunięty"), deletedUser.fullName)
      setDeletedUser(null)
    } catch (error) {
      notify.error(t("Nie udało się usunąć użytkownika"), getErrorMessage(error))
    }
  }

  return (
    <>
      <PageHeader
        title={t("Użytkownicy")}
        description={t("Konta i uprawnienia zespołu wsparcia")}
        actions={
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => void usersQuery.refetch()}
              disabled={usersQuery.isFetching}
            >
              <RefreshCw className={usersQuery.isFetching ? "animate-spin" : undefined} />
              <span className="hidden sm:inline">{t("Odśwież")}</span>
            </Button>
            <Button size="sm" onClick={openCreate} disabled={!canManageUsers}>
              <Plus /> {t("Dodaj użytkownika")} </Button>
          </div>
        }
      />

      <main className="min-h-0 flex-1 overflow-y-auto bg-muted/20">
        <div className="mx-auto flex w-full max-w-[1600px] flex-col gap-4 p-4 md:p-6">
          <section className="grid gap-2 sm:grid-cols-3" aria-label={t("Podsumowanie użytkowników")}>
            <Metric label={t("Aktywne konta")} value={activeCount} />
            <Metric label={t("Administratorzy")} value={adminCount} />
            <Metric label={t("Przypisane aktywne case’y")} value={assignedCount} />
          </section>

          <div className="flex flex-col gap-3">
            <div className="rounded-lg border bg-card p-3">
              <FilterBar>
                <SearchFilter value={search} onChange={setSearch} placeholder={t("Szukaj po nazwie lub e-mailu…")} />
                <FilterSelect
                  value={role}
                  onChange={setRole}
                  placeholder={t("Rola")}
                  allLabel={t("Wszystkie role")}
                  options={[
                    { value: "USER", label: t("Użytkownik") },
                    { value: "ADMIN", label: t("Administrator") },
                  ]}
                />
                <FilterSelect
                  value={active}
                  onChange={setActive}
                  placeholder={t("Status")}
                  allLabel={t("Wszystkie statusy")}
                  options={[
                    { value: "active", label: t("Aktywni") },
                    { value: "inactive", label: t("Nieaktywni") },
                  ]}
                />
                {(search || role !== ALL_VALUE || active !== ALL_VALUE) && (
                  <Button variant="ghost" size="sm" onClick={resetFilters}>{t("Wyczyść filtry")}</Button>
                )}
              </FilterBar>
            </div>

            {usersQuery.isError || currentAdministrationUserQuery.isError ? (
              <div className="rounded-lg border bg-card">
                <ErrorState onRetry={() => { void usersQuery.refetch(); void currentAdministrationUserQuery.refetch() }} />
              </div>
            ) : (
              <DataTable
                columns={columns}
                data={users}
                getRowId={(user) => user.id}
                isLoading={usersQuery.isLoading || currentAdministrationUserQuery.isLoading}
                emptyState={
                  <EmptyState
                    icon={Users}
                    title={t("Nie znaleziono użytkowników")}
                    description={t("Zmień kryteria wyszukiwania albo dodaj pierwsze konto.")}
                    action={<Button onClick={openCreate} disabled={!canManageUsers}><Plus /> {t("Dodaj użytkownika")}</Button>}
                  />
                }
              />
            )}
          </div>
        </div>
      </main>

      {dialogOpen ? (
        <UserDialog
          key={editedUser?.id ?? "new-user"}
          open
          onOpenChange={setDialogOpen}
          user={editedUser}
          saving={actions.save.isPending}
          onSave={async (input) => {
            try {
              await actions.save.mutateAsync({ input, id: editedUser?.id })
              notify.success(editedUser ? t("Zapisano zmiany użytkownika") : t("Dodano użytkownika"), input.fullName)
              setDialogOpen(false)
            } catch (error) {
              notify.error(t("Nie udało się zapisać użytkownika"), getErrorMessage(error))
            }
          }}
        />
      ) : null}

      <ConfirmDialog
        open={Boolean(deactivatedUser)}
        onOpenChange={(open) => !open && setDeactivatedUser(null)}
        title={t("Dezaktywować użytkownika?")}
        description={deactivatedUser ? t("{{value0}} utraci dostęp do aplikacji. Aktywne case’y pozostaną widoczne i będzie można je przepisać.", { value0: deactivatedUser.fullName }) : undefined}
        confirmLabel={t("Dezaktywuj")}
        destructive
        onConfirm={() => void deactivate()}
      />

      <ConfirmDialog
        open={Boolean(deletedUser)}
        onOpenChange={(open) => !open && setDeletedUser(null)}
        title={t("Usunąć użytkownika?")}
        description={deletedUser ? t("{{value0}} zostanie trwale usunięty z listy użytkowników. Tej operacji nie można cofnąć.", { value0: deletedUser.fullName }) : undefined}
        confirmLabel={t("Usuń")}
        destructive
        onConfirm={() => void deleteUser()}
      />

      <Dialog open={Boolean(credentialResult)} onOpenChange={(open) => !open && setCredentialResult(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{credentialResult?.kind === "invitation" ? t("Zaproszenie gotowe") : t("Reset hasła gotowy")}</DialogTitle>
            <DialogDescription>
              {t("Token jest widoczny tylko teraz. Przekaż go użytkownikowi bezpiecznym kanałem i poproś o ustawienie hasła na stronie /activate; wygaśnie")} {credentialResult ? formatDateTime(credentialResult.expiresAt) : ""}.
            </DialogDescription>
          </DialogHeader>
          <div className="grid gap-2">
            <Label htmlFor="credential-token">{t("Jednorazowy token dla")} {credentialResult?.user.fullName}</Label>
            <Input id="credential-token" readOnly value={credentialResult?.token ?? ""} onFocus={(event) => event.currentTarget.select()} />
          </div>
          <DialogFooter>
            <Button type="button" onClick={() => setCredentialResult(null)}>{t("Gotowe")}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}

function UserDialog({
  open,
  onOpenChange,
  user,
  saving,
  onSave,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  user: AdministrationUser | null
  saving: boolean
  onSave: (input: AdministrationUserInput) => Promise<void>
}) {
  const t = useT()
  const [form, setForm] = useState<AdministrationUserInput>(() =>
    user
      ? {
          fullName: user.fullName,
          email: user.email,
          role: user.role,
          active: user.active,
          validFrom: user.validFrom,
          validUntil: user.validUntil,
          permissions: [...user.permissions],
        }
      : structuredClone(emptyForm),
  )

  const togglePermission = (permission: AdministrationPermission, checked: boolean) => {
    setForm((current) => ({
      ...current,
      permissions: checked
        ? [...new Set([...current.permissions, permission])]
        : current.permissions.filter((item) => item !== permission),
    }))
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!form.fullName.trim() || !form.email.trim()) {
      notify.warning(t("Uzupełnij wymagane pola"), t("Nazwa i adres e-mail są wymagane."))
      return
    }
    void onSave(form)
  }

  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
    >
      <DialogContent className="max-h-[90svh] overflow-y-auto sm:max-w-2xl">
        <form onSubmit={submit} className="grid gap-4">
          <DialogHeader>
            <DialogTitle>{user ? t("Edytuj użytkownika") : t("Dodaj użytkownika")}</DialogTitle>
            <DialogDescription>
              {t("Konto i uprawnienia zostaną zapisane w backendzie aplikacji.")} </DialogDescription>
          </DialogHeader>

          <div className="grid gap-4 sm:grid-cols-2">
            <Field label={t("Imię i nazwisko")} required>
              <Input value={form.fullName} onChange={(event) => setForm({ ...form, fullName: event.target.value })} autoFocus />
            </Field>
            <Field label={t("E-mail")} required>
              <Input type="email" value={form.email} onChange={(event) => setForm({ ...form, email: event.target.value })} />
            </Field>
            <Field label={t("Rola")}>
              <Select
                value={form.role}
                onValueChange={(next) => {
                  const role = String(next) as AdministrationRole
                  setForm({
                    ...form,
                    role,
                    permissions:
                      role === "ADMIN" ? [...allAdministrationPermissions] : [],
                  })
                }}
              >
                <SelectTrigger className="w-full"><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="USER">{t("Użytkownik")}</SelectItem>
                  <SelectItem value="ADMIN">{t("Administrator")}</SelectItem>
                </SelectContent>
              </Select>
            </Field>
            <Field label={t("Ważny od")}>
              <Input type="date" value={form.validFrom} onChange={(event) => setForm({ ...form, validFrom: event.target.value })} />
            </Field>
            <Field label={t("Ważny do (opcjonalnie)")}>
              <Input type="date" value={form.validUntil ?? ""} onChange={(event) => setForm({ ...form, validUntil: event.target.value || undefined })} />
            </Field>
          </div>

          <div className="flex items-center justify-between rounded-lg border p-3">
            <div>
              <Label htmlFor="user-active">{t("Aktywne konto")}</Label>
              <p className="text-xs text-muted-foreground">{t("Użytkownik może zalogować się do aplikacji.")}</p>
            </div>
            <Switch id="user-active" checked={form.active} onCheckedChange={(checked) => setForm({ ...form, active: checked })} />
          </div>

          <fieldset className="grid gap-2 rounded-lg border p-3">
            <legend className="px-1 text-sm font-medium">{t("Uprawnienia indywidualne")}</legend>
            <p className="text-xs text-muted-foreground">{t("Uprawnienia administracyjne można nadać wyłącznie roli Administrator. Rola Użytkownik nie uzyskuje dostępu przez indywidualny grant.")}</p>
            <div className="grid gap-2 sm:grid-cols-2">
              {allAdministrationPermissions.map((permission) => (
                <label key={permission} className="flex items-center gap-2 rounded-md p-2 text-sm hover:bg-muted/50">
                  <input
                    type="checkbox"
                    className="size-4 accent-primary"
                    checked={form.permissions.includes(permission)}
                    disabled={form.role !== "ADMIN"}
                    onChange={(event) => togglePermission(permission, event.target.checked)}
                  />
                  {t(administrationPermissionLabels[permission])}
                </label>
              ))}
            </div>
          </fieldset>

          <DialogFooter>
            <DialogClose render={<Button variant="outline" type="button" />}>{t("Anuluj")}</DialogClose>
            <Button type="submit" disabled={saving}>{saving ? t("Zapisywanie…") : t("Zapisz")}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function Field({ label, required, children }: { label: string; required?: boolean; children: ReactNode }) {
  return (
    <label className="grid gap-1.5 text-sm">
      <span className="font-medium">{label}{required && <span className="text-destructive"> *</span>}</span>
      {children}
    </label>
  )
}

function Metric({ label, value }: { label: string; value: number }) {
  return (
    <div className="rounded-lg border bg-card px-4 py-3">
      <p className="text-2xl font-semibold tabular-nums">{value}</p>
      <p className="text-xs text-muted-foreground">{label}</p>
    </div>
  )
}

function getErrorMessage(error: unknown) {
  return userError(error)
}
