package com.yoox.service.storage.service.impl;

import com.yoox.api.media.AbstractMediaService;
import com.yoox.great.mqtt.core.consume.MqttReply;
import com.yoox.great.mqtt.model.media.StorageConfigGet;
import com.yoox.great.mqtt.model.storage.StsCredentialsResponse;
import com.yoox.great.mqtt.handle.requests.TopicRequestsRequest;
import com.yoox.great.mqtt.handle.requests.TopicRequestsResponse;
import com.yoox.great.oss.model.OssConfiguration;
import com.yoox.great.oss.service.impl.OssServiceContext;
import com.yoox.service.storage.service.IStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.MessageHeaders;
import org.springframework.stereotype.Service;


@Service
public class StorageServiceImpl extends AbstractMediaService implements IStorageService {

    @Autowired
    private OssServiceContext ossService;

    @Override
    public StsCredentialsResponse getSTSCredentials() {
        return new StsCredentialsResponse()
                .setEndpoint(OssConfiguration.publicEndpoint())
                .setBucket(OssConfiguration.bucket)
                .setCredentials(ossService.getCredentials())
                .setProvider(OssConfiguration.provider)
                .setObjectKeyPrefix(OssConfiguration.objectDirPrefix)
                .setRegion(OssConfiguration.region);
    }

    @Override
    public TopicRequestsResponse<MqttReply<StsCredentialsResponse>> storageConfigGet(TopicRequestsRequest<StorageConfigGet> response, MessageHeaders headers) {
        return new TopicRequestsResponse<MqttReply<StsCredentialsResponse>>().setData(MqttReply.success(getSTSCredentials()));
    }
}
