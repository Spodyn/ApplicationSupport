import assert from "node:assert/strict";
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import {
  API_DIR,
  INFRA_ENV,
  INFRA_ENV_EXAMPLE,
  backendCommand,
  composeArgs,
  ensureLocalFile,
  parseEnvFile,
  processInvocation,
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

test("composeArgs uses the local env when present and the example otherwise", () => {
  const args = composeArgs({ createEnv: false });
  assert.equal(args[0], "compose");
  assert.ok(args.includes("--env-file"));
  assert.ok(args.includes("-f"));
  assert.ok(args.includes(existsSync(INFRA_ENV) ? INFRA_ENV : INFRA_ENV_EXAMPLE));
  assert.ok(args.some((value) => value.endsWith(join("infra", "compose.yaml"))));
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

test("Windows routes only trusted cmd launchers through ComSpec", () => {
  assert.deepEqual(
    processInvocation("pnpm", ["--filter", "@usi/web", "dev"], {
      platform: "win32",
      comSpec: "C:\\Windows\\System32\\cmd.exe",
    }),
    {
      command: "C:\\Windows\\System32\\cmd.exe",
      args: ["/d", "/s", "/c", "pnpm.cmd", "--filter", "@usi/web", "dev"],
    },
  );

  assert.deepEqual(
    processInvocation("mvn.cmd", ["spring-boot:run"], {
      platform: "win32",
      comSpec: "cmd.exe",
    }),
    {
      command: "cmd.exe",
      args: ["/d", "/s", "/c", "mvn.cmd", "spring-boot:run"],
    },
  );

  assert.deepEqual(
    processInvocation("docker", ["compose", "ps"], {
      platform: "win32",
      comSpec: "cmd.exe",
    }),
    {
      command: "docker",
      args: ["compose", "ps"],
    },
  );

  assert.deepEqual(
    processInvocation("pnpm", ["check"], { platform: "linux" }),
    {
      command: "pnpm",
      args: ["check"],
    },
  );
});

test("Windows does not route arbitrary cmd files through the trusted shell path", () => {
  assert.deepEqual(
    processInvocation("untrusted.cmd", ["arg"], {
      platform: "win32",
      comSpec: "cmd.exe",
    }),
    {
      command: "untrusted.cmd",
      args: ["arg"],
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
