import { afterEach, describe, expect, it, vi } from "vitest"
import { apiAdministrationSettingsRepository as repository } from "@/lib/services/api/administration-settings-adapter"

afterEach(() => vi.unstubAllGlobals())
describe("provider onboarding transport", () => {
  it("saves Teams team selection and default customer through its protected endpoint", async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ id: "integration", provider: "TEAMS", displayName: "Sandbox", status: "CONFIGURING", health: "UNKNOWN", secretConfigured: true }), { headers: { "content-type": "application/json" } }))
    vi.stubGlobal("fetch", fetch)
    await repository.configureIntegration({ platform: "teams", displayName: "Sandbox", workspaceId: "", workspaceName: "", secretRef: "", team: "https://teams.microsoft.com/l/team/opaque/conversations?groupId=team", defaultCustomerId: "customer", automaticDiscovery: true })
    expect(fetch.mock.calls[0][0]).toBe("/api/v1/admin/integrations/teams")
    expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({ displayName: "Sandbox", team: "https://teams.microsoft.com/l/team/opaque/conversations?groupId=team", defaultCustomerId: "customer", automaticDiscovery: true })
  })
  it("edits Slack without sending browser secrets and schedules discovery independently", async () => {
    const fetch = vi.fn().mockImplementation(async () => new Response(JSON.stringify({ id: "integration", provider: "SLACK", status: "ENABLED", health: "HEALTHY" }), { headers: { "content-type": "application/json" } }))
    vi.stubGlobal("fetch", fetch)
    await repository.configureIntegration({ id: "integration", platform: "slack", displayName: "Staging", workspaceId: "T123", workspaceName: "", secretRef: "", automaticDiscovery: false })
    await repository.setAutomaticDiscovery("integration", true)
    expect(fetch.mock.calls[0][0]).toBe("/api/v1/admin/integrations/integration/slack")
    expect(JSON.parse(fetch.mock.calls[0][1].body)).toMatchObject({ displayName: "Staging", workspaceExternalId: "T123", automaticDiscovery: false })
    expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual({ automaticDiscovery: true })
  })
  it("maps only public runtime metadata and provides the package only for a configured Teams bot", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify([{ provider: "SLACK", callbackUrl: "https://instance.example.test/slack", botConfigured: true }, { provider: "TEAMS", callbackUrl: "https://instance.example.test/teams", botConfigured: false }]), { headers: { "content-type": "application/json" } })))
    expect(await repository.getProviderSetup()).toEqual([{ platform: "slack", callbackUrl: "https://instance.example.test/slack", botConfigured: true, clientId: undefined, packageUrl: undefined }, { platform: "teams", callbackUrl: "https://instance.example.test/teams", botConfigured: false, clientId: undefined, packageUrl: undefined }])
  })
})
