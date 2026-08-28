// @vitest-environment node

import { createHash } from "node:crypto"
import { readdir, readFile, stat } from "node:fs/promises"
import { fileURLToPath } from "node:url"
import path from "node:path"
import { expect, test } from "vitest"

const repositoryRoot = fileURLToPath(new URL("../../../../", import.meta.url))
const webRoot = path.join(repositoryRoot, "apps/web")

const requiredDirectories = [
  "apps/web/app",
  "apps/web/components",
  "apps/web/lib/services",
  "apps/web/public",
  "apps/web/tests",
  "apps/api/openapi",
  "packages/api-client/src/generated",
  "infra",
]

const frontendConfigs = [
  "components.json",
  "eslint.config.mjs",
  "next.config.mjs",
  "playwright.config.ts",
  "postcss.config.mjs",
  "tsconfig.json",
  "vitest.config.mts",
]

const frontendRoutes = [
  "app/page.tsx",
  "app/cases/page.tsx",
  "app/settings/page.tsx",
  "app/statistics/page.tsx",
  "app/users/page.tsx",
]

const visualBaselines = {
  "desktop-cases-1440x900.png":
    "9276973392efd7afce10bdda426a690d59d3da24082775316f9744f2813a7e91",
  "desktop-settings-1440x900.png":
    "a97012f267faf341f912d92d6eb2e4fcf80b916bdc6c08583b9ec27376dca972",
  "desktop-statistics-1440x900.png":
    "fa0320c45ecb4680b7fcfca27b0cda01adbf12f016d74f02f748d898aa6f49b1",
  "desktop-users-1440x900.png":
    "70e6d315a930563b7627823c58952e539760d1c5a228d644abdf0cfcf66f13cd",
  "mobile-cases-390x844.png":
    "94c64e1a8e071efc6fd4d89847c1b919fbc2e27a3b96924e3af2fe019dd763ba",
} as const

async function assertDirectory(relativePath: string) {
  const entry = await stat(path.join(repositoryRoot, relativePath))
  expect(entry.isDirectory(), `${relativePath} must be a directory`).toBe(true)
}

async function assertFile(relativePath: string) {
  const entry = await stat(path.join(repositoryRoot, relativePath))
  expect(entry.isFile(), `${relativePath} must be a file`).toBe(true)
}

async function findSourceFiles(directory: string): Promise<string[]> {
  const entries = await readdir(directory, { withFileTypes: true })
  const files = await Promise.all(
    entries.map(async (entry) => {
      const entryPath = path.join(directory, entry.name)
      if (entry.isDirectory()) return findSourceFiles(entryPath)
      return /\.(?:ts|tsx)$/.test(entry.name) ? [entryPath] : []
    }),
  )
  return files.flat()
}

test("production workspace homes are explicit pnpm packages/directories", async () => {
  await Promise.all(requiredDirectories.map(assertDirectory))
  await assertFile("apps/web/AGENTS.md")

  const rootPackage = JSON.parse(
    await readFile(path.join(repositoryRoot, "package.json"), "utf8"),
  )
  const webPackage = JSON.parse(
    await readFile(path.join(webRoot, "package.json"), "utf8"),
  )
  const clientPackage = JSON.parse(
    await readFile(
      path.join(repositoryRoot, "packages/api-client/package.json"),
      "utf8",
    ),
  )

  expect(rootPackage.private).toBe(true)
  expect(rootPackage.scripts.check).toBe(
    "pnpm test:dev-tools && pnpm test:config && pnpm config:validate && pnpm security:secrets && pnpm openapi:check && pnpm --filter @usi/web check",
  )
  expect(rootPackage.scripts["test:dev-tools"]).toBe(
    "node --test scripts/tests/dev.test.mjs",
  )
  expect(rootPackage.scripts["local:infra:up"]).toBe(
    "node scripts/dev.mjs infra-up",
  )
  expect(rootPackage.scripts["local:infra:reset"]).toBe(
    "node scripts/dev.mjs infra-reset --confirm-local-data-loss",
  )
  expect(rootPackage.scripts["local:web"]).toBe("node scripts/dev.mjs web")
  expect(rootPackage.scripts["local:api"]).toBe("node scripts/dev.mjs api")
  expect(rootPackage.scripts["local:health"]).toBe("node scripts/dev.mjs health")
  expect(rootPackage.scripts["local:check"]).toBe("node scripts/dev.mjs check")
  expect(rootPackage.scripts["security:secrets"]).toBe(
    "python3 scripts/secret_scan.py",
  )
  expect(rootPackage.scripts.build).toBe("pnpm --filter @usi/web build")
  expect(rootPackage.dependencies?.next).toBeUndefined()
  expect(rootPackage.devDependencies?.vitest).toBeUndefined()
  expect(webPackage.name).toBe("@usi/web")
  expect(webPackage.dependencies.next).toBe("16.3.0")
  expect(webPackage.devDependencies.vitest).toBe("4.1.10")
  expect(clientPackage.name).toBe("@usi/api-client")
  expect(clientPackage.private).toBe(true)

  const workspace = await readFile(
    path.join(repositoryRoot, "pnpm-workspace.yaml"),
    "utf8",
  )
  expect(workspace).toMatch(/^packages:\n/m)
  expect(workspace).toMatch(/^  - apps\/\*$/m)
  expect(workspace).toMatch(/^  - packages\/\*$/m)
})

