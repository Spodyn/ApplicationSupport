"use client"

import { useMemo, useState, type FormEvent, type ReactNode } from "react"
import {
  MoreHorizontal,
  CheckCircle2,
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
  validFrom: "2026-08-03",
  validUntil: undefined,
  permissions: [],
}

export default function UsersRoute() {
  const [search, setSearch] = useState("")
  const [role, setRole] = useState(ALL_VALUE)
  const [active, setActive] = useState(ALL_VALUE)
  const [editedUser, setEditedUser] = useState<AdministrationUser | null>(null)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [deactivatedUser, setDeactivatedUser] = useState<AdministrationUser | null>(null)
  const [deletedUser, setDeletedUser] = useState<AdministrationUser | null>(null)

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
  const canManageUsers = hasAdministrationPermission(
    currentAdministrationUserQuery.data,
    "manage_users",
  )

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
        header: "Użytkownik",
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
        header: "Rola",
        cell: (user) => (
          <Badge variant={user.role === "ADMIN" ? "default" : "secondary"}>
            {user.role === "ADMIN" && <ShieldCheck />}
            {roleLabels[user.role]}
          </Badge>
        ),
      },
      {
        id: "active",
        header: "Status",
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
            {user.active ? "Aktywny" : "Nieaktywny"}
          </Badge>
        ),
      },
      {
        id: "validity",
        header: "Okres ważności",
        className: "min-w-40 text-xs",
        cell: (user) => (
          <div>
            <p>od {formatDate(user.validFrom)}</p>
            <p className="text-muted-foreground">
              {user.validUntil ? `do ${formatDate(user.validUntil)}` : "bezterminowo"}
            </p>
          </div>
        ),
      },
      {
        id: "cases",
        header: "Aktywne case’y",
        align: "center",
        cell: (user) => <span className="font-medium tabular-nums">{user.activeAssignedCases}</span>,
      },
      {
        id: "login",
        header: "Ostatnie logowanie",
        className: "min-w-40 text-xs text-muted-foreground",
        cell: (user) => user.lastLoginAt ? formatDateTime(user.lastLoginAt) : "Jeszcze nigdy",
      },
      {
        id: "actions",
        header: <span className="sr-only">Akcje</span>,
        align: "end",
        cell: (user) => (
          <DropdownMenu>
            <DropdownMenuTrigger
              render={<Button variant="ghost" size="icon-sm" aria-label={`Akcje dla ${user.fullName}`} />}
            >
              <MoreHorizontal />
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-44">
              <DropdownMenuItem disabled={!canManageUsers} onClick={() => openEdit(user)}>
                <Pencil /> Edytuj
              </DropdownMenuItem>
              <DropdownMenuSeparator />
              <DropdownMenuItem
                variant="destructive"
                disabled={!user.active || !canManageUsers}
                onClick={() => setDeactivatedUser(user)}
              >
                <UserX /> Dezaktywuj
              </DropdownMenuItem>
              <DropdownMenuItem
                variant="destructive"
                disabled={!canManageUsers || user.id === currentAdministrationUserQuery.data?.id}
                onClick={() => setDeletedUser(user)}
              >
                <Trash2 /> Usuń użytkownika
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        ),
      },
    ],
    [canManageUsers, currentAdministrationUserQuery.data?.id],
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
      notify.success("Użytkownik został dezaktywowany", deactivatedUser.fullName)
      setDeactivatedUser(null)
    } catch (error) {
      notify.error("Nie udało się dezaktywować użytkownika", getErrorMessage(error))
    }
  }

  const deleteUser = async () => {
    if (!deletedUser) return
    try {
      await actions.delete.mutateAsync(deletedUser.id)
      notify.success("Użytkownik został usunięty", deletedUser.fullName)
      setDeletedUser(null)
    } catch (error) {
      notify.error("Nie udało się usunąć użytkownika", getErrorMessage(error))
    }
  }

  return (
    <>
      <PageHeader
        title="Użytkownicy"
        description="Konta i uprawnienia zespołu wsparcia"
        actions={
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => void usersQuery.refetch()}
              disabled={usersQuery.isFetching}
            >
              <RefreshCw className={usersQuery.isFetching ? "animate-spin" : undefined} />
              <span className="hidden sm:inline">Odśwież</span>
            </Button>
            <Button size="sm" onClick={openCreate} disabled={!canManageUsers}>
              <Plus /> Dodaj użytkownika
            </Button>
          </div>
        }
      />

      <main className="min-h-0 flex-1 overflow-y-auto bg-muted/20">
        <div className="mx-auto flex w-full max-w-[1600px] flex-col gap-4 p-4 md:p-6">
          <section className="grid gap-2 sm:grid-cols-3" aria-label="Podsumowanie użytkowników">
            <Metric label="Aktywne konta" value={activeCount} />
            <Metric label="Administratorzy" value={adminCount} />
            <Metric label="Przypisane aktywne case’y" value={assignedCount} />
          </section>

          <div className="flex flex-col gap-3">
            <div className="rounded-lg border bg-card p-3">
              <FilterBar>
                <SearchFilter value={search} onChange={setSearch} placeholder="Szukaj po nazwie lub e-mailu…" />
                <FilterSelect
                  value={role}
                  onChange={setRole}
                  placeholder="Rola"
                  allLabel="Wszystkie role"
                  options={[
                    { value: "USER", label: "Użytkownik" },
                    { value: "ADMIN", label: "Administrator" },
                  ]}
                />
                <FilterSelect
                  value={active}
                  onChange={setActive}
                  placeholder="Status"
                  allLabel="Wszystkie statusy"
                  options={[
                    { value: "active", label: "Aktywni" },
                    { value: "inactive", label: "Nieaktywni" },
                  ]}
                />
                {(search || role !== ALL_VALUE || active !== ALL_VALUE) && (
                  <Button variant="ghost" size="sm" onClick={resetFilters}>Wyczyść filtry</Button>
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
                    title="Nie znaleziono użytkowników"
                    description="Zmień kryteria wyszukiwania albo dodaj pierwsze konto."
                    action={<Button onClick={openCreate} disabled={!canManageUsers}><Plus /> Dodaj użytkownika</Button>}
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
              notify.success(editedUser ? "Zapisano zmiany użytkownika" : "Dodano użytkownika", input.fullName)
              setDialogOpen(false)
            } catch (error) {
              notify.error("Nie udało się zapisać użytkownika", getErrorMessage(error))
            }
          }}
        />
      ) : null}

      <ConfirmDialog
        open={Boolean(deactivatedUser)}
        onOpenChange={(open) => !open && setDeactivatedUser(null)}
        title="Dezaktywować użytkownika?"
        description={deactivatedUser ? `${deactivatedUser.fullName} utraci dostęp do aplikacji. Aktywne case’y pozostaną widoczne i będzie można je przepisać.` : undefined}
        confirmLabel="Dezaktywuj"
        destructive
        onConfirm={() => void deactivate()}
      />

      <ConfirmDialog
        open={Boolean(deletedUser)}
        onOpenChange={(open) => !open && setDeletedUser(null)}
        title="Usunąć użytkownika?"
        description={deletedUser ? `${deletedUser.fullName} zostanie trwale usunięty z listy użytkowników. Tej operacji nie można cofnąć.` : undefined}
        confirmLabel="Usuń"
        destructive
        onConfirm={() => void deleteUser()}
      />
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
      notify.warning("Uzupełnij wymagane pola", "Nazwa i adres e-mail są wymagane.")
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
            <DialogTitle>{user ? "Edytuj użytkownika" : "Dodaj użytkownika"}</DialogTitle>
            <DialogDescription>
              Dane są zapisywane w lokalnym serwisie demonstracyjnym.
            </DialogDescription>
          </DialogHeader>

          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Imię i nazwisko" required>
              <Input value={form.fullName} onChange={(event) => setForm({ ...form, fullName: event.target.value })} autoFocus />
            </Field>
            <Field label="E-mail" required>
              <Input type="email" value={form.email} onChange={(event) => setForm({ ...form, email: event.target.value })} />
            </Field>
            <Field label="Rola">
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
                  <SelectItem value="USER">Użytkownik</SelectItem>
                  <SelectItem value="ADMIN">Administrator</SelectItem>
                </SelectContent>
              </Select>
            </Field>
            <Field label="Ważny od">
              <Input type="date" value={form.validFrom} onChange={(event) => setForm({ ...form, validFrom: event.target.value })} />
            </Field>
            <Field label="Ważny do (opcjonalnie)">
              <Input type="date" value={form.validUntil ?? ""} onChange={(event) => setForm({ ...form, validUntil: event.target.value || undefined })} />
            </Field>
          </div>

          <div className="flex items-center justify-between rounded-lg border p-3">
            <div>
              <Label htmlFor="user-active">Aktywne konto</Label>
              <p className="text-xs text-muted-foreground">Użytkownik może zalogować się do aplikacji.</p>
            </div>
            <Switch id="user-active" checked={form.active} onCheckedChange={(checked) => setForm({ ...form, active: checked })} />
          </div>

          <fieldset className="grid gap-2 rounded-lg border p-3">
            <legend className="px-1 text-sm font-medium">Uprawnienia indywidualne</legend>
            <p className="text-xs text-muted-foreground">Uprawnienia administracyjne można nadać wyłącznie roli Administrator. Rola Użytkownik nie uzyskuje dostępu przez indywidualny grant.</p>
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
                  {administrationPermissionLabels[permission]}
                </label>
              ))}
            </div>
          </fieldset>

          <DialogFooter>
            <DialogClose render={<Button variant="outline" type="button" />}>Anuluj</DialogClose>
            <Button type="submit" disabled={saving}>{saving ? "Zapisywanie…" : "Zapisz"}</Button>
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
  return error instanceof Error ? error.message : "Spróbuj ponownie."
}
