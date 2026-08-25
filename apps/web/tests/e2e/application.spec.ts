import { expect, test, type Page } from "@playwright/test"

const localOrigin = "http://127.0.0.1:3100"

async function blockExternalRequests(page: Page) {
  const externalRequests: string[] = []

  await page.route("**/*", async (route) => {
    const requestUrl = new URL(route.request().url())
    const isHttp = requestUrl.protocol === "http:" || requestUrl.protocol === "https:"

    if (isHttp && requestUrl.origin !== localOrigin) {
      externalRequests.push(requestUrl.href)
      await route.abort("blockedbyclient")
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
    const externalRequests = await blockExternalRequests(page)

    await page.goto(route.path)

    await expect(page.getByRole("heading", { level: 1, name: route.heading })).toBeVisible()
    expect(externalRequests).toEqual([])
  })
}

test("wybranie nieodczytanego czatu otwiera rozmowę i oznacza wpis jako odczytany", async ({ page }) => {
  const externalRequests = await blockExternalRequests(page)
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
  const externalRequests = await blockExternalRequests(page)
  await page.goto("/cases")

  const conversation = page.getByRole("region", { name: "Rozmowa Northstar Retail" })
  await conversation.getByRole("button", { name: "Odpowiedz na wiadomość" }).first().click()

  await expect(conversation.getByText(/Odpowiedź: W panelu widzę dwa obciążenia/)).toBeVisible()
  expect(externalRequests).toEqual([])
})

test("formularz dodawania użytkownika otwiera się i resetuje po anulowaniu", async ({ page }) => {
  const externalRequests = await blockExternalRequests(page)
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
  const externalRequests = await blockExternalRequests(page)
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