test("the Next.js application owns one complete config set", async () => {
  await Promise.all(
    frontendConfigs.map(async (config) => {
      await assertFile(`apps/web/${config}`)
      await expect(stat(path.join(repositoryRoot, config))).rejects.toMatchObject({
        code: "ENOENT",
      })
    }),
  )
})

test("accepted frontend routes and visual baseline artifacts remain unchanged", async () => {
  await Promise.all(frontendRoutes.map((route) => assertFile(`apps/web/${route}`)))
  await Promise.all(
    Object.entries(visualBaselines).map(async ([baseline, expectedHash]) => {
      const relativePath = `docs/baseline/2026-08-09/${baseline}`
      await assertFile(relativePath)
      const digest = createHash("sha256")
        .update(await readFile(path.join(repositoryRoot, relativePath)))
        .digest("hex")
      expect(digest).toBe(expectedHash)
    }),
  )
})

test("UI imports stay behind the service registry and transport boundary", async () => {
  await Promise.all([
    assertFile("apps/web/lib/services/queries.ts"),
    assertFile("apps/web/lib/services/registry.ts"),
  ])

  const uiFiles = (
    await Promise.all([
      findSourceFiles(path.join(webRoot, "app")),
      findSourceFiles(path.join(webRoot, "components")),
    ])
  ).flat()

  const importSpecifier =
    /(?:from\s+|import\s*(?:\(\s*)?)["']([^"']+)["']/g
  const mockRoot = path.join(webRoot, "mocks")
  const generatedClientRoot = path.join(repositoryRoot, "packages/api-client")
  const violations: string[] = []

  for (const file of uiFiles) {
    const source = await readFile(file, "utf8")
    for (const match of source.matchAll(importSpecifier)) {
      const specifier = match[1]
      const resolvedImport = specifier.startsWith(".")
        ? path.resolve(path.dirname(file), specifier)
        : undefined
      const importsGeneratedClient =
        specifier === "@usi/api-client" ||
        specifier.startsWith("@usi/api-client/") ||
        (resolvedImport !== undefined &&
          (resolvedImport === generatedClientRoot ||
            resolvedImport.startsWith(`${generatedClientRoot}${path.sep}`)))
      const importsMocks =
        specifier === "@/mocks" ||
        specifier.startsWith("@/mocks/") ||
        (resolvedImport !== undefined &&
          (resolvedImport === mockRoot ||
            resolvedImport.startsWith(`${mockRoot}${path.sep}`)))

      if (importsGeneratedClient || importsMocks) {
        violations.push(`${path.relative(repositoryRoot, file)} -> ${specifier}`)
      }
    }
  }

  expect(
    violations,
    "UI must use queries/registry instead of generated DTOs or mocks",
  ).toEqual([])
})
