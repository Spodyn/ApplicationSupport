import type { InboxCase, InboxMessage } from "@/lib/domain/inbox"
import type { Channel, SlaState, User } from "@/lib/domain/shared"
import { mockUsers } from "@/mocks/users"

export interface InboxCaseRecord
  extends Omit<
    InboxCase,
    | "unreadForCurrentUser"
    | "snoozedForCurrentUserUntil"
    | "currentUserRestrictedByIgnore"
  > {
  unreadForUserIds: string[]
  snoozedUntilByUser: Record<string, string>
  restrictedUserIds: string[]
}

const [anna, piotr, magda, tomasz, , rafal] = mockUsers
const now = Date.now()

const minutesAgo = (minutes: number) => new Date(now - minutes * 60_000).toISOString()
const dueIn = (minutes: number) => new Date(now + minutes * 60_000).toISOString()

interface CaseSeed {
  reference: string
  subject: string
  platform: Channel
  sourceChannel: string
  customer: [string, string]
  status: InboxCase["status"]
  owner?: User
  unread?: boolean
  activityMinutesAgo: number
  slaState: SlaState
  slaMinutes?: number
  ignored?: 0 | 1 | 2
  snoozed?: boolean
  priority: InboxCase["metadata"]["priority"]
  category: string
  product: string
  environment?: string
  preview: string
  tags: string[]
  relatedCase?: InboxCase["relatedCase"]
}

const seeds: CaseSeed[] = [
  {
    reference: "ZG-2048",
    subject: "Płatność pobrana dwukrotnie po odnowieniu subskrypcji",
    platform: "slack",
    sourceChannel: "#rozliczenia-premium",
    customer: ["Northstar Retail", "Joanna Borkowska"],
    status: "verification",
    owner: magda,
    unread: true,
    activityMinutesAgo: 3,
    slaState: "breached",
    slaMinutes: -18,
    priority: "Krytyczny",
    category: "Rozliczenia",
    product: "Subskrypcje",
    preview: "W panelu widzę dwa obciążenia za ten sam okres rozliczeniowy.",
    tags: ["płatności", "duplikat", "enterprise"],
    relatedCase: { reference: "ZG-1982", subject: "Podwójna autoryzacja karty przy odnowieniu" },
  },
  {
    reference: "ZG-2051",
    subject: "Brak możliwości logowania po włączeniu SSO",
    platform: "teams",
    sourceChannel: "IT Helpdesk",
    customer: ["Orbit Labs", "Marek Woźniak"],
    status: "new",
    owner: anna,
    unread: true,
    activityMinutesAgo: 7,
    slaState: "at_risk",
    slaMinutes: 24,
    priority: "Wysoki",
    category: "Dostęp",
    product: "Logowanie SSO",
    preview: "Po przekierowaniu z Entra ID wracamy na pustą stronę.",
    tags: ["sso", "logowanie", "entra"],
  },
  {
    reference: "ZG-2044",
    subject: "Webhook zwraca 502 dla zdarzeń invoice.paid",
    platform: "telegram",
    sourceChannel: "@nordbyte_ops",
    customer: ["Nordbyte", "Aleksander Lis"],
    status: "waiting_for_customer",
    activityMinutesAgo: 11,
    slaState: "paused",
    priority: "Wysoki",
    category: "API",
    product: "Webhooks",
    preview: "Wysyłam identyfikator requestu i fragment odpowiedzi z bramki.",
    tags: ["api", "webhook", "502"],
  },
  {
    reference: "ZG-2053",
    subject: "Nie zgadza się liczba aktywnych licencji",
    platform: "slack",
    sourceChannel: "#support-enterprise",
    customer: ["Vistula Energy", "Natalia Kurek"],
    status: "new",
    unread: true,
    activityMinutesAgo: 14,
    slaState: "on_track",
    slaMinutes: 118,
    priority: "Średni",
    category: "Konto",
    product: "Licencje",
    preview: "Panel pokazuje 184 miejsca, a w umowie mamy 200.",
    tags: ["licencje", "billing"],
  },
  {
    reference: "ZG-2038",
    subject: "Eksport CSV ucina polskie znaki w nazwach klientów",
    platform: "teams",
    sourceChannel: "Obsługa danych",
    customer: ["Meridian Logistics", "Karolina Mróz"],
    status: "partially_ignored",
    activityMinutesAgo: 22,
    slaState: "on_track",
    slaMinutes: 205,
    ignored: 1,
    priority: "Niski",
    category: "Eksport",
    product: "Raporty",
    preview: "Problem występuje tylko przy eksporcie w kodowaniu domyślnym.",
    tags: ["csv", "kodowanie"],
  },
  {
    reference: "ZG-2029",
    subject: "Powiadomienia push nie docierają na urządzenia z Androidem 15",
    platform: "telegram",
    sourceChannel: "@aurora_mobile",
    customer: ["Aurora Health", "Patryk Gajda"],
    status: "verification",
    owner: tomasz,
    snoozed: true,
    activityMinutesAgo: 37,
    slaState: "paused",
    priority: "Średni",
    category: "Mobile",
    product: "Powiadomienia",
    preview: "Czekamy na wynik testu z wersją aplikacji 6.12.1.",
    tags: ["android", "push", "mobile"],
  },
  {
    reference: "ZG-2014",
    subject: "Prośba o usunięcie testowych wiadomości z kanału",
    platform: "slack",
    sourceChannel: "#wdrozenie-acme",
    customer: ["Acme Polska", "Oliwia Król"],
    status: "ignored",
    activityMinutesAgo: 49,
    slaState: "paused",
    ignored: 2,
    priority: "Niski",
    category: "Porządkowe",
    product: "Wiadomości",
    preview: "To wpis testowy utworzony podczas szkolenia zespołu.",
    tags: ["test", "bez-akcji"],
  },
  {
    reference: "ZG-1998",
    subject: "Faktura korygująca nie pojawia się w panelu",
    platform: "teams",
    sourceChannel: "Finanse — Polska",
    customer: ["Baltic Foods", "Michał Sowa"],
    status: "resolved",
    owner: anna,
    unread: true,
    activityMinutesAgo: 64,
    slaState: "paused",
    priority: "Średni",
    category: "Rozliczenia",
    product: "Faktury",
    preview: "Korekta jest już widoczna. Dziękuję za szybką pomoc.",
    tags: ["faktura", "korekta"],
  },
  {
    reference: "ZG-2050",
    subject: "Czy można zwiększyć limit zapytań API dla środowiska produkcyjnego?",
    platform: "telegram",
    sourceChannel: "@atlas_platform",
    customer: ["Atlas Commerce", "Hubert Pawlak"],
    status: "new",
    owner: piotr,
    unread: true,
    activityMinutesAgo: 18,
    slaState: "on_track",
    slaMinutes: 142,
    priority: "Średni",
    category: "API",
    product: "Limity",
    preview: "W przyszłym tygodniu uruchamiamy kampanię i spodziewamy się większego ruchu.",
    tags: ["api", "limity", "produkcja"],
  },
  {
    reference: "ZG-2041",
    subject: "Załączniki powyżej 10 MB zatrzymują synchronizację wątku",
    platform: "slack",
    sourceChannel: "#help-platform",
    customer: ["Lumen Studio", "Iga Kalinowska"],
    status: "verification",
    owner: anna,
    activityMinutesAgo: 31,
    slaState: "on_track",
    slaMinutes: 156,
    priority: "Wysoki",
    category: "Synchronizacja",
    product: "Załączniki",
    preview: "Mniejszy plik przechodzi poprawnie, większy pozostaje w kolejce.",
    tags: ["załączniki", "sync"],
  },
  {
    reference: "ZG-2036",
    subject: "Błędna strefa czasowa w cotygodniowym raporcie SLA",
    platform: "teams",
    sourceChannel: "Service Desk",
    customer: ["Helios Bank", "Damian Zięba"],
    status: "waiting_for_customer",
    activityMinutesAgo: 43,
    slaState: "paused",
    priority: "Średni",
    category: "Raporty",
    product: "SLA",
    preview: "Raport kończy dobę o 22:00 zamiast o północy czasu lokalnego.",
    tags: ["sla", "timezone", "raport"],
  },
  {
    reference: "ZG-2022",
    subject: "Kanał alarmowy nie synchronizuje wiadomości od godziny",
    platform: "slack",
    sourceChannel: "#incident-critical",
    customer: ["Evergreen Cloud", "Łukasz Banaś"],
    status: "verification",
    owner: magda,
    unread: true,
    activityMinutesAgo: 5,
    slaState: "breached",
    slaMinutes: -7,
    priority: "Krytyczny",
    category: "Integracje",
    product: "Slack Connector",
    preview: "Ostatnia wiadomość w skrzynce jest z 10:42, na Slacku mamy już sześć kolejnych.",
    tags: ["incident", "slack", "sync"],
  },
  {
    reference: "ZG-2007",
    subject: "Bot nie rozpoznaje komendy /status w języku polskim",
    platform: "telegram",
    sourceChannel: "@cobalt_support",
    customer: ["Cobalt Media", "Wiktoria Polak"],
    status: "partially_ignored",
    activityMinutesAgo: 73,
    slaState: "on_track",
    slaMinutes: 260,
    ignored: 1,
    priority: "Niski",
    category: "Bot",
    product: "Telegram Connector",
    preview: "Angielski odpowiednik działa, polski zwraca listę dostępnych komend.",
    tags: ["telegram", "lokalizacja"],
  },
  {
    reference: "ZG-2001",
    subject: "Reset uwierzytelniania dwuskładnikowego dla administratora",
    platform: "teams",
    sourceChannel: "Bezpieczeństwo",
    customer: ["Greenfield SA", "Szymon Dudek"],
    status: "verification",
    owner: anna,
    snoozed: true,
    activityMinutesAgo: 88,
    slaState: "paused",
    priority: "Wysoki",
    category: "Dostęp",
    product: "2FA",
    preview: "Dokument potwierdzający tożsamość został przekazany bezpiecznym kanałem.",
    tags: ["2fa", "bezpieczeństwo"],
  },
  {
    reference: "ZG-2054",
    subject: "Duplikaty powiadomień po ponownym połączeniu workspace",
    platform: "slack",
    sourceChannel: "#product-support",
    customer: ["Nova Works", "Emilia Baran"],
    status: "new",
    unread: true,
    activityMinutesAgo: 9,
    slaState: "at_risk",
    slaMinutes: 35,
    priority: "Wysoki",
    category: "Integracje",
    product: "Slack Connector",
    preview: "Każde nowe zdarzenie pojawia się teraz dwa razy.",
    tags: ["duplikaty", "slack"],
  },
  {
    reference: "ZG-1989",
    subject: "Historia rozmowy nie obejmuje wiadomości sprzed migracji",
    platform: "teams",
    sourceChannel: "Migracja danych",
    customer: ["Polaris Group", "Kamil Kopeć"],
    status: "resolved",
    owner: piotr,
    activityMinutesAgo: 132,
    slaState: "paused",
    priority: "Średni",
    category: "Migracja",
    product: "Historia rozmów",
    preview: "Po ponownym indeksowaniu historia jest już kompletna.",
    tags: ["migracja", "historia"],
  },
  {
    reference: "ZG-2046",
    subject: "Nieaktualny status SLA zwracany przez endpoint /cases",
    platform: "telegram",
    sourceChannel: "@vector_dev",
    customer: ["Vector Systems", "Robert Cichy"],
    status: "verification",
    owner: tomasz,
    unread: true,
    activityMinutesAgo: 27,
    slaState: "on_track",
    slaMinutes: 173,
    priority: "Średni",
    category: "API",
    product: "Cases API",
    preview: "W aplikacji wartość jest prawidłowa, ale API nadal zwraca at_risk.",
    tags: ["api", "sla", "cache"],
  },
  {
    reference: "ZG-1977",
    subject: "Brak avatarów w testowym kanale demonstracyjnym",
    platform: "slack",
    sourceChannel: "#demo-sandbox",
    customer: ["Demo Partner", "Monika Sitek"],
    status: "ignored",
    activityMinutesAgo: 180,
    slaState: "paused",
    ignored: 2,
    priority: "Niski",
    category: "Wygląd",
    product: "Demo",
    preview: "To nie wpływa na środowisko produkcyjne i nie wymaga działania.",
    tags: ["demo", "sandbox"],
  },
]

