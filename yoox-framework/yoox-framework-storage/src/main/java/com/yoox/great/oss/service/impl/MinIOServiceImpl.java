package com.yoox.great.oss.service.impl;

import com.yoox.great.context.enums.oss.OssTypeEnum;
import com.yoox.great.context.model.CredentialsToken;
import com.yoox.great.oss.model.OssConfiguration;
import com.yoox.great.oss.service.IOssService;
import io.minio.*;
import io.minio.credentials.AssumeRoleProvider;
import io.minio.credentials.Credentials;
import io.minio.errors.*;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

@Service
@Slf4j
public class MinIOServiceImpl implements IOssService {

    private MinioClient client;

    /** 预签名 URL 下发给外部设备，host 必须用对外端点（未配置时与内部端点相同）。 */
    private MinioClient externalClient;
    
    @Override
    public OssTypeEnum getOssType() {
        return OssTypeEnum.MINIO;
    }

    @Override
    public CredentialsToken getCredentials() {
        try {
            AssumeRoleProvider provider = new AssumeRoleProvider(OssConfiguration.endpoint, OssConfiguration.accessKey,
                    OssConfiguration.secretKey, Math.toIntExact(OssConfiguration.expire),
                    null, OssConfiguration.region, null, null, null, null);
            Credentials credential = provider.fetch();
            return new CredentialsToken(credential.accessKey(), credential.secretKey(), credential.sessionToken(), OssConfiguration.expire);
        } catch (NoSuchAlgorithmException e) {
            log.debug("Failed to obtain sts.");
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public URL getObjectUrl(String bucket, String objectKey) {
        try {
            return new URL(
                    externalClient.getPresignedObjectUrl(
                                    GetPresignedObjectUrlArgs.builder()
                                            .method(Method.GET)
                                            .bucket(bucket)
                                            .object(objectKey)
                                            .expiry(Math.toIntExact(OssConfiguration.expire))
                                            .build()));
        } catch (ErrorResponseException | InsufficientDataException | InternalException |
                InvalidKeyException | InvalidResponseException | IOException |
                NoSuchAlgorithmException | XmlParserException | ServerException e) {
            throw new RuntimeException("The file does not exist on the OssConfiguration.");
        }
    }

    @Override
    public Boolean deleteObject(String bucket, String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (MinioException | NoSuchAlgorithmException | IOException | InvalidKeyException e) {
            log.error("Failed to delete file.");
            e.printStackTrace();
            return false;
        }
        return true;
    }

    @Override
    public InputStream getObject(String bucket, String objectKey) {
        try {
            GetObjectResponse object = client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
            return new ByteArrayInputStream(object.readAllBytes());
        } catch (ErrorResponseException | InsufficientDataException | InternalException | InvalidKeyException | InvalidResponseException | IOException | NoSuchAlgorithmException | ServerException | XmlParserException e) {
            e.printStackTrace();
        }
        return InputStream.nullInputStream();
    }

    @Override
    public void putObject(String bucket, String objectKey, InputStream input) {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            throw new RuntimeException("The filename already exists.");
        } catch (MinioException | InvalidKeyException | IOException | NoSuchAlgorithmException e) {
            log.info("The file does not exist, start uploading.");
            try {
                ObjectWriteResponse response = client.putObject(
                        PutObjectArgs.builder().bucket(bucket).object(objectKey).stream(input, input.available(), 0).build());
                log.info("Upload FlighttaskCreateFile: {}", response.etag());
            } catch (MinioException | IOException | InvalidKeyException | NoSuchAlgorithmException ex) {
                log.error("Failed to upload FlighttaskCreateFile {}.", objectKey);
                ex.printStackTrace();
            }
        }
    }

    public void createClient() {
        // 以 externalClient 为完成标志：若上次初始化中途失败（如 region 非法），
        // 仅判断 client 会让 externalClient 永远为 null。
        if (Objects.nonNull(this.externalClient)) {
            return;
        }
        this.client = MinioClient.builder()
                .endpoint(OssConfiguration.endpoint)
                .credentials(OssConfiguration.accessKey, OssConfiguration.secretKey)
                //.region(OssConfiguration.region)
                .build();
        // 预签名 URL 客户端：对外端点与内部端点相同时直接复用。
        // 必须显式指定 region：跳过 GetBucketLocation 网络查询，使预签名成为纯本地
        // 计算——桥接网络下容器无法回访宿主局域网 IP，联网查询会挂起导致 504。
        // 注意 yml 空值会绑定为空串，region("") 会抛异常，需用 hasText 判断。
        String externalEndpoint = OssConfiguration.publicEndpoint();
        if (externalEndpoint.equals(OssConfiguration.endpoint)) {
            this.externalClient = this.client;
        } else {
            String region = org.springframework.util.StringUtils.hasText(OssConfiguration.region)
                    ? OssConfiguration.region : "us-east-1";
            this.externalClient = MinioClient.builder()
                    .endpoint(externalEndpoint)
                    .credentials(OssConfiguration.accessKey, OssConfiguration.secretKey)
                    .region(region)
                    .build();
        }
    }
}
