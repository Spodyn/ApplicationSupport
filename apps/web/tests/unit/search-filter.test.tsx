import { useState } from "react"
import { render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { describe, expect, it } from "vitest"
import { SearchFilter } from "@/components/design-system/filter-bar"

function SearchFilterHarness() {
  const [value, setValue] = useState("")
  return (
    <SearchFilter
      value={value}
      onChange={setValue}
      placeholder="Szukaj użytkowników"
    />
  )
}

describe("SearchFilter", () => {
  it("pozwala wpisać i wyczyścić wyszukiwaną frazę", async () => {
    const user = userEvent.setup()
    render(<SearchFilterHarness />)

    const input = screen.getByRole("textbox", { name: "Szukaj użytkowników" })
    await user.type(input, "Anna")

    expect(input).toHaveValue("Anna")

    await user.click(screen.getByRole("button", { name: "Wyczyść wyszukiwanie" }))

    expect(input).toHaveValue("")
    expect(screen.queryByRole("button", { name: "Wyczyść wyszukiwanie" })).not.toBeInTheDocument()
  })
})
