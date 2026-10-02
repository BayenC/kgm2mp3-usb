#!/usr/bin/env python3
"""Explicit proxy handling shared by isolated download and Java build tools.

No proxy URL, username, password, or full command line is printed. curl receives
proxy configuration through its environment. Java receives discrete system
properties, avoiding JAVA_TOOL_OPTIONS (which the JVM prints at startup).
"""
import hashlib
import os
from pathlib import Path
import shlex
import subprocess
import sys
from urllib.parse import unquote, urlsplit


def proxy_environment(source=None):
    env = dict(os.environ if source is None else source)

    def value(name):
        return env.get(name.lower()) or env.get(name.upper())

    shared = value("all_proxy")
    http = value("http_proxy") or shared
    https = value("https_proxy") or http or shared
    for name, setting in (("http_proxy", http), ("https_proxy", https), ("all_proxy", shared),
                          ("no_proxy", value("no_proxy"))):
        if setting:
            env[name] = setting
            env[name.upper()] = setting
    return env


def _proxy(value):
    if any(ord(c) < 32 for c in value):
        raise ValueError("Proxy configuration contains control characters")
    parsed = urlsplit(value if "://" in value else "http://" + value)
    if parsed.scheme not in ("http", "https", "socks", "socks4", "socks5", "socks5h") or not parsed.hostname:
        raise ValueError("Unsupported proxy configuration")
    try:
        port = parsed.port or (1080 if parsed.scheme.startswith("socks") else 443 if parsed.scheme == "https" else 80)
    except ValueError:
        raise ValueError("Proxy port is invalid") from None
    return parsed, port


def java_proxy_options(source=None):
    env = proxy_environment(source)
    options = []
    socks = None
    for scheme in ("http", "https"):
        value = env.get(scheme + "_proxy")
        if not value:
            continue
        parsed, port = _proxy(value)
        if parsed.scheme.startswith("socks"):
            candidate = (parsed.hostname, port, parsed.username, parsed.password)
            if socks is not None and socks != candidate:
                raise ValueError("Java requires one shared SOCKS proxy")
            socks = candidate
            continue
        options += [f"-D{scheme}.proxyHost={parsed.hostname}", f"-D{scheme}.proxyPort={port}"]
        for name, setting in (("proxyUser", parsed.username), ("proxyPassword", parsed.password)):
            if setting is not None:
                options.append(f"-D{scheme}.{name}={unquote(setting)}")
    if socks:
        host, port, username, password = socks
        options += [f"-DsocksProxyHost={host}", f"-DsocksProxyPort={port}"]
        if username is not None:
            options.append("-Djava.net.socks.username=" + unquote(username))
        if password is not None:
            options.append("-Djava.net.socks.password=" + unquote(password))
    if options:
        bypass = ["localhost", "127.*", "[::1]"]
        for item in env.get("no_proxy", "").split(","):
            item = item.strip()
            if not item or "/" in item:
                continue
            bypass.extend([item[1:], "*" + item] if item.startswith(".") else [item])
        options.append("-Dhttp.nonProxyHosts=" + "|".join(dict.fromkeys(bypass)))
    return options


def sdkmanager_proxy_options(source=None):
    env = proxy_environment(source)
    value = env.get("https_proxy") or env.get("http_proxy")
    if not value:
        return []
    parsed, port = _proxy(value)
    kind = "socks" if parsed.scheme.startswith("socks") else "http"
    return ["--proxy=" + kind, "--proxy_host=" + parsed.hostname, "--proxy_port=" + str(port)]


def java_environment(source=None):
    env = proxy_environment(source)
    flags = shlex.join(java_proxy_options(env))
    if flags:
        env["JAVA_OPTS"] = (env.get("JAVA_OPTS", "") + " " + flags).strip()
    return env


def _curl(url, timeout, output=None):
    command = ["curl", "--fail", "--location", "--silent", "--show-error", "--retry", "2",
               "--connect-timeout", "30", "--max-time", str(timeout), "--url", url]
    if output is not None:
        command += ["--output", str(output)]
    result = subprocess.run(command, env=proxy_environment(), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        # Never echo stderr: third-party network errors can include proxy URLs.
        raise RuntimeError(f"Download failed (curl exit {result.returncode}); check the configured network/proxy")
    return result.stdout


def read_url(url, timeout=180):
    return _curl(url, timeout)


def download_url(url, target, checksum=None, algorithm="sha256", timeout=1800):
    target = Path(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.exists():
        temporary = target.with_name(target.name + ".partial")
        _curl(url, timeout, temporary)
        temporary.replace(target)
    if checksum:
        digest = hashlib.new(algorithm)
        with target.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
        if digest.hexdigest().lower() != checksum.lower():
            target.unlink()
            raise RuntimeError("Downloaded archive checksum does not match: " + target.name)
    return target


def launch_gradle(gradle_path, arguments):
    env = proxy_environment()
    options = java_proxy_options(env)
    if options:
        env["GRADLE_OPTS"] = (env.get("GRADLE_OPTS", "") + " " + shlex.join(options)).strip()
    # JVM flags also reach the daemon as explicit Gradle system properties.
    os.execve(gradle_path, [gradle_path, "--console=plain", *options, *arguments], env)


if __name__ == "__main__":
    if len(sys.argv) < 3 or sys.argv[1] != "gradle":
        raise SystemExit("This helper is invoked by scripts/gradle_local.sh")
    launch_gradle(sys.argv[2], sys.argv[3:])
