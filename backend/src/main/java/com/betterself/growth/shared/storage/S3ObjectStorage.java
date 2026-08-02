package com.betterself.growth.shared.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.time.Duration;

@Component
@ConditionalOnProperty(name = "app.object-storage.provider", havingValue = "s3")
public class S3ObjectStorage implements ObjectStorage {

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;

    public S3ObjectStorage(
        @Value("${app.object-storage.endpoint}") String endpoint,
        @Value("${app.object-storage.region}") String region,
        @Value("${app.object-storage.bucket}") String bucket,
        @Value("${app.object-storage.access-key}") String accessKey,
        @Value("${app.object-storage.secret-key}") String secretKey
    ) {
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        this.client = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.of(region))
            .credentialsProvider(credentials).forcePathStyle(true).build();
        this.presigner = S3Presigner.builder().endpointOverride(URI.create(endpoint)).region(Region.of(region))
            .credentialsProvider(credentials).build();
        this.bucket = bucket;
        ensureBucket();
    }

    private void ensureBucket() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException exception) {
            client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        }
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType)
            .serverSideEncryption(ServerSideEncryption.AES256).build(), RequestBody.fromBytes(content));
    }

    @Override
    public byte[] get(String key) {
        return client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
    }

    @Override
    public String presignDownload(String key, Duration ttl) {
        return presigner.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(ttl)
            .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build()).build()).url().toString();
    }

    @Override
    public String presignUpload(String key, String contentType, Duration ttl) {
        return presigner.presignPutObject(PutObjectPresignRequest.builder().signatureDuration(ttl)
            .putObjectRequest(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType)
                .serverSideEncryption(ServerSideEncryption.AES256).build()).build()).url().toString();
    }

    @Override
    public void delete(String key) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }
}
