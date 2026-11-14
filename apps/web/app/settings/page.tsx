import { AdministrationSettingsPage } from "@/components/settings/administration-settings-page"

export default async function SettingsRoute({ searchParams }: { searchParams: Promise<{ tab?: string }> }) {
  const { tab } = await searchParams
  return <AdministrationSettingsPage initialTab={tab === "integrations" ? "integrations" : "general"} />
}
