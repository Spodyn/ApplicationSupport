import assert from "node:assert/strict";
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import {
  API_DIR,
  INFRA_ENV,
  INFRA_ENV_EXAMPLE,
  LOCAL_SLACK_SEED_SQL,
  assertLocalSlackPostgresLabels,
  backendCommand,
  composeArgs,
  ensureLocalFile,
  parseEnvFile,
  localSlackPsqlArgs,
  localSlackSandboxIds,
  parseLocalSlackSeedRecords,
  processInvocation,
  requireResetConfirmation,
} from "../dev.mjs";

test("Slack seed targets only local Compose postgres and reports three UUIDs", () => {
  assert.doesNotThrow(() => assertLocalSlackPostgresLabels("usi-local|postgres"));
  assert.throws(() => assertLocalSlackPostgresLabels("usi-staging|postgres"), /usi-local/u);
  assert.throws(() => assertLocalSlackPostgresLabels("usi-local|rabbitmq"), /usi-local/u);
  const psqlArgs = localSlackPsqlArgs("TTEST123", "CTEST456");
  assert.deepEqual(psqlArgs.slice(0, 3), ["exec", "-T", "postgres"]);
  assert.deepEqual(psqlArgs.slice(-3), ["sh", "TTEST123", "CTEST456"]);
  assert.deepEqual(localSlackSandboxIds({
    USI_SLACK_TEAM_ID: "TTEST123",
    USI_SLACK_CHANNEL_ID: "CTEST456",
  }), { teamId: "TTEST123", channelId: "CTEST456" });
  assert.throws(() => localSlackSandboxIds({ USI_SLACK_TEAM_ID: "", USI_SLACK_CHANNEL_ID: "" }));
  assert.throws(() => localSlackSandboxIds({
    USI_SLACK_TEAM_ID: "not-a-team",
    USI_SLACK_CHANNEL_ID: "CTEST456",
  }));

  const ids = [
    "0199f9f9-aaaa-7777-8888-000000000001",
    "0199f9f9-aaaa-7777-8888-000000000002",
    "0199f9f9-aaaa-7777-8888-000000000003",
  ];
  const output = `customer|${ids[0]}|created\nintegration|${ids[1]}|reused\nchannel|${ids[2]}|created`;
  assert.deepEqual(parseLocalSlackSeedRecords(output), [
    ["customer", ids[0], "created"],
    ["integration", ids[1], "reused"],
    ["channel", ids[2], "created"],
  ]);
  assert.throws(() => parseLocalSlackSeedRecords(output.replace("channel|", "secret|")));
  assert.throws(() => parseLocalSlackSeedRecords(`${output}\nextra output`));

  const sql = readFileSync(LOCAL_SLACK_SEED_SQL, "utf8");
  for (const value of ["slack_team_id", "slack_channel_id", "slack/development-workspace", "SLACK_ROOT_THREAD"]) {
    assert.ok(sql.includes(value));
  }
  assert.ok(!sql.includes("T0C6P3JEDU5"));
  assert.ok(!sql.includes("C0C6N61M4HZ"));
  assert.ok(sql.includes("pg_advisory_xact_lock"));
  assert.ok(sql.includes("FOR UPDATE"));
  assert.ok(!sql.includes("xoxb-"));
  assert.ok(!sql.includes("slack-signing-secret"));
});

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
