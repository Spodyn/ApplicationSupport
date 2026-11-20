#!/usr/bin/env python3
"""Create isolated staging credentials/TLS once. Never print or rotate existing secrets."""
import argparse
import os
from pathlib import Path
import re
import secrets
import subprocess


def initialize(root: Path, email: str) -> None:
    if not root.is_absolute() or root.is_symlink():
        raise ValueError("Use an absolute, non-symlink staging state directory")
    if not re.fullmatch(r"[A-Za-z0-9_.+%-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}", email):
        raise ValueError("A valid staging administrator email is required")
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(root, 0o700)
    for directory in ("core", "integrations", "minio-tls", "trust", "trust/CAs", "ca", "recovery"):
        path = root / directory
        if path.is_symlink():
            raise ValueError("Secret directories must not be symlinks")
        path.mkdir(exist_ok=True, mode=0o750)
        if os.name == "posix":
            os.chown(path, 0, 10001)
        os.chmod(path, 0o700 if directory == "recovery" else 0o750 if directory in ("core", "integrations", "minio-tls", "ca") else 0o755)

    values = {
        "spring.datasource.username": "usi_staging",
        "spring.datasource.password": secrets.token_hex(32),
        "spring.rabbitmq.username": "usi_staging",
        "spring.rabbitmq.password": secrets.token_hex(32),
        "usi.object-storage.access-key": "usi-staging",
        "usi.object-storage.secret-key": secrets.token_hex(32),
        "usi.pagination.cursor-signing-key": secrets.token_hex(32),
        "bootstrap-admin-password": secrets.token_hex(24),
    }
    for name, value in values.items():
        path = root / "core" / name
        if path.is_symlink():
            raise ValueError("Secret files must not be symlinks")
        if not path.exists():
            with path.open("x", encoding="utf-8") as output:
                output.write(value + "\n")
        os.chmod(path, 0o640)
        if os.name == "posix":
            os.chown(path, 0, 10001)

    def openssl(*args: str) -> None:
        subprocess.run(["openssl", *args], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    ca_key = root / "ca/private.key"
    ca_cert = root / "trust/CAs/minio-ca.crt"
    if not ca_key.exists() and not ca_cert.exists():
        openssl("req", "-x509", "-newkey", "rsa:3072", "-nodes", "-days", "3650",
                "-subj", "/CN=USI staging object storage CA", "-keyout", str(ca_key), "-out", str(ca_cert))
        os.chmod(ca_key, 0o600)
        os.chmod(ca_cert, 0o644)
    if not ca_key.is_file() or not ca_cert.is_file():
        raise ValueError("Incomplete CA state; repair explicitly, do not rotate silently")
    key, cert = root / "minio-tls/private.key", root / "minio-tls/public.crt"
    if not key.exists() and not cert.exists():
        request = root / "ca/minio.csr"
        extensions = root / "ca/minio.ext"
        extensions.write_text("subjectAltName=DNS:minio,DNS:localhost\nextendedKeyUsage=serverAuth\nbasicConstraints=CA:FALSE\n", encoding="utf-8")
        openssl("req", "-new", "-newkey", "rsa:3072", "-nodes", "-subj", "/CN=minio",
                "-keyout", str(key), "-out", str(request))
        openssl("x509", "-req", "-in", str(request), "-CA", str(ca_cert), "-CAkey", str(ca_key),
                "-CAcreateserial", "-days", "365", "-extfile", str(extensions), "-out", str(cert))
        os.chmod(key, 0o640)
        if os.name == "posix":
            os.chown(key, 0, 10001)
    if not key.is_file() or not cert.is_file():
        raise ValueError("Incomplete MinIO certificate state")
    config = root / "runtime.env"
    if not config.exists():
        config.write_text(f"USI_STATE_DIRECTORY={root.as_posix()}\nSTAGING_ADMIN_EMAIL={email}\n", encoding="utf-8")
    os.chmod(config, 0o600)
    backup_key = root / "recovery/backup.agekey"
    if not backup_key.exists():
        subprocess.run(["age-keygen", "-o", str(backup_key)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    os.chmod(backup_key, 0o600)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--admin-email", required=True)
    args = parser.parse_args()
    initialize(args.directory, args.admin_email)
    print("Staging state initialized; existing credentials preserved. No secret values displayed.")
