import { fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"
import { AdministrationSettingsPage } from "@/components/settings/administration-settings-page"
import { LanguageProvider } from "@/lib/i18n/provider"
import type { AdministrationSettings } from "@/lib/domain/administration"

const fixture = vi.hoisted(() => ({ data: null as AdministrationSettings | null, save: vi.fn() }))
vi.mock("@/lib/services/queries", () => ({
  useAdministrationSettings: () => ({ data: fixture.data, isLoading: false, isError: false, isFetching: false, refetch: vi.fn() }),
  useAdministrationSettingsActions: () => ({ saveSection: {isPending: false, mutateAsync: fixture.save} }),
  useProviderSetup: () => ({data: []}),
}))

function settings(): AdministrationSettings {
  return {
    general: {organizationName: "Fixture", interfaceLanguage: "pl", compactMode: false},
    sla: {firstResponseMinutes: 15, nextResponseMinutes: 30, customerWaitingMinutes: 1440,
      unclaimedReminderMinutes: 15, inProgressReminderMinutes: 30, warningBeforeDeadlineMinutes: 15,
      repeatedBreachMinutes: 120, businessHoursOnly: true, pauseWhileWaiting: true},
    schedule: {timezone: "UTC", days: [], exceptions: []},
    outOfOffice: {enabled: false, template: "Fixture", sendOncePerClosure: true},
    integrations: [{id: "real-teams-id", platform: "teams", displayName: "Configured Teams", workspace: "Sandbox",
      workspaceId: "team", status: "enabled", health: "healthy", hasSecretReference: true}],
    customers: [{id: "real-customer-id", name: "Customer display name", active: true}],
    channels: [
      {id: "mapped", platform: "teams", externalChannelId: "external", channelName: "mapped-channel",
        customerId: "real-customer-id", customer: "Customer display name", ignored: false, groupingStrategy: "TEAMS_ROOT_REPLIES", active: true},
      {id: "unmapped", platform: "slack", externalChannelId: "unmapped", channelName: "unmapped-channel",
        customer: "", ignored: false, groupingStrategy: "SLACK_ROOT_THREAD", active: true},
    ], notifications: [], rolePermissions: [],
  }
}

function open(language: "en" | "pl" = "en") {
  document.documentElement.lang = language
  return render(<LanguageProvider initialLanguage={language}><AdministrationSettingsPage /></LanguageProvider>)
}

describe("Settings shows authoritative names and valid configuration choices", () => {
  beforeEach(() => { fixture.data = settings(); fixture.save.mockReset().mockResolvedValue(undefined) })

  it.each(["en", "pl"] as const)("shows mapped and unmapped customer names before opening a selector in %s", async language => {
    open(language)
    fireEvent.click(screen.getByRole("tab", {name: language === "en" ? "Channels" : "Kanały"}))
    const panel = await screen.findByRole("tabpanel", {name: language === "en" ? "Channels" : "Kanały"})
    const mapped = within(panel).getByRole("row", {name: /^Microsoft Teams/})
    expect(within(mapped).getByRole("combobox")).toHaveTextContent("Customer display name")
    const unmapped = within(panel).getByRole("row", {name: /^Slack/})
    expect(within(unmapped).getByRole("combobox")).toHaveTextContent(language === "en" ? "Unassigned" : "Nie przypisano")
    expect(panel).not.toHaveTextContent(/real-customer-id|__unmapped__/)
  })

  it("retains the name of an existing inactive customer without offering a new inactive assignment", async () => {
    fixture.data!.customers[0].active = false
    open()
    fireEvent.click(screen.getByRole("tab", {name: "Channels"}))
    const panel = await screen.findByRole("tabpanel", {name: "Channels"})
    expect(within(panel).getByRole("row", {name: /^Microsoft Teams/})).toHaveTextContent("Customer display name")
    fireEvent.click(within(panel).getByRole("row", {name: /^Microsoft Teams/}).querySelector("button")!)
    expect(screen.queryByRole("option", {name: "Customer display name"})).not.toBeInTheDocument()
  })

  it.each(["en", "pl"] as const)("uses the real configured Teams identity when creating a destination in %s", async language => {
    open(language)
    fireEvent.click(screen.getByRole("tab", {name: language === "en" ? "Notifications" : "Powiadomienia"}))
    fireEvent.click(await screen.findByRole("button", {name: language === "en" ? "Add destination" : "Dodaj cel"}))
    const dialog = await screen.findByRole("dialog")
    expect(within(dialog).getByRole("combobox")).toHaveTextContent("Microsoft Teams · Sandbox")
    fireEvent.change(within(dialog).getByRole("textbox", {name: language === "en" ? "Name" : "Nazwa"}), {target: {value: "Fixture alerts"}})
    fireEvent.change(within(dialog).getByRole("textbox", {name: language === "en" ? "Destination channel" : "Kanał docelowy"}), {target: {value: "sandbox-channel"}})
    fireEvent.click(within(dialog).getByRole("button", {name: language === "en" ? "Save" : "Zapisz"}))
    await waitFor(() => expect(fixture.save).toHaveBeenCalledWith({key: "notifications", value: [expect.objectContaining({integrationId: "real-teams-id", provider: "teams"})]}))
  })

  it("does not permit saving a destination without a configured integration", async () => {
    fixture.data!.integrations = []
    open()
    fireEvent.click(screen.getByRole("tab", {name: "Notifications"}))
    fireEvent.click(await screen.findByRole("button", {name: "Add destination"}))
    const dialog = await screen.findByRole("dialog")
    fireEvent.change(within(dialog).getByRole("textbox", {name: "Name"}), {target: {value: "Fixture alerts"}})
    fireEvent.change(within(dialog).getByRole("textbox", {name: "Destination channel"}), {target: {value: "sandbox-channel"}})
    expect(within(dialog).getByRole("button", {name: "Save"})).toBeDisabled()
    expect(fixture.save).not.toHaveBeenCalled()
    expect(dialog).not.toHaveTextContent("int-slack")
  })

  it("preserves a draft but blocks saving after its configured integration disappears", async () => {
    const view = open()
    fireEvent.click(screen.getByRole("tab", {name: "Notifications"}))
    fireEvent.click(await screen.findByRole("button", {name: "Add destination"}))
    const dialog = await screen.findByRole("dialog")
    fireEvent.change(within(dialog).getByRole("textbox", {name: "Name"}), {target: {value: "Unsent configuration"}})
    fireEvent.change(within(dialog).getByRole("textbox", {name: "Destination channel"}), {target: {value: "sandbox-channel"}})
    expect(within(dialog).getByRole("button", {name: "Save"})).toBeEnabled()
    fixture.data = {...fixture.data!, integrations: []}
    view.rerender(<LanguageProvider initialLanguage="en"><AdministrationSettingsPage /></LanguageProvider>)
    expect(within(dialog).getByRole("textbox", {name: "Name"})).toHaveValue("Unsent configuration")
    expect(within(dialog).getByRole("button", {name: "Save"})).toBeDisabled()
    expect(fixture.save).not.toHaveBeenCalled()
  })

  it.each(["en", "pl"] as const)("localizes every timestamp in the SLA example in %s", async language => {
    open(language)
    fireEvent.click(screen.getByRole("tab", {name: "SLA"}))
    const panel = await screen.findByRole("tabpanel", {name: "SLA"})
    if (language === "en") {
      expect(panel).not.toHaveTextContent("pon.")
      expect(panel).toHaveTextContent(/Mon.*16:30/)
    } else expect(panel).toHaveTextContent(/pon.*16:30/)
  })
})
