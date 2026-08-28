#!/usr/bin/env node

import { copyFileSync, existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

export const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
export const INFRA_DIR = resolve(ROOT, "infra");
export const INFRA_ENV = resolve(INFRA_DIR, ".env");
export const INFRA_ENV_EXAMPLE = resolve(INFRA_DIR, ".env.example");
export const COMPOSE_FILE = resolve(INFRA_DIR, "compose.yaml");
export const WEB_ENV = resolve(ROOT, "apps", "web", ".env.local");
export const WEB_ENV_EXAMPLE = resolve(ROOT, "apps", "web", ".env.example");
export const API_DIR = resolve(ROOT, "apps", "api");
export const ROOT_ENV = resolve(ROOT, ".env");
export const ROOT_ENV_EXAMPLE = resolve(ROOT, ".env.example");

const SERVICES = ["postgres", "rabbitmq", "minio"];

function platformCommand(command, platform = process.platform) {
  if (platform === "win32" && ["pnpm", "mvn"].includes(command)) {
    return `${command}.cmd`;
  }
  return command;
}

export function ensureLocalFile(target, example) {
  if (existsSync(target)) {
    return false;
  }
  if (!existsSync(example)) {
    throw new Error(`Missing required template: ${example}`);
  }
  copyFileSync(example, target);
  console.log(`[local] created ${target} from ${example}`);
  return true;
}

export function composeArgs({ createEnv = false } = {}) {
  let envFile = INFRA_ENV;
  if (!existsSync(envFile)) {
    if (createEnv) {
      ensureLocalFile(INFRA_ENV, INFRA_ENV_EXAMPLE);
    } else {
      envFile = INFRA_ENV_EXAMPLE;
    }
  }

  return [
    "compose",
    "--env-file",
    envFile,
    "-f",
    COMPOSE_FILE,
  ];
}

export function parseEnvFile(path) {
  if (!existsSync(path)) {
    return {};
  }

  const values = {};
  for (const rawLine of readFileSync(path, "utf8").split(/\r?\n/u)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) {
      continue;
    }

    const separator = line.indexOf("=");
    if (separator < 1) {
      continue;
    }

    const key = line.slice(0, separator).trim();
    let value = line.slice(separator + 1).trim();
    if (
      value.length >= 2
      && ((value.startsWith('"') && value.endsWith('"'))
        || (value.startsWith("'") && value.endsWith("'")))
    ) {
      value = value.slice(1, -1);
    }
    values[key] = value;
  }
  return values;
}

export function backendCommand({
  platform = process.platform,
  fileExists = existsSync,
} = {}) {
  if (platform === "win32" && fileExists(resolve(API_DIR, "mvnw.cmd"))) {
    return {
      command: resolve(API_DIR, "mvnw.cmd"),
      args: ["spring-boot:run"],
      cwd: API_DIR,
    };
  }

  if (platform !== "win32" && fileExists(resolve(API_DIR, "mvnw"))) {
    return {
      command: resolve(API_DIR, "mvnw"),
      args: ["spring-boot:run"],
      cwd: API_DIR,
    };
  }

  if (fileExists(resolve(API_DIR, "pom.xml"))) {
    return {
      command: platformCommand("mvn", platform),
      args: ["spring-boot:run"],
      cwd: API_DIR,
    };
  }

  return null;
}

export function backendVerifyCommand({
  platform = process.platform,
  fileExists = existsSync,
} = {}) {
  if (platform === "win32" && fileExists(resolve(API_DIR, "mvnw.cmd"))) {
    return {
      command: resolve(API_DIR, "mvnw.cmd"),
      args: ["clean", "verify"],
      cwd: API_DIR,
    };
  }

  if (platform !== "win32" && fileExists(resolve(API_DIR, "mvnw"))) {
    return {
      command: resolve(API_DIR, "mvnw"),
      args: ["clean", "verify"],
      cwd: API_DIR,
    };
  }

  if (fileExists(resolve(API_DIR, "pom.xml"))) {
    return {
      command: platformCommand("mvn", platform),
      args: ["clean", "verify"],
      cwd: API_DIR,
    };
  }

  return null;
}

export function requireResetConfirmation(args) {
  if (!args.includes("--confirm-local-data-loss")) {
    throw new Error(
      "Refusing destructive reset. Re-run with --confirm-local-data-loss. "
      + "This command is restricted to infra/compose.yaml local named volumes.",
    );
  }
}

function run(command, args, {
  cwd = ROOT,
  env = process.env,
  capture = false,
} = {}) {
  const result = spawnSync(platformCommand(command), args, {
    cwd,
    env,
    encoding: "utf8",
    stdio: capture ? ["ignore", "pipe", "pipe"] : "inherit",
    shell: false,
  });

  if (result.error) {
    throw result.error;
  }
  if (result.status !== 0) {
    const detail = capture ? (result.stderr || result.stdout || "").trim() : "";
    throw new Error(
      `${command} ${args.join(" ")} failed with exit code ${result.status}`
      + (detail ? `: ${detail}` : ""),
    );
  }

  return capture ? result.stdout.trim() : "";
}

function dockerCompose(args, options = {}) {
  return run("docker", [...composeArgs(options), ...args], options);
}

