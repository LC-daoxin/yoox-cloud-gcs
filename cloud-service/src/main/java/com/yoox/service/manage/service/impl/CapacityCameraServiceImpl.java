package com.yoox.service.manage.service.impl;

import com.yoox.great.context.enums.device.DeviceDomainEnum;
import com.yoox.great.mqtt.model.device.PayloadIndex;
import com.yoox.great.redis.RedisConst;
import com.yoox.great.redis.RedisOpsUtils;
import com.yoox.service.manage.model.dto.CapacityCameraDTO;
import com.yoox.service.manage.model.dto.DeviceDictionaryDTO;
import com.yoox.service.manage.model.receiver.CapacityCameraReceiver;
import com.yoox.service.manage.service.ICameraVideoService;
import com.yoox.service.manage.service.ICapacityCameraService;
import com.yoox.service.manage.service.IDeviceDictionaryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CapacityCameraServiceImpl implements ICapacityCameraService {

    @Autowired
    private ICameraVideoService cameraVideoService;

    @Autowired
    private IDeviceDictionaryService dictionaryService;

    @Override
    public List<CapacityCameraDTO> getCapacityCameraByDeviceSn(String deviceSn) {
        if (!StringUtils.hasText(deviceSn)) {
            return Collections.emptyList();
        }
        return (List<CapacityCameraDTO>) RedisOpsUtils.hashGet(RedisConst.LIVE_CAPACITY, deviceSn.trim());
    }

    @Override
    public Boolean deleteCapacityCameraByDeviceSn(String deviceSn) {
        if (!StringUtils.hasText(deviceSn)) {
            return false;
        }
        return RedisOpsUtils.hashDel(RedisConst.LIVE_CAPACITY, new String[]{deviceSn.trim()});
    }

    @Override
    public void saveCapacityCameraReceiverList(List<CapacityCameraReceiver> capacityCameraReceivers, String deviceSn) {
        if (!StringUtils.hasText(deviceSn) || capacityCameraReceivers == null) {
            return;
        }
        List<CapacityCameraDTO> capacity = capacityCameraReceivers.stream()
                .filter(Objects::nonNull)
                .map(this::receiver2Dto).collect(Collectors.toList());
        RedisOpsUtils.hashSet(RedisConst.LIVE_CAPACITY, deviceSn.trim(), capacity);
    }

    public CapacityCameraDTO receiver2Dto(CapacityCameraReceiver receiver) {
        CapacityCameraDTO.CapacityCameraDTOBuilder builder = CapacityCameraDTO.builder();
        if (receiver == null) {
            return builder.build();
        }
        PayloadIndex cameraIndex = receiver.getCameraIndex();
        Optional<DeviceDictionaryDTO> dictionaryOpt = dictionaryService.getOneDictionaryInfoByTypeSubType(
                DeviceDomainEnum.PAYLOAD.getDomain(), cameraIndex.getType().getType(), cameraIndex.getSubType().getSubType());
        dictionaryOpt.ifPresent(dictionary -> builder.name(dictionary.getDeviceName()));

        return builder
                .id(UUID.randomUUID().toString())
                .videosList(receiver.getVideoList()
                        .stream()
                        .map(cameraVideoService::receiver2Dto)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList()))
                .index(receiver.getCameraIndex().toString())
                .build();
    }
}
