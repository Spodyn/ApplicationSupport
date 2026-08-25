// @vitest-environment node

import { readFile } from "node:fs/promises"
import { fileURLToPath } from "node:url"
import path from "node:path"
import { describe, expect, test } from "vitest"
import {
  PUBLIC_ENVIRONMENT_VARIABLE_NAMES,
  parsePublicEnvironment,
} from "../../config/public-environment.mjs"

const repositoryRoot = fileURLToPath(new URL("../../../../", import.meta.url))

const validPublicEnvironment = {
  NEXT_PUBLIC_API_BASE_URL: "/api/v1",
  NEXT_PUBLIC_WS_BASE_URL: "/ws",
}

const invalidPublicEnvironments: Array<
  [description: string, environment: Record<string, string | undefined>]
> = [
  ["missing API path", { NEXT_PUBLIC_WS_BASE_URL: "/ws" }],
  [
    "absolute cross-origin API URL",
    {
      ...validPublicEnvironment,
      NEXT_PUBLIC_API_BASE_URL: "https://api.example.invalid/api/v1",
    },
  ],
  [
    "protocol-relative WebSocket URL",
    { ...validPublicEnvironment, NEXT_PUBLIC_WS_BASE_URL: "//example.invalid/ws" },
  ],
  [
    "path traversal",
    { ...validPublicEnvironment, NEXT_PUBLIC_API_BASE_URL: "/api/../admin" },
  ],
  [
    "encoded path separator",
    { ...validPublicEnvironment, NEXT_PUBLIC_API_BASE_URL: "/api/%2fadmin" },
  ],
]

describe("browser-public environment", () => {
  test("returns a typed, immutable same-origin configuration", () => {
    const configuration = parsePublicEnvironment(validPublicEnvironment)

    expect(configuration).toEqual({
      apiBaseUrl: "/api/v1",
      websocketBaseUrl: "/ws",
    })
    expect(Object.isFrozen(configuration)).toBe(true)
  })

  test.each(invalidPublicEnvironments)("rejects %s", (_description, environment) => {
    expect(() => parsePublicEnvironment(environment)).toThrow()
  })

  test("rejects every unreviewed NEXT_PUBLIC variable without echoing its value", () => {
    const sensitiveName = ["NEXT_PUBLIC", "SLACK", "SIGNING", "SECRET"].join("_")
    const sensitiveValue = ["do", "not", "echo", "this", "value"].join("-")

    expect(() =>
      parsePublicEnvironment({
        ...validPublicEnvironment,
        [sensitiveName]: sensitiveValue,
      }),
    ).toThrowError(new RegExp(sensitiveName))

    try {
      parsePublicEnvironment({
        ...validPublicEnvironment,
        [sensitiveName]: sensitiveValue,
      })
    } catch (error) {
      expect(String(error)).not.toContain(sensitiveValue)
    }
  })

  test("matches the machine-readable public allowlist", async () => {
    const contract = JSON.parse(
      await readFile(
        path.join(repositoryRoot, "config/environment-contract.json"),
        "utf8",
      ),
    ) as { publicVariables: Record<string, unknown> }

    expect([...PUBLIC_ENVIRONMENT_VARIABLE_NAMES].sort()).toEqual(
      Object.keys(contract.publicVariables).sort(),
    )
  })
})
