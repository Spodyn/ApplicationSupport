package com.unifiedsupportinbox.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;

class SafeProviderFileDownloaderTests {

    @Test
    void rejectsDnsRebindingStylePrivateResolution() throws Exception {
        SafeProviderFileDownloader downloader = downloader("127.0.0.1");

        assertThatThrownBy(() -> downloader.validateForDownload(SafeProviderFileDownloader.Provider.SLACK,
                URI.create("https://files.slack.com/files-pri/T1/F1/report.pdf")))
                .isInstanceOf(UnsafeProviderDownloadException.class)
                .hasMessageContaining("non-public");
    }

    @Test
    void rejectsPrivateIpv4AndIpv6Ranges() throws Exception {
        for (String address : new String[] {"10.0.0.1", "100.64.0.1", "169.254.169.254", "192.168.1.1", "fc00::1", "fe80::1", "::1"}) {
            SafeProviderFileDownloader downloader = downloader(address);
            assertThatThrownBy(() -> downloader.validateForDownload(SafeProviderFileDownloader.Provider.TEAMS,
                    URI.create("https://graph.microsoft.com/v1.0/me/drive/items/1/content")))
                    .isInstanceOf(UnsafeProviderDownloadException.class);
        }
    }

    @Test
    void rejectsRedirectDestinationThatIsNotAnAllowedPublicProviderHost() throws Exception {
        SafeProviderFileDownloader downloader = downloader("8.8.8.8");

        assertThatThrownBy(() -> downloader.validateForDownload(SafeProviderFileDownloader.Provider.SLACK,
                URI.create("https://169.254.169.254/latest/meta-data")))
                .isInstanceOf(UnsafeProviderDownloadException.class)
                .hasMessageContaining("allowlisted");
    }

    @Test
    void allowsAnHttpsProviderDownloadResolvedToPublicAddress() throws Exception {
        SafeProviderFileDownloader downloader = downloader("8.8.8.8");

        assertThat(downloader.validateForDownload(SafeProviderFileDownloader.Provider.TELEGRAM,
                URI.create("https://api.telegram.org/file/bot-token/file_1")))
                .isEqualTo(URI.create("https://api.telegram.org/file/bot-token/file_1"));
    }

    @Test
    void enforcesMaximumResponseSizeBeforeReturningBytes() {
        byte[] oversized = new byte[25 * 1024 * 1024 + 1];

        assertThatThrownBy(() -> SafeProviderFileDownloader.readBounded(new ByteArrayInputStream(oversized)))
                .isInstanceOf(UnsafeProviderDownloadException.class)
                .hasMessageContaining("25 MiB");
    }

    private static SafeProviderFileDownloader downloader(String answer) throws Exception {
        InetAddress resolved = InetAddress.getByName(answer);
        return new SafeProviderFileDownloader(HttpClient.newBuilder().build(), ignored -> new InetAddress[] {resolved});
    }
}
