package com.unifiedsupportinbox.provider;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Downloads provider-owned file URLs without ever accepting arbitrary network destinations.
 *
 * <p>Callers must obtain the URL from the provider's authenticated file API using a provider
 * file identifier.  The URL is then checked again for every redirect and every resolved address
 * must be globally routable before a request is made.</p>
 */
public final class SafeProviderFileDownloader {

    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_BYTES = 25 * 1024 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Map<Provider, List<String>> ALLOWED_HOSTS = allowedHosts();

    private final HttpClient client;
    private final AddressResolver resolver;

    public SafeProviderFileDownloader() {
        this(HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(), InetAddress::getAllByName);
    }

    SafeProviderFileDownloader(HttpClient client, AddressResolver resolver) {
        this.client = Objects.requireNonNull(client, "client");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    public DownloadedFile download(Provider provider, URI providerFileUrl, byte[] bearerToken) {
        if (bearerToken == null || bearerToken.length == 0) {
            throw new IllegalArgumentException("Provider authorization token is required.");
        }
        URI current = validateForDownload(provider, providerFileUrl);
        byte[] authorization = null;
        try {
            authorization = new byte["Bearer ".length() + bearerToken.length];
            System.arraycopy("Bearer ".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, authorization, 0, "Bearer ".length());
            System.arraycopy(bearerToken, 0, authorization, "Bearer ".length(), bearerToken.length);

            for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
                HttpRequest request = HttpRequest.newBuilder(current)
                        .timeout(REQUEST_TIMEOUT)
                        .header("Authorization", new String(authorization, java.nio.charset.StandardCharsets.US_ASCII))
                        .GET()
                        .build();
                HttpResponse<InputStream> response = send(request);
                if (isRedirect(response.statusCode())) {
                    try (InputStream ignored = response.body()) {
                        if (redirects == MAX_REDIRECTS) throw new UnsafeProviderDownloadException("Too many provider redirects.");
                        String location = response.headers().firstValue("Location")
                                .orElseThrow(() -> new UnsafeProviderDownloadException("Provider redirect has no location."));
                        current = validateForDownload(provider, current.resolve(location));
                        continue;
                    } catch (IOException exception) {
                        throw new UnsafeProviderDownloadException("Could not close provider response.", exception);
                    }
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    close(response.body());
                    throw new UnsafeProviderDownloadException("Provider download returned HTTP " + response.statusCode() + ".");
                }
                try (InputStream body = response.body()) {
                    return new DownloadedFile(readBounded(body), response.headers().firstValue("Content-Type").orElse(null));
                } catch (IOException exception) {
                    throw new UnsafeProviderDownloadException("Provider download could not be read.", exception);
                }
            }
            throw new UnsafeProviderDownloadException("Too many provider redirects.");
        } finally {
            if (authorization != null) Arrays.fill(authorization, (byte) 0);
        }
    }

    URI validateForDownload(Provider provider, URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new UnsafeProviderDownloadException("Provider file URL must be an absolute HTTPS URL without user info or fragment.");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (ALLOWED_HOSTS.get(provider).stream().noneMatch(allowed -> host.equals(allowed) || host.endsWith("." + allowed))) {
            throw new UnsafeProviderDownloadException("Provider file URL host is not allowlisted.");
        }
        try {
            InetAddress[] addresses = resolver.resolve(host);
            if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(SafeProviderFileDownloader::isPrivateOrSpecial)) {
                throw new UnsafeProviderDownloadException("Provider file URL resolves to a non-public address.");
            }
        } catch (UnknownHostException exception) {
            throw new UnsafeProviderDownloadException("Provider file URL could not be resolved.", exception);
        }
        return uri.normalize();
    }

    private HttpResponse<InputStream> send(HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new UnsafeProviderDownloadException("Provider download was interrupted.", exception);
        } catch (IOException exception) {
            throw new UnsafeProviderDownloadException("Provider download failed.", exception);
        }
    }

    static byte[] readBounded(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new UnsafeProviderDownloadException("Provider file exceeds the 25 MiB limit.");
        return bytes;
    }

    private static boolean isPrivateOrSpecial(InetAddress address) {
        return address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()
                || isCarrierGradeNat(address) || isIpv6UniqueLocal(address);
    }

    private static boolean isCarrierGradeNat(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64;
    }

    private static boolean isIpv6UniqueLocal(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static void close(InputStream input) {
        try { input.close(); } catch (IOException ignored) { }
    }

    private static Map<Provider, List<String>> allowedHosts() {
        Map<Provider, List<String>> hosts = new EnumMap<>(Provider.class);
        hosts.put(Provider.SLACK, List.of("slack.com"));
        hosts.put(Provider.TEAMS, List.of("graph.microsoft.com"));
        hosts.put(Provider.TELEGRAM, List.of("api.telegram.org"));
        return Map.copyOf(hosts);
    }

    public enum Provider { SLACK, TEAMS, TELEGRAM }

    public record DownloadedFile(byte[] bytes, String contentType) {
        public DownloadedFile { bytes = bytes.clone(); }
    }

    @FunctionalInterface
    interface AddressResolver { InetAddress[] resolve(String host) throws UnknownHostException; }
}