export const mockInboxCaseRecords: InboxCaseRecord[] = seeds.map((seed, index) => {
  const ignored = seed.ignored ?? 0
  const createdAt = minutesAgo(seed.activityMinutesAgo + 720 + index * 37)
  const updatedAt = minutesAgo(seed.activityMinutesAgo)
  return {
    id: `inbox-${index + 1}`,
    reference: seed.reference,
    subject: seed.subject,
    platform: seed.platform,
    sourceChannel: seed.sourceChannel,
    customer: {
      id: `customer-${index + 1}`,
      name: seed.customer[0],
      contactName: seed.customer[1],
    },
    status: seed.status,
    owner: seed.owner,
    unreadForUserIds: seed.unread ? [anna.id] : [],
    snoozedUntilByUser: seed.snoozed ? { [anna.id]: dueIn(480 + index * 10) } : {},
    restrictedUserIds: [],
    lastMessagePreview: seed.preview,
    createdAt,
    updatedAt,
    sla: {
      state: seed.slaState,
      dueAt: typeof seed.slaMinutes === "number" ? dueIn(seed.slaMinutes) : undefined,
    },
    ignoreVotes: {
      current: ignored,
      required: 2,
      voters: ignored === 2 ? [magda.fullName, rafal.fullName] : ignored === 1 ? [rafal.fullName] : [],
    },
    metadata: {
      priority: seed.priority,
      category: seed.category,
      product: seed.product,
      environment: seed.environment ?? "Produkcja",
      tags: seed.tags,
    },
    relatedCase: seed.relatedCase,
    waitingUntil:
      seed.status === "waiting_for_customer" ? dueIn(24 * 60 - seed.activityMinutesAgo) : undefined,
    activity: [
      {
        id: `${seed.reference}-activity-1`,
        label: `Case utworzony z ${seed.platform === "slack" ? "kanału Slack" : seed.platform === "teams" ? "Microsoft Teams" : "Telegrama"}`,
        createdAt,
      },
      ...(seed.owner
        ? [{
            id: `${seed.reference}-activity-2`,
            label: "Przypisano opiekuna",
            createdAt: minutesAgo(seed.activityMinutesAgo + 210),
            author: seed.owner.fullName,
          }]
        : []),
      ...(ignored > 0
        ? [{
            id: `${seed.reference}-activity-3`,
            label: `Oddano głos ignorowania (${ignored}/2)`,
            createdAt: minutesAgo(seed.activityMinutesAgo + 70),
            author: rafal.fullName,
          }]
        : []),
      {
        id: `${seed.reference}-activity-4`,
        label: "Odebrano ostatnią wiadomość",
        createdAt: updatedAt,
        author: seed.customer[1],
      },
    ],
  }
})

