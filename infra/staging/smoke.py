#!/usr/bin/env python3
"""Check real HTTPS login, secure cookies, API and authenticated STOMP without printing credentials."""
import argparse
import base64
import hashlib
import html
import http.cookiejar
import json
import os
from pathlib import Path
import re
import socket
import ssl
import struct
import urllib.error
import urllib.parse
import urllib.request


def check(origin: str, email: str | None = None, password_file: Path | None = None, ca_file: str | None = None) -> None:
    if bool(email) != bool(password_file):
        raise ValueError("Both smoke account email and password file are required")
    url = urllib.parse.urlsplit(origin)
    if url.scheme != "https" or not url.hostname or url.path not in ("", "/") or url.username or url.password or url.query or url.fragment:
        raise ValueError("Smoke requires an HTTPS origin")
    context = ssl.create_default_context(cafile=ca_file)
    cookies = http.cookiejar.CookieJar()
    client = urllib.request.build_opener(urllib.request.HTTPSHandler(context=context), urllib.request.HTTPCookieProcessor(cookies))

    def request(path, body=None, expected=200):
        headers = {"Origin": origin}
        if body is not None:
            headers.update({"Content-Type": "application/json", "X-XSRF-TOKEN": next((urllib.parse.unquote(c.value) for c in cookies if c.name == "XSRF-TOKEN"), "")})
        try:
            response = client.open(urllib.request.Request(origin + path, data=None if body is None else json.dumps(body).encode(), headers=headers), timeout=20)
        except urllib.error.HTTPError as failure:
            if failure.code == expected:
                return b""
            raise RuntimeError(f"HTTPS smoke failed: {path}, HTTP {failure.code}") from None
        with response:
            if response.status != expected:
                raise RuntimeError("Unexpected HTTPS smoke status")
            return response.read(2 * 1024 * 1024)

    login_html = request("/login")
    if b">STAGING</div>" not in login_html:
        raise AssertionError("Staging must be visibly marked")
    assets = re.findall(r'(?:src|href)="(/_next/static/[^\"]+)"', login_html.decode())
    for suffix in (".css", ".js"):
        asset = next((html.unescape(path) for path in assets if suffix in path), None)
        if asset is None or not request(asset):
            raise AssertionError("Standalone static application assets are missing")
    request("/actuator/health", expected=404)
    request("/api/v1/auth/me", expected=401)
    request("/api/v1/cases", expected=401)
    request("/api/v1/statistics/records", expected=401)
    if password_file is None:
        print("PASS: public HTTPS, staging marker, static assets and anonymous API isolation. Authenticated acceptance NOT RUN.")
        return
    request("/api/v1/auth/login", {"email": email, "password": password_file.read_text(encoding="utf-8").strip()})
    identity = json.loads(request("/api/v1/auth/me"))
    if identity.get("email", "").lower() != email.lower():
        raise AssertionError("Login did not return the expected staging account")
    session = next(c for c in cookies if c.name == "USI_SESSION")
    if not session.secure or not session.has_nonstandard_attr("HttpOnly") or session.get_nonstandard_attr("SameSite").lower() != "lax":
        raise AssertionError("Secure session cookie attributes are missing")
    cases = json.loads(request("/api/v1/cases"))
    if not isinstance(cases.get("items"), list):
        raise AssertionError("Case inbox contract is unavailable")

    key = base64.b64encode(os.urandom(16)).decode()
    cookie_header = "; ".join(f"{c.name}={c.value}" for c in cookies)
    connection = context.wrap_socket(socket.create_connection((url.hostname, url.port or 443), timeout=15), server_hostname=url.hostname)
    with connection:
        upgrade = (f"GET /ws HTTP/1.1\r\nHost: {url.netloc}\r\nOrigin: {origin}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                   f"Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Protocol: v12.stomp\r\nCookie: {cookie_header}\r\n\r\n")
        connection.sendall(upgrade.encode())
        stream = connection.makefile("rb")
        status = stream.readline(4096)
        headers = {}
        for _ in range(100):
            line = stream.readline(8192)
            if line == b"\r\n":
                break
            name, value = line.decode().split(":", 1)
            headers[name.lower()] = value.strip()
        expected_accept = base64.b64encode(hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
        if b" 101 " not in status or headers.get("sec-websocket-accept") != expected_accept:
            raise AssertionError("Authenticated WebSocket upgrade failed")
        payload = f"CONNECT\naccept-version:1.2\nhost:{url.hostname}\nheart-beat:0,0\n\n\0".encode()
        mask = os.urandom(4)
        connection.sendall(bytes([0x81, 0x80 | len(payload)]) + mask + bytes(value ^ mask[index % 4] for index, value in enumerate(payload)))
        prefix = stream.read(2)
        if len(prefix) != 2:
            raise AssertionError("WebSocket closed before STOMP CONNECTED")
        length = prefix[1] & 127
        if length == 126:
            length = struct.unpack("!H", stream.read(2))[0]
        elif length == 127:
            length = struct.unpack("!Q", stream.read(8))[0]
        if length > 65536 or prefix[0] & 15 != 1 or not stream.read(length).startswith(b"CONNECTED\n"):
            raise AssertionError("STOMP CONNECTED was not received")
    request("/api/v1/auth/logout", {}, expected=204)
    request("/api/v1/auth/me", expected=401)
    print("PASS: HTTPS UI, staging marker, login, Secure/HttpOnly/SameSite cookie, API, WebSocket/STOMP and logout. No secrets displayed.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--origin", default="https://application-support.duckdns.org")
    parser.add_argument("--email")
    parser.add_argument("--password-file", type=Path)
    parser.add_argument("--public-only", action="store_true")
    parser.add_argument("--ca-file")
    args = parser.parse_args()
    if args.public_only and (args.email or args.password_file):
        parser.error("Public-only checks must not receive credentials")
    if not args.public_only and (not args.email or not args.password_file):
        parser.error("Authenticated checks require --email and --password-file")
    check(args.origin.rstrip("/"), args.email, args.password_file, args.ca_file)
