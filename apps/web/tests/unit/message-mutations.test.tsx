import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { describe, expect, it, vi } from "vitest"
import type { ApiTransport } from "@usi/api-client/generated"
import type { InboxMessage } from "@/lib/domain/inbox"
import { createApiInboxRepository, mapCaseMessage } from "@/lib/services/api/inbox-adapter"
import { LanguageProvider } from "@/lib/i18n/provider"
import { MessageMutationControls } from "@/components/cases/message-mutation-controls"

const actions = vi.hoisted(() => ({ mutate: {isPending: false, mutateAsync: vi.fn()}, retry: {isPending: false, mutate: vi.fn()} }))
vi.mock("@/lib/services/queries", () => ({useInboxMessageMutations: () => actions}))

const message: InboxMessage = {id: "message", kind: "support", sender: "Agent", body: "Original text", createdAt: "2026-11-28T10:00:00Z",
  mutation: {isAuthor: true, canEdit: true, canDelete: true, canRetry: false, revision: 0}}

describe("provider-neutral message mutations", () => {
  it("translates commands behind the service boundary and preserves idempotency and revision", async () => {
    const request = vi.fn().mockResolvedValue({operationId: "operation", state: "QUEUED"})
    const repository = createApiInboxRepository({request} as ApiTransport)
    expect(await repository.mutateMessage("message", {action: "edit", body: "Changed", expectedRevision: 4, idempotencyKey: "stable-key"})).toEqual({operationId: "operation"})
    expect(request).toHaveBeenLastCalledWith(expect.objectContaining({method: "POST", path: "/api/v1/messages/message/mutations", body: {action: "EDIT", body: "Changed", expectedRevision: 4}, headers: {"Idempotency-Key": "stable-key"}}))
    await repository.mutateMessage("message", {action: "delete", expectedRevision: 5, idempotencyKey: "delete-key"})
    expect(request).toHaveBeenLastCalledWith(expect.objectContaining({body: {action: "DELETE", body: null, expectedRevision: 5}}))
    await repository.retryMessageMutation("message", "operation", "retry-key")
    expect(request).toHaveBeenLastCalledWith(expect.objectContaining({path: "/api/v1/messages/message/mutations/operation/retry", headers: {"Idempotency-Key": "retry-key"}}))
    const failure = new Error("provider unavailable")
    request.mockRejectedValue(failure)
    await expect(repository.mutateMessage("message", {action: "edit", body: "Changed", expectedRevision: 4, idempotencyKey: "stable-key"})).rejects.toBe(failure)
  })

  it("maps authoritative capabilities without guessing from provider, ownership or delivery", () => {
    const mapped = mapCaseMessage("case", {id: "message", kind: "SUPPORT", body: "Original", bodyFormat: "PLAIN_TEXT", inbound: false,
      deliveryStatus: "SENT", providerCreatedAt: null, createdAt: "2026-11-28T10:00:00Z", editedAt: null, deletedAt: null, authorName: "Agent", attachments: [],
      mutation: {isAuthor: true, canEdit: false, canDelete: false, canRetry: false, revision: 3, operationId: "operation", action: "EDIT", state: "UNCERTAIN", editUnavailable: "MUTATION_PENDING", deleteUnavailable: "MUTATION_PENDING", errorCode: "TEAMS_MUTATION_UNCONFIRMED"}})
    expect(mapped.mutation).toMatchObject({canEdit: false, canDelete: false, state: "uncertain", action: "edit", revision: 3})
  })

  it("explains missing provider identities and separate attachment resource restrictions without offering a command", async () => {
    const unavailable = {...message, body: "", mutation: {...message.mutation!, canEdit: false, canDelete: false,
      editUnavailable: "NO_TEXT_RESOURCE", deleteUnavailable: "MULTIPLE_PROVIDER_RESOURCES"}}
    const view = render(<LanguageProvider initialLanguage="en"><MessageMutationControls message={unavailable} caseId="case" /></LanguageProvider>)
    fireEvent.click(screen.getByRole("button", {name: "Message actions"}))
    expect(await screen.findByRole("menuitem", {name: /^Edit message$/})).toHaveAttribute("aria-disabled", "true")
    expect(screen.getByRole("menuitem", {name: /^Delete message$/})).toHaveAttribute("aria-disabled", "true")
    expect(screen.getByText(/Attachment-only messages cannot be edited/)).toBeVisible()
    expect(screen.getByText(/Complete deletion is unavailable/)).toBeVisible()
    view.rerender(<LanguageProvider initialLanguage="en"><MessageMutationControls message={{...message, mutation: {...unavailable.mutation, editUnavailable: "PROVIDER_TARGET_UNAVAILABLE", deleteUnavailable: "PROVIDER_TARGET_UNAVAILABLE"}}} caseId="case" /></LanguageProvider>)
    expect(screen.getAllByText(/The provider did not return a safe target/)).toHaveLength(2)
  })

  it.each(["en", "pl"] as const)("keeps the editor open after acceptance until provider confirmation in %s", async (language) => {
    actions.mutate.mutateAsync.mockResolvedValue({operationId: "operation", state: "queued"})
    const labels = language === "en" ? ["Message actions", "Edit message", "New message text", "Save changes"] : ["Akcje wiadomości", "Edytuj wiadomość", "Nowa treść wiadomości", "Zapisz zmiany"]
    const view = render(<LanguageProvider initialLanguage={language}><MessageMutationControls message={message} caseId="case" /></LanguageProvider>)
    fireEvent.click(screen.getByRole("button", {name: labels[0]}))
    fireEvent.click(await screen.findByRole("menuitem", {name: labels[1]}))
    fireEvent.change(screen.getByRole("textbox", {name: labels[2]}), {target: {value: "Edited text"}})
    fireEvent.click(screen.getByRole("button", {name: labels[3]}))
    await waitFor(() => expect(actions.mutate.mutateAsync).toHaveBeenCalled())
    expect(screen.getByRole("textbox", {name: labels[2]})).toHaveValue("Edited text")
    view.rerender(<LanguageProvider initialLanguage={language}><MessageMutationControls message={{...message, mutation: {...message.mutation!, canEdit: false, canDelete: false, operationId: "operation", state: "running"}}} caseId="case" /></LanguageProvider>)
    expect(screen.getByRole("textbox", {name: labels[2]})).toBeDisabled()
    view.rerender(<LanguageProvider initialLanguage={language}><MessageMutationControls message={{...message, body: "Edited text", edited: true, mutation: {...message.mutation!, revision: 1, operationId: "operation", state: "confirmed"}}} caseId="case" /></LanguageProvider>)
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument()
  })
})
