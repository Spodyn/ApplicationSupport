import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import {
  API_DIR,
  backendCommand,
  composeArgs,
  ensureLocalFile,
  parseEnvFile,
  requireResetConfirmation,
} from "../dev.mjs";

test("ensureLocalFile copies template only when target is missing", () => {
  const root = mkdtempSync(join(tmpdir(), "usi-dev-script-"));
  const source = join(root, "example");
  const target = join(root, "local");
  writeFileSync(source, "initial\n", "utf8");

  assert.equal(ensureLocalFile(target, source), true);
  assert.equal(readFileSync(target, "utf8"), "initial\n");

  writeFileSync(source, "changed\n", "utf8");
  assert.equal(ensureLocalFile(target, source), false);
  assert.equal(readFileSync(target, "utf8"), "initial\n");
});

test("parseEnvFile handles comments, empty values and quoted values", () => {
  const root = mkdtempSync(join(tmpdir(), "usi-dev-env-"));
  const path = join(root, ".env");
  writeFileSync(
    path,
    "# comment\nA=one\nB=\"two words\"\nC='three'\nEMPTY=\n",
    "utf8",
  );

  assert.deepEqual(parseEnvFile(path), {
    A: "one",
    B: "two words",
    C: "three",
    EMPTY: "",
  });
});

test("composeArgs uses checked-in example when local env is absent", () => {
  const args = composeArgs({ createEnv: false });
  assert.equal(args[0], "compose");
  assert.ok(args.includes("--env-file"));
  assert.ok(args.includes("-f"));
  assert.ok(args.some((value) => value.endsWith("infra/.env.example")));
  assert.ok(args.some((value) => value.endsWith("infra/compose.yaml")));
});

test("destructive reset requires explicit local-data-loss confirmation", () => {
  assert.throws(
    () => requireResetConfirmation([]),
    /Refusing destructive reset/u,
  );
  assert.doesNotThrow(
    () => requireResetConfirmation(["--confirm-local-data-loss"]),
  );
});

test("backendCommand prefers Maven wrapper and is platform-aware", () => {
  const unixFiles = new Set([join(API_DIR, "mvnw")]);
  assert.deepEqual(
    backendCommand({
      platform: "linux",
      fileExists: (path) => unixFiles.has(path),
    }),
    {
      command: join(API_DIR, "mvnw"),
      args: ["spring-boot:run"],
      cwd: API_DIR,
    },
  );

  const windowsFiles = new Set([join(API_DIR, "mvnw.cmd")]);
  assert.deepEqual(
    backendCommand({
      platform: "win32",
      fileExists: (path) => windowsFiles.has(path),
    }),
    {
      command: join(API_DIR, "mvnw.cmd"),
      args: ["spring-boot:run"],
      cwd: API_DIR,
    },
  );
});

test("backendCommand fails closed when backend bootstrap is absent", () => {
  assert.equal(
    backendCommand({
      platform: "linux",
      fileExists: () => false,
    }),
    null,
  );
});
