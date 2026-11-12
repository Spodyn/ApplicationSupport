import { DirectMessagesPage } from "@/components/direct-messages/direct-messages-page"

export default async function DirectMessagesRoute({ searchParams }: { searchParams: Promise<{ conversation?: string }> }) {
  const { conversation } = await searchParams
  const id = typeof conversation === "string" && /^[\da-f]{8}(?:-[\da-f]{4}){3}-[\da-f]{12}$/i.test(conversation)
    ? conversation : ""
  return <DirectMessagesPage initialConversation={id} />
}
