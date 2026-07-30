package com.yoox.service.manage.service.impl;

import com.yoox.great.mqtt.enums.livestream.VideoTypeEnum;
import com.yoox.service.manage.model.dto.CapacityVideoDTO;
import com.yoox.service.manage.model.receiver.CapacityVideoReceiver;
import com.yoox.service.manage.service.ICameraVideoService;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CameraVideoServiceImpl implements ICameraVideoService {

    @Override
    public CapacityVideoDTO receiver2Dto(CapacityVideoReceiver receiver) {
        if (null == receiver) {
            return null;
        }
        CapacityVideoDTO.CapacityVideoDTOBuilder builder = CapacityVideoDTO.builder()
                .id(UUID.randomUUID().toString())
                .index(receiver.getVideoIndex())
                .type(receiver.getVideoType().getType());
        if (null != receiver.getSwitchableVideoTypes()) {
            builder.switchVideoTypes(receiver.getSwitchableVideoTypes().stream()
                    .map(VideoTypeEnum::getType).collect(Collectors.toList()));
        }
        return builder.build();
    }
}