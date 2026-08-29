import type {
  AdministrationSettings,
  ChannelGroupingStrategy,
  ManagedChannel,
} from "@/lib/domain/administration"
import type { AdministrationSettingsRepository } from "@/lib/services/administration"
import { mockAdministrationSettingsRepository } from "@/lib/services/administration"
import { mapApiChannel } from "./channel-adapter"
import { browserApiTransport } from "./http-transport"

type ApiChannelRecord = {
  id: string
  integrationId: string
  provider: "SLACK" | "TEAMS" | "TELEGRAM"
  externalChannelId: string
  name: string
  customerId?: string | null
  customerName?: string | null
  ignored: boolean
  groupingStrategy: ChannelGroupingStrategy
  active: boolean
  lastMessageAt?: string | null
}

function mapChannel(channel: ApiChannelRecord): ManagedChannel {
  const activity = channel.active ? "Aktywny" : "Nieaktywny"
  return {
    id: channel.id,
    platform: mapApiChannel(channel.provider),
    externalChannelId: channel.externalChannelId,
    channelName: `${channel.name} · ${activity}`,
    customer: channel.customerName ?? "Nie przypisano",
    ignored: channel.ignored,
    groupingStrategy: channel.groupingStrategy,
    active: channel.active,
    lastMessageAt: channel.lastMessageAt ?? undefined,
  }
}

async function listChannels(): Promise<ManagedChannel[]> {
  const channels = await browserApiTransport.request<ApiChannelRecord[]>({
    method: "GET",
    path: "/api/v1/admin/channels",
  })
  return channels.map(mapChannel)
}

export const apiAdministrationSettingsRepository: AdministrationSettingsRepository = {
  async get() {
    const [settings, channels] = await Promise.all([
      mockAdministrationSettingsRepository.get(),
      listChannels(),
    ])
    return { ...settings, channels }
  },

  saveSection<K extends keyof AdministrationSettings>(
    key: K,
    value: AdministrationSettings[K],
  ) {
    return mockAdministrationSettingsRepository.saveSection(key, value)
  },

  configureIntegration(id, workspace) {
    return mockAdministrationSettingsRepository.configureIntegration(id, workspace)
  },

  setIntegrationStatus(id, status) {
    return mockAdministrationSettingsRepository.setIntegrationStatus(id, status)
  },

  testIntegration(id) {
    return mockAdministrationSettingsRepository.testIntegration(id)
  },

  async setChannelIgnored(id, ignored) {
    await browserApiTransport.request<ApiChannelRecord>({
      method: "PATCH",
      path: `/api/v1/admin/channels/${encodeURIComponent(id)}`,
      body: { ignored },
    })
  },

  toggleNotification(id, enabled) {
    return mockAdministrationSettingsRepository.toggleNotification(id, enabled)
  },
}
