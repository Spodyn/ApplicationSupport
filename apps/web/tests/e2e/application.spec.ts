import { expect, test, type Page } from "@playwright/test"

const localOrigin = "http://127.0.0.1:3100"

const authenticatedSession = {
  id: "018f0000-0000-7000-8000-000000000064",
  email: "anna.kowalska@firma.pl",
  displayName: "Anna Kowalska",
  role: "USER",
  createdAt: "2024-01-12T08:00:00.000Z",
  effectivePermissions: [],
}

const caseId = "018f0000-0000-7000-8000-000000000201"
const inboundMessageId = "018f0000-0000-7000-8000-000000000202"
const caseListItem = {
  id: caseId, reference: "CASE-00000002", status: "NEW", customerId: "customer",
  customerName: "Slack Test Customer", channelId: "channel", channelName: "new-channel",
  externalChannelId: "C0C6N61M4HZ", provider: "SLACK", ownerUserId: null,
  ownerDisplayName: null, lastMessageId: inboundMessageId, lastMessagePreview: "Hello from Slack - USI test 1",
  unreadForCurrentUser: true, snoozedUntil: null, slaState: "ON_TRACK", slaDueAt: null,
  ignoreScore: 0, waitingUntil: null, createdAt: "2026-10-05T10:00:00Z",
  updatedAt: "2026-10-05T10:00:00Z", lastActivityAt: "2026-10-05T10:00:00Z",
}
const inboundMessage = {
  id: inboundMessageId, kind: "CUSTOMER", body: "Hello from Slack - USI test 1", bodyFormat: "PLAIN_TEXT",
  inbound: true, deliveryStatus: null, providerCreatedAt: caseListItem.createdAt,
  createdAt: caseListItem.createdAt, editedAt: null, deletedAt: null, authorName: "Customer", attachments: [],
}

async function preparePage(page: Page, initiallyAuthenticated = true) {
  const externalRequests: string[] = []
  let authenticated = initiallyAuthenticated
  let claimed = false
  let resolved = false
  let read = false
  const supportMessages: Array<Omit<typeof inboundMessage, "deliveryStatus"> & { deliveryStatus: string | null }> = []
  const sends: string[] = []
  await page.context().addCookies([{ name: "XSRF-TOKEN", value: "e2e-csrf", url: localOrigin }])

  await page.route("**/*", async (route) => {
    const request = route.request()
    const requestUrl = new URL(request.url())
    const isHttp = requestUrl.protocol === "http:" || requestUrl.protocol === "https:"

    if (isHttp && requestUrl.origin !== localOrigin) {
      externalRequests.push(requestUrl.href)
      await route.abort("blockedbyclient")
      return
    }

    if (requestUrl.pathname === "/api/v1/auth/me") {
      if (authenticated) {
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(authenticatedSession),
        })
      } else {
        await route.fulfill({
          status: 401,
          contentType: "application/problem+json",
          headers: {
            "set-cookie": "XSRF-TOKEN=e2e-csrf; Path=/; SameSite=Lax",
          },
          body: JSON.stringify({
            code: "AUTHENTICATION_REQUIRED",
            title: "Authentication required",
            status: 401,
            detail: "Authentication is required to access this resource.",
            correlationId: "e2e-correlation",
          }),
        })
      }
      return
    }

    if (requestUrl.pathname === "/api/v1/auth/login" && request.method() === "POST") {
      expect(request.headers()["x-xsrf-token"]).toBe("e2e-csrf")
      authenticated = true
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(authenticatedSession),
      })
      return
    }

    if (requestUrl.pathname === "/api/v1/auth/logout" && request.method() === "POST") {
      authenticated = false
      await route.fulfill({ status: 204 })
      return
    }

    if (requestUrl.pathname === "/api/v1/cases" && request.method() === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
        items: [{ ...caseListItem, status: resolved ? "RESOLVED" : claimed ? "VERIFICATION" : "NEW",
          ownerUserId: claimed ? authenticatedSession.id : null,
          ownerDisplayName: claimed ? authenticatedSession.displayName : null,
          unreadForCurrentUser: !read }], nextCursor: null,
      }) })
      return
    }
    if (requestUrl.pathname === `/api/v1/cases/${caseId}` && request.method() === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
        id: caseId, reference: caseListItem.reference, status: resolved ? "RESOLVED" : claimed ? "VERIFICATION" : "NEW",
        customer: { id: "customer", name: caseListItem.customerName, externalRef: "slack-test" },
        owner: { id: claimed ? authenticatedSession.id : null, displayName: claimed ? authenticatedSession.displayName : null },
        channel: { id: "channel", name: "new-channel", externalChannelId: "C0C6N61M4HZ", groupingStrategy: "SLACK_ROOT_THREAD" },
        integration: { id: "integration", provider: "SLACK", displayName: "TestApp", workspaceExternalId: "team", workspaceName: "TestApp" },
        relatedCase: { id: null, reference: null }, personalState: { lastReadMessageId: read ? inboundMessageId : null, lastReadAt: null, snoozedUntil: null },
        sla: null, ignoreScore: 0, availableActions: resolved ? ["MARK_READ"] : claimed ? ["REPLY", "RESOLVE", "MARK_READ"] : ["CLAIM", "MARK_READ"],
        claimedAt: claimed ? caseListItem.updatedAt : null, waitingUntil: null,
        resolvedAt: resolved ? new Date().toISOString() : null, ignoredAt: null, resolutionCategory: null,
        createdAt: caseListItem.createdAt, updatedAt: caseListItem.updatedAt, lastActivityAt: caseListItem.lastActivityAt, version: resolved ? 3 : claimed ? 2 : 1,
      }) })
      return
    }
    if (requestUrl.pathname === `/api/v1/cases/${caseId}/messages` && request.method() === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ items: [...supportMessages].reverse().concat(inboundMessage), nextCursor: null }) })
      return
    }
    if (requestUrl.pathname === `/api/v1/cases/${caseId}/read-position` && request.method() === "PUT") {
      read = true
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ caseId, messageId: inboundMessageId, readAt: new Date().toISOString() }) })
      return
    }
    if (requestUrl.pathname === `/api/v1/cases/${caseId}/claim` && request.method() === "POST") {
      expect(request.headers()["idempotency-key"]).toMatch(/^[0-9a-f-]{36}$/)
      expect(request.headers()["x-xsrf-token"]).toBe("e2e-csrf")
      claimed = true
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ caseId, status: "VERIFICATION", ownerUserId: authenticatedSession.id, version: 2 }) })
      return
    }
    if (requestUrl.pathname === `/api/v1/cases/${caseId}/messages` && request.method() === "POST") {
      expect(request.headers()["idempotency-key"]).toMatch(/^[0-9a-f-]{36}$/)
      expect(request.headers()["x-xsrf-token"]).toBe("e2e-csrf")
      const body = request.postDataJSON() as { body: string }
      sends.push(body.body)
      supportMessages.push({ ...inboundMessage, id: "018f0000-0000-7000-8000-000000000203", kind: "SUPPORT", inbound: false, body: body.body, authorName: authenticatedSession.displayName, deliveryStatus: "QUEUED" })
      await route.fulfill({ status: 202, contentType: "application/json", body: JSON.stringify({ messageId: supportMessages[0].id }) })
      return
    }
    if (requestUrl.pathname === `/api/v1/cases/${caseId}/resolve` && request.method() === "POST") {
      expect(request.headers()["idempotency-key"]).toMatch(/^[0-9a-f-]{36}$/)
      expect(request.headers()["x-xsrf-token"]).toBe("e2e-csrf")
      expect(request.postDataJSON()).toEqual({ resolutionCategory: null })
      resolved = true
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
        caseId, status: "RESOLVED", ownerUserId: authenticatedSession.id,
        resolutionCategory: null, version: 3, resolvedAt: new Date().toISOString(),
      }) })
      return
    }
    await route.continue()
  })

  return { externalRequests, sends }
}

const smokeRoutes = [
  { path: "/cases", heading: "Czaty" },
  { path: "/statistics", heading: "Statystyki" },
  { path: "/users", heading: "Użytkownicy" },
  { path: "/settings", heading: "Ustawienia" },
] as const

for (const route of smokeRoutes) {
  test(`${route.path} ładuje główny widok`, async ({ page }) => {
    const { externalRequests } = await preparePage(page)

    await page.goto(route.path)

    await expect(page.getByRole("heading", { level: 1, name: route.heading })).toBeVisible()
    expect(externalRequests).toEqual([])
  })
}

test("niezalogowany użytkownik trafia na login i może utworzyć sesję", async ({ page }) => {
  const { externalRequests } = await preparePage(page, false)

  await page.goto("/cases")
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByRole("heading", { name: "Zaloguj się" })).toBeVisible()

  await page.getByLabel("E-mail").fill("anna.kowalska@firma.pl")
  await page.getByLabel("Hasło").fill("test-only-credential")
  await page.getByRole("button", { name: "Zaloguj się" }).click()

  await expect(page).toHaveURL(/\/cases$/)
  await expect(page.getByRole("heading", { level: 1, name: "Czaty" })).toBeVisible()
  expect(externalRequests).toEqual([])
})

test("real API Case opens, is marked read, claimed, and receives one persisted support reply", async ({ page }) => {
  const { externalRequests, sends } = await preparePage(page)
  await page.goto("/cases")
  const chat = page.getByRole("option", { name: /Slack Test Customer/ })
  await expect(chat).toContainText("Hello from Slack - USI test 1")
  await chat.click()
  await expect(chat.getByLabel("Nieodczytane")).toHaveCount(0)
  const conversation = page.getByRole("region", { name: "Rozmowa Slack Test Customer" })
  await expect(conversation.getByText("Hello from Slack - USI test 1")).toBeVisible()
  await conversation.getByRole("button", { name: "Przejmij" }).click()
  await expect(conversation.getByText("Anna Kowalska")).toBeVisible()
  await conversation.getByLabel("Treść odpowiedzi").fill("USI test reply")
  await conversation.getByRole("button", { name: "Wyślij" }).click()
  await expect(conversation.getByText("USI test reply")).toBeVisible()
  expect(sends).toEqual(["USI test reply"])
  expect(externalRequests).toEqual([])
})

test("current owner can confirm and resolve a Case", async ({ page }) => {
  const { externalRequests } = await preparePage(page)
  await page.goto("/cases")
  const conversation = page.getByRole("region", { name: "Rozmowa Slack Test Customer" })
  await conversation.getByRole("button", { name: "Przejmij" }).click()
  await expect(conversation.getByText("Anna Kowalska")).toBeVisible()

  await conversation.getByRole("button", { name: "Zamknij sprawę" }).click()
  const dialog = page.getByRole("alertdialog", { name: "Zamknąć sprawę?" })
  await expect(dialog).toBeVisible()
  await dialog.getByRole("button", { name: "Zamknij sprawę" }).click()

  await expect(conversation.getByText("Rozwiązane")).toBeVisible()
  await expect(conversation.getByRole("button", { name: "Zamknij sprawę" })).toBeDisabled()
  expect(externalRequests).toEqual([])
})

test("empty persisted conversation renders an empty state", async ({ page }) => {
  await preparePage(page)
  await page.route("**/api/v1/cases/*/messages*", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ items: [], nextCursor: null }) })
  })
  await page.goto("/cases")
  const conversation = page.getByRole("region", { name: "Rozmowa Slack Test Customer" })
  await expect(conversation.getByText("Brak wiadomości.")).toBeVisible()
  await expect(conversation.getByText("Nie udało się wczytać wiadomości.")).toHaveCount(0)
})

test("formularz dodawania użytkownika otwiera się i resetuje po anulowaniu", async ({ page }) => {
  const { externalRequests } = await preparePage(page)
  await page.goto("/users")

  await page.getByRole("button", { name: "Dodaj użytkownika" }).click()
  const dialog = page.getByRole("dialog", { name: "Dodaj użytkownika" })
  await expect(dialog).toBeVisible()

  await dialog.getByLabel("Imię i nazwisko").fill("Test tymczasowy")
  await dialog.getByLabel("E-mail").fill("test@example.invalid")
  await dialog.getByRole("button", { name: "Anuluj" }).click()

  await page.getByRole("button", { name: "Dodaj użytkownika" }).click()
  const reopenedDialog = page.getByRole("dialog", { name: "Dodaj użytkownika" })
  await expect(reopenedDialog.getByLabel("Imię i nazwisko")).toHaveValue("")
  await expect(reopenedDialog.getByLabel("E-mail")).toHaveValue("")
  expect(externalRequests).toEqual([])
})

test("mobilny widok czatów przechodzi z listy do rozmowy i wraca", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const { externalRequests } = await preparePage(page)
  await page.goto("/cases")

  const chatList = page.getByRole("region", { name: "Lista czatów" })
  await expect(chatList).toBeVisible()
  await page.getByRole("option", { name: /Slack Test Customer/ }).click()

  const conversation = page.getByRole("region", { name: "Rozmowa Slack Test Customer" })
  await expect(conversation).toBeVisible()
  await conversation.getByRole("button", { name: "Wróć do listy" }).click()

  await expect(chatList).toBeVisible()
  expect(externalRequests).toEqual([])
})
