#!/usr/bin/env python3
"""Disposable real staging-image acceptance. Never uses developer .env or providers."""
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[2]


def run(*args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def main():
    project = "usi-staging-test-" + uuid.uuid4().hex[:10]
    env = {key: value for key, value in os.environ.items() if not key.startswith(("USI_", "STAGING_", "COMPOSE_"))}
    with tempfile.TemporaryDirectory(prefix=project) as temporary:
        directory = Path(temporary)
        state = directory / "state"
        state.mkdir()
        # Root helper is restricted to this random disposable directory. On
        # Windows this also supplies the Linux openssl/age tools, without WSL paths.
        run("docker", "run", "--rm", "--mount", f"type=bind,source={state},target=/state", "--mount",
            f"type=bind,source={ROOT / 'infra/staging'},target=/source,readonly", "ubuntu:24.04", "bash", "-ec",
            "apt-get update -qq; apt-get install -y python3 openssl age >/dev/null; python3 /source/init_state.py --directory /state --admin-email admin@example.test", env=env)
        https_port = port()
        values = {
            "USI_STATE_DIRECTORY": state.as_posix(), "USI_PUBLIC_HOST": f"localhost:{https_port}",
            "USI_SITE_ADDRESS": "https://localhost", "USI_TLS_DIRECTIVE": "tls internal",
            "USI_BIND_ADDRESS": "127.0.0.1", "USI_HTTP_PORT": str(port()), "USI_HTTPS_PORT": str(https_port),
            "STAGING_ADMIN_EMAIL": "admin@example.test", "STAGING_BOOTSTRAP_ENABLED": "true",
            "USI_API_IMAGE": "usi-staging-test-api:local", "USI_WEB_IMAGE": "usi-staging-test-web:local",
            "USI_MINIO_IMAGE": "usi/minio:RELEASE.2025-09-07T16-13-09Z", "USI_MC_IMAGE": "usi/minio-mc:RELEASE.2025-08-13T08-35-41Z",
        }
        config = directory / "test.env"

        def save():
            config.write_text("".join(f"{key}={value}\n" for key, value in values.items()), encoding="utf-8")

        save()
        compose = ["docker", "compose", "--project-name", project, "--env-file", str(config), "-f", str(ROOT / "infra/staging/compose.yaml")]
        try:
            run(*compose, "up", "-d", "--wait", "--wait-timeout", "600", env=env)
            # Remove the one-time bootstrap trigger before any restart.
            values["STAGING_BOOTSTRAP_ENABLED"] = "false"
            save()
            run(*compose, "up", "-d", "--wait", "--wait-timeout", "180", "api", env=env)
            caddy = subprocess.check_output([*compose, "ps", "-q", "caddy"], env=env, text=True).strip()
            ca = directory / "caddy-ca.crt"
            run("docker", "cp", f"{caddy}:/data/caddy/pki/authorities/local/root.crt", str(ca), env=env)
            smoke = [sys.executable, str(ROOT / "infra/staging/smoke.py"), "--origin", f"https://localhost:{https_port}",
                     "--email", "admin@example.test", "--password-file", str(state / "core/bootstrap-admin-password"), "--ca-file", str(ca)]
            if os.name == "posix" and os.geteuid() != 0:
                smoke = ["sudo", *smoke]
            run(*smoke, env=env)
            # Verify the mounted MinIO key/CA with real encrypted object I/O.
            object_check = '''mc alias set staging https://minio:9000 "$(cat /run/usi-core/usi.object-storage.access-key)" "$(cat /run/usi-core/usi.object-storage.secret-key)" >/dev/null
printf test-only-persistent-object | mc pipe staging/usi-attachments-staging/staging-test.txt >/dev/null
test "$(mc cat staging/usi-attachments-staging/staging-test.txt)" = test-only-persistent-object'''
            run(*compose, "run", "--rm", "--no-deps", "--entrypoint", "/bin/sh", "minio-init", "-ec", object_check, env=env)
            run(*compose, "down", env=env)
            run(*compose, "up", "-d", "--wait", "--wait-timeout", "300", env=env)
            run(*smoke, env=env)
            count = subprocess.check_output([*compose, "exec", "-T", "postgres", "psql", "-U", "usi_staging", "-d", "usi_staging", "-Atc", "SELECT count(*) FROM users"], env=env, text=True).strip()
            if count != "1":
                raise AssertionError("Restart duplicated/lost the bootstrap administrator")
            read_object = object_check.splitlines()[0] + '\ntest "$(mc cat staging/usi-attachments-staging/staging-test.txt)" = test-only-persistent-object'
            run(*compose, "run", "--rm", "--no-deps", "--entrypoint", "/bin/sh", "minio-init", "-ec", read_object, env=env)
            print("PASS: real staging images, TLS/CA, login/API/STOMP, one-time bootstrap, container recreation, persistent DB/objects/ACME.")
        except Exception:
            run(*compose, "ps", "--all", env=env)
            subprocess.run([*compose, "logs", "--no-log-prefix", "--tail", "80", "api", "minio", "minio-init", "rabbitmq", "clamav"], env=env, check=False)
            raise
        finally:
            # This project name is generated here and cannot refer to the local or VPS stack.
            if not project.startswith("usi-staging-test-"):
                raise AssertionError("Refusing cleanup outside disposable project")
            run(*compose, "down", "--volumes", "--remove-orphans", env=env)
            if os.name == "posix" and os.geteuid() != 0:
                # Restore ownership of only the verified disposable helper output.
                run("sudo", "chown", "-R", f"{os.getuid()}:{os.getgid()}", str(state), env=env)


if __name__ == "__main__":
    main()