function assertServiceHealthy(service) {
  const containerId = dockerCompose(
    ["ps", "--quiet", service],
    { createEnv: true, capture: true },
  );
  if (!containerId) {
    throw new Error(`${service}: container is not running`);
  }

  const state = run(
    "docker",
    [
      "inspect",
      "--format",
      "{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}",
      containerId,
    ],
    { capture: true },
  );

  if (state !== "healthy") {
    throw new Error(`${service}: expected healthy, got ${state || "unknown"}`);
  }
  console.log(`[health] ${service}: healthy`);
}

export function apiBootstrapExists(fileExists = existsSync) {
  return fileExists(resolve(API_DIR, "pom.xml"));
}

async function assertApiHealthy() {
  if (!apiBootstrapExists()) {
    console.log("[health] api: skipped (apps/api/pom.xml not present yet)");
    return;
  }

  const url = process.env.USI_API_HEALTH_URL
    || "http://127.0.0.1:8080/actuator/health";
  let response;
  try {
    response = await fetch(url, { signal: AbortSignal.timeout(4000) });
  } catch (error) {
    throw new Error(`api: cannot reach ${url}: ${error.message}`);
  }

  if (!response.ok) {
    throw new Error(`api: ${url} returned HTTP ${response.status}`);
  }

  let payload;
  try {
    payload = await response.json();
  } catch {
    throw new Error(`api: ${url} did not return JSON`);
  }

  if (payload?.status !== "UP") {
    throw new Error(`api: expected actuator status UP, got ${payload?.status ?? "unknown"}`);
  }
  console.log(`[health] api: UP (${url})`);
}

async function infraUp() {
  ensureLocalFile(INFRA_ENV, INFRA_ENV_EXAMPLE);
  dockerCompose(["config", "--quiet"], { createEnv: true });
  dockerCompose(
    [
      "up",
      "--detach",
      "--wait",
      "--wait-timeout",
      "180",
      ...SERVICES,
    ],
    { createEnv: true },
  );
  dockerCompose(["run", "--rm", "minio-init"], { createEnv: true });
  for (const service of SERVICES) {
    assertServiceHealthy(service);
  }
  console.log("[local] infrastructure ready");
}

function infraDown() {
  dockerCompose(["down", "--remove-orphans"], { createEnv: true });
}

function infraReset(args) {
  requireResetConfirmation(args);
  dockerCompose(
    ["down", "--volumes", "--remove-orphans"],
    { createEnv: true },
  );
  console.log("[local] local infrastructure volumes removed");
}

function infraLogs(args) {
  const services = args.filter((value) => !value.startsWith("--"));
  dockerCompose(
    ["logs", "--follow", "--tail", "200", ...services],
    { createEnv: true },
  );
}

function webDev() {
  ensureLocalFile(WEB_ENV, WEB_ENV_EXAMPLE);
  run("pnpm", ["--filter", "@usi/web", "dev"]);
}

function apiDev() {
  ensureLocalFile(ROOT_ENV, ROOT_ENV_EXAMPLE);
  const command = backendCommand();
  if (!command) {
    throw new Error(
      "Backend bootstrap is not present yet (apps/api/pom.xml or Maven wrapper missing). "
      + "Complete E02-T01/USI-48 first, then re-run this command.",
    );
  }

  const env = {
    ...process.env,
    ...parseEnvFile(ROOT_ENV),
  };
  run(command.command, command.args, { cwd: command.cwd, env });
}

async function health() {
  for (const service of SERVICES) {
    assertServiceHealthy(service);
  }
  await assertApiHealthy();
}

function fullCheck() {
  dockerCompose(["config", "--quiet"], { createEnv: false });
  run("pnpm", ["check"]);

  const command = backendVerifyCommand();
  if (command) {
    const env = {
      ...process.env,
      ...parseEnvFile(existsSync(ROOT_ENV) ? ROOT_ENV : ROOT_ENV_EXAMPLE),
    };
    run(command.command, command.args, { cwd: command.cwd, env });
  } else {
    console.log("[check] backend: skipped (apps/api/pom.xml not present yet)");
  }
}

export function usage() {
  return `USI local developer commands

Usage:
  node scripts/dev.mjs infra-up
  node scripts/dev.mjs infra-down
  node scripts/dev.mjs infra-reset --confirm-local-data-loss
  node scripts/dev.mjs infra-logs [service...]
  node scripts/dev.mjs web
  node scripts/dev.mjs api
  node scripts/dev.mjs health
  node scripts/dev.mjs check
`;
}

export async function main(argv = process.argv.slice(2)) {
  const [command, ...args] = argv;

  switch (command) {
    case "infra-up":
      await infraUp();
      break;
    case "infra-down":
      infraDown();
      break;
    case "infra-reset":
      infraReset(args);
      break;
    case "infra-logs":
      infraLogs(args);
      break;
    case "web":
      webDev();
      break;
    case "api":
      apiDev();
      break;
    case "health":
      await health();
      break;
    case "check":
      fullCheck();
      break;
    case "help":
    case "--help":
    case "-h":
    case undefined:
      console.log(usage());
      break;
    default:
      throw new Error(`Unknown command: ${command}\n\n${usage()}`);
  }
}

const invokedDirectly = process.argv[1]
  && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;

if (invokedDirectly) {
  main().catch((error) => {
    console.error(`[local] ${error.message}`);
    process.exitCode = 1;
  });
}