function buildThread(item: InboxCaseRecord, caseIndex: number): InboxMessage[] {
  const total = 18
  const customerBodies = [
    `Dzień dobry, potrzebujemy pomocy w sprawie: ${item.subject.toLowerCase()}.`,
    "Problem udało się odtworzyć na dwóch kontach. Wysyłam dodatkowe szczegóły.",
    "Czy możecie potwierdzić, czy po Waszej stronie widać tę samą nieprawidłowość?",
    item.lastMessagePreview,
  ]
  const supportBodies = [
    "Dziękuję za zgłoszenie. Sprawdzam konfigurację oraz ostatnie zdarzenia integracji.",
    "Mam już pierwsze wyniki. Potrzebuję jeszcze identyfikatora operacji, aby połączyć logi.",
    "Zweryfikowaliśmy dane po naszej stronie. Zespół techniczny analizuje teraz konkretny request.",
    "Aktualizacja: przyczyna została zawężona, wrócę z kolejną informacją w ramach bieżącego SLA.",
  ]

  return Array.from({ length: total }, (_, index): InboxMessage => {
    const createdAt = minutesAgo((total - index) * 47 + caseIndex * 3)
    if (index === 0 || index === 6 || index === 13) {
      return {
        id: `${item.id}-message-${index}`,
        kind: "system",
        body:
          index === 0
            ? `Rozmowa zaimportowana z ${item.sourceChannel}`
            : index === 6
              ? item.owner
                ? `${item.owner.fullName} przejęła/przejął case`
                : "Case nadal oczekuje na przejęcie"
              : "Status SLA został ponownie przeliczony",
        createdAt,
      }
    }

    const customer = index % 2 === 1
    return {
      id: `${item.id}-message-${index}`,
      kind: customer ? "customer" : "support",
      sender: customer ? item.customer.contactName : item.owner?.fullName ?? anna.fullName,
      body: customer
        ? customerBodies[index % customerBodies.length]
        : supportBodies[index % supportBodies.length],
      createdAt,
      edited: index === 9,
      attachments:
        index === 7
          ? [
              { id: `${item.id}-attachment-1`, fileName: "zrzut-ekranu.png", size: "842 KB", type: "image" },
              { id: `${item.id}-attachment-2`, fileName: "request-details.txt", size: "12 KB", type: "document" },
            ]
          : undefined,
      codeBlock:
        index === 11
          ? {
              language: "json",
              content: `{
  "requestId": "req_${item.reference.toLowerCase().replace("-", "_")}",
  "status": 502,
  "message": "upstream temporarily unavailable"
}`,
            }
          : undefined,
      deliveryStatus: customer ? undefined : index >= 16 ? "read" : index >= 12 ? "delivered" : "sent",
    }
  })
}

export const mockInboxMessages: Record<string, InboxMessage[]> = Object.fromEntries(
  mockInboxCaseRecords.map((item, index) => [item.id, buildThread(item, index)]),
)
