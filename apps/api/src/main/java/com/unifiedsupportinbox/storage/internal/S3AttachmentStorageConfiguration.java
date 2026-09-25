package com.unifiedsupportinbox.storage.internal;

import com.unifiedsupportinbox.UsiConfigurationProperties;
import com.unifiedsupportinbox.storage.AttachmentObjectStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {
        "usi.object-storage.access-key",
        "usi.object-storage.secret-key"
})
class S3AttachmentStorageConfiguration {

    @Bean(destroyMethod = "close")
    S3Client attachmentS3Client(UsiConfigurationProperties properties, Environment environment) {
        UsiConfigurationProperties.ObjectStorage storage = properties.objectStorage();
        String accessKey = environment.getRequiredProperty("usi.object-storage.access-key");
        String secretKey = environment.getRequiredProperty("usi.object-storage.secret-key");
        if (accessKey.isBlank() || secretKey.isBlank()) {
            throw new IllegalStateException("Object-storage credentials must not be blank.");
        }
        return S3Client.builder()
                .endpointOverride(storage.endpoint())
                .region(Region.of(storage.region()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    @Bean
    AttachmentObjectStorage attachmentObjectStorage(S3Client attachmentS3Client, UsiConfigurationProperties properties) {
        return new S3AttachmentObjectStorage(
                attachmentS3Client,
                properties.objectStorage().attachmentsBucket());
    }
}
