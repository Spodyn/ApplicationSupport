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

async function preparePage(page: Page, initiallyAuthenticated = true) {
  const externalRequests: string[] = []
  let authenticated = initiallyAuthenticated

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

    await route.continue()
  })

  return externalRequests
}

const smokeRoutes = [
  { path: "/cases", heading: "Czaty" },
  { path: "/statistics", heading: "Statystyki" },
  { path: "/users", heading: "Użytkownicy" },
  { path: "/settings", heading: "Ustawienia" },
] as const

for (const route of smokeRoutes) {
  test(`${route.path} ładuje główny widok`, async ({ page }) => {
    const externalRequests = await preparePage(page)

    await page.goto(route.path)

    await expect(page.getByRole("heading", { level: 1, name: route.heading })).toBeVisible()
    expect(externalRequests).toEqual([])
  })
}

test("niezalogowany użytkownik trafia na login i może utworzyć sesję", async ({ page }) => {
  const externalRequests = await preparePage(page, false)

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

test("wybranie nieodczytanego czatu otwiera rozmowę i oznacza wpis jako odczytany", async ({ page }) => {
  const externalRequests = await preparePage(page)
  await page.goto("/cases")

  const chat = page.getByRole("option", { name: /Nova Works/ })
  await expect(chat.getByLabel("Nieodczytane")).toBeVisible()

  await chat.click()

  await expect(chat).toHaveAttribute("aria-selected", "true")
  await expect(chat.getByLabel("Nieodczytane")).toHaveCount(0)
  await expect(page.getByRole("region", { name: "Rozmowa Nova Works" })).toBeVisible()
  expect(externalRequests).toEqual([])
})

test("akcja odpowiedzi pokazuje kontekst wiadomości w kompozytorze", async ({ page }) => {
  const externalRequests = await preparePage(page)
  await page.goto("/cases")

  const conversation = page.getByRole("region", { name: "Rozmowa Northstar Retail" })
  await conversation.getByRole("button", { name: "Odpowiedz na wiadomość" }).first().click()

  await expect(conversation.getByText(/Odpowiedź: W panelu widzę dwa obciążenia/)).toBeVisible()
  expect(externalRequests).toEqual([])
})

test("formularz dodawania użytkownika otwiera się i resetuje po anulowaniu", async ({ page }) => {
  const externalRequests = await preparePage(page)
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
  const externalRequests = await preparePage(page)
  await page.goto("/cases")

  const chatList = page.getByRole("region", { name: "Lista czatów" })
  await expect(chatList).toBeVisible()
  await page.getByRole("option", { name: /Evergreen Cloud/ }).click()

  const conversation = page.getByRole("region", { name: "Rozmowa Evergreen Cloud" })
  await expect(conversation).toBeVisible()
  await conversation.getByRole("button", { name: "Wróć do listy" }).click()

  await expect(chatList).toBeVisible()
  expect(externalRequests).toEqual([])
})
