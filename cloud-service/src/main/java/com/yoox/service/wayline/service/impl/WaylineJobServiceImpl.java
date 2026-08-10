package com.yoox.service.wayline.service.impl;

import com.yoox.great.context.page.Pagination;
import com.yoox.great.context.page.PaginationData;
import com.yoox.great.context.enums.device.DeviceDomainEnum;
import com.yoox.great.mqtt.enums.device.DockModeCodeEnum;
import com.yoox.great.mqtt.enums.device.DroneModeCodeEnum;
import com.yoox.great.mqtt.enums.wayline.OutOfControlActionEnum;
import com.yoox.great.mqtt.enums.wayline.TaskTypeEnum;
import com.yoox.great.mqtt.enums.wayline.WaylineTypeEnum;
import com.yoox.great.mqtt.core.EventsReceiver;
import com.yoox.great.mqtt.model.device.OsdDock;
import com.yoox.great.mqtt.model.device.OsdDockDrone;
import com.yoox.great.mqtt.model.device.OsdRcDrone;
import com.yoox.great.mqtt.model.wayline.FlighttaskProgress;
import com.yoox.great.mqtt.model.wayline.FlighttaskProgressData;
import com.yoox.great.mqtt.model.wayline.GetWaylineListResponse;
import com.yoox.great.redis.RedisConst;
import com.yoox.great.redis.RedisOpsUtils;
import com.yoox.service.manage.model.dto.DeviceDTO;
import com.yoox.service.manage.service.IDeviceRedisService;
import com.yoox.service.manage.service.IDeviceService;
import com.yoox.service.media.model.MediaFileCountDTO;
import com.yoox.service.media.service.IFileService;
import com.yoox.service.wayline.dao.IWaylineJobMapper;
import com.yoox.service.wayline.model.dto.WaylineJobDTO;
import com.yoox.service.wayline.model.entity.WaylineJobEntity;
import com.yoox.service.wayline.model.enums.WaylineJobStatusEnum;
import com.yoox.service.wayline.model.param.CreateJobParam;
import com.yoox.service.wayline.service.IWaylineFileService;
import com.yoox.service.wayline.service.IWaylineJobService;
import com.yoox.service.wayline.service.IWaylineRedisService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
@Slf4j
public class WaylineJobServiceImpl implements IWaylineJobService {

    @Autowired
    private IWaylineJobMapper mapper;

    @Autowired
    private IWaylineFileService waylineFileService;

    @Autowired
    private IDeviceService deviceService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IFileService fileService;

    @Autowired
    private IDeviceRedisService deviceRedisService;

    @Autowired
    private IWaylineRedisService waylineRedisService;

    private Optional<WaylineJobDTO> insertWaylineJob(WaylineJobEntity jobEntity) {
        int id = mapper.insert(jobEntity);
        if (id <= 0) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.entity2Dto(jobEntity));
    }

    @Override
    public Optional<WaylineJobDTO> createWaylineJob(CreateJobParam param, String workspaceId, String username, Long beginTime, Long endTime) {
        if (Objects.isNull(param)) {
            return Optional.empty();
        }
        // Immediate tasks, allocating time on the backend.
        WaylineJobEntity jobEntity = WaylineJobEntity.builder()
                .name(param.getName())
                .dockSn(param.getDockSn())
                .fileId(param.getFileId())
                .username(username)
                .workspaceId(workspaceId)
                .jobId(UUID.randomUUID().toString())
                .beginTime(beginTime)
                .endTime(endTime)
                .status(WaylineJobStatusEnum.PENDING.getVal())
                .taskType(param.getTaskType().getType())
                .waylineType(param.getWaylineType().getValue())
                .outOfControlAction(param.getOutOfControlAction().getAction())
                .rthAltitude(param.getRthAltitude())
                .mediaCount(0)
                .build();

        return insertWaylineJob(jobEntity);
    }

    @Override
    public Optional<WaylineJobDTO> createWaylineJobByParent(String workspaceId, String parentId) {
        Optional<WaylineJobDTO> parentJobOpt = this.getJobByJobId(workspaceId, parentId);
        if (parentJobOpt.isEmpty()) {
            return Optional.empty();
        }
        WaylineJobEntity jobEntity = this.dto2Entity(parentJobOpt.get());
        jobEntity.setJobId(UUID.randomUUID().toString());
        jobEntity.setErrorCode(null);
        jobEntity.setCompletedTime(null);
        jobEntity.setExecuteTime(null);
        jobEntity.setStatus(WaylineJobStatusEnum.PENDING.getVal());
        jobEntity.setParentId(parentId);

        return this.insertWaylineJob(jobEntity);
    }

    public List<WaylineJobDTO> getJobsByConditions(String workspaceId, Collection<String> jobIds, WaylineJobStatusEnum status) {
        return mapper.selectList(
                        new LambdaQueryWrapper<WaylineJobEntity>()
                                .eq(WaylineJobEntity::getWorkspaceId, workspaceId)
                                .eq(Objects.nonNull(status), WaylineJobEntity::getStatus, status.getVal())
                                .and(!CollectionUtils.isEmpty(jobIds),
                                        wrapper -> jobIds.forEach(id -> wrapper.eq(WaylineJobEntity::getJobId, id).or())))
                .stream()
                .map(this::entity2Dto)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<WaylineJobDTO> getJobByJobId(String workspaceId, String jobId) {
        WaylineJobEntity jobEntity = mapper.selectOne(
                new LambdaQueryWrapper<WaylineJobEntity>()
                        .eq(WaylineJobEntity::getWorkspaceId, workspaceId)
                        .eq(WaylineJobEntity::getJobId, jobId));
        return Optional.ofNullable(entity2Dto(jobEntity));
    }

    @Override
    public Boolean updateJob(WaylineJobDTO dto) {
        return mapper.update(this.dto2Entity(dto),
                new LambdaUpdateWrapper<WaylineJobEntity>()
                        .eq(WaylineJobEntity::getJobId, dto.getJobId())) > 0;
    }

    @Override
    public Boolean updateJobIfNotEnded(WaylineJobDTO dto) {
        return mapper.update(this.dto2Entity(dto),
                new LambdaUpdateWrapper<WaylineJobEntity>()
                        .eq(WaylineJobEntity::getJobId, dto.getJobId())
                        .notIn(WaylineJobEntity::getStatus,
                                WaylineJobStatusEnum.SUCCESS.getVal(),
                                WaylineJobStatusEnum.CANCEL.getVal(),
                                WaylineJobStatusEnum.FAILED.getVal())) > 0;
    }

    @Override
    public int cancelJobsIfNotEnded(String workspaceId, Collection<String> jobIds) {
        if (!StringUtils.hasText(workspaceId) || CollectionUtils.isEmpty(jobIds)) {
            return 0;
        }
        WaylineJobEntity update = this.dto2Entity(WaylineJobDTO.builder()
                .status(WaylineJobStatusEnum.CANCEL.getVal())
                .completedTime(LocalDateTime.now())
                .build());
        return mapper.update(update,
                new LambdaUpdateWrapper<WaylineJobEntity>()
                        .eq(WaylineJobEntity::getWorkspaceId, workspaceId)
                        .in(WaylineJobEntity::getJobId, jobIds)
                        .notIn(WaylineJobEntity::getStatus,
                                WaylineJobStatusEnum.SUCCESS.getVal(),
                                WaylineJobStatusEnum.CANCEL.getVal(),
                                WaylineJobStatusEnum.FAILED.getVal()));
    }

    @Override
    public PaginationData<WaylineJobDTO> getJobsByWorkspaceId(String workspaceId, long page, long pageSize) {
        Page<WaylineJobEntity> pageData = mapper.selectPage(
                new Page<WaylineJobEntity>(page, pageSize),
                new LambdaQueryWrapper<WaylineJobEntity>()
                        .eq(WaylineJobEntity::getWorkspaceId, workspaceId)
                        .orderByDesc(WaylineJobEntity::getId));
        List<WaylineJobDTO> records = pageData.getRecords()
                .stream()
                .map(this::entity2Dto)
                .collect(Collectors.toList());

        return new PaginationData<WaylineJobDTO>(records, new Pagination(pageData.getCurrent(), pageData.getSize(), pageData.getTotal()));
    }

    private WaylineJobEntity dto2Entity(WaylineJobDTO dto) {
        WaylineJobEntity.WaylineJobEntityBuilder builder = WaylineJobEntity.builder();
        if (dto == null) {
            return builder.build();
        }
        if (Objects.nonNull(dto.getBeginTime())) {
            builder.beginTime(dto.getBeginTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        }
        if (Objects.nonNull(dto.getEndTime())) {
            builder.endTime(dto.getEndTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        }
        if (Objects.nonNull(dto.getExecuteTime())) {
            builder.executeTime(dto.getExecuteTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        }
        if (Objects.nonNull(dto.getCompletedTime())) {
            builder.completedTime(dto.getCompletedTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        }
        return builder.status(dto.getStatus())
                .mediaCount(dto.getMediaCount())
                .name(dto.getJobName())
                .errorCode(dto.getCode())
                .jobId(dto.getJobId())
                .fileId(dto.getFileId())
                .dockSn(dto.getDockSn())
                .workspaceId(dto.getWorkspaceId())
                .taskType(Optional.ofNullable(dto.getTaskType()).map(TaskTypeEnum::getType).orElse(null))
                .waylineType(Optional.ofNullable(dto.getWaylineType()).map(WaylineTypeEnum::getValue).orElse(null))
                .username(dto.getUsername())
                .rthAltitude(dto.getRthAltitude())
                .outOfControlAction(Optional.ofNullable(dto.getOutOfControlAction())
                        .map(OutOfControlActionEnum::getAction).orElse(null))
                .parentId(dto.getParentId())
                .build();
    }

    public WaylineJobStatusEnum getWaylineState(String dockSn) {
        Optional<DeviceDTO> dockOpt = deviceRedisService.getDeviceOnline(dockSn);
        if (dockOpt.isEmpty() || !StringUtils.hasText(dockOpt.get().getChildDeviceSn())) {
            return WaylineJobStatusEnum.UNKNOWN;
        }
        DeviceDTO gateway = dockOpt.get();
        DroneModeCodeEnum modeCode;
        if (DeviceDomainEnum.REMOTER_CONTROL == gateway.getDomain()) {
            Optional<OsdRcDrone> deviceOsdOpt = deviceRedisService.getDeviceOsd(
                    gateway.getChildDeviceSn(), OsdRcDrone.class);
            if (deviceOsdOpt.isEmpty()) {
                return WaylineJobStatusEnum.UNKNOWN;
            }
            modeCode = deviceOsdOpt.get().getModeCode();
        } else if (DeviceDomainEnum.DOCK == gateway.getDomain()) {
            Optional<OsdDock> dockOsdOpt = deviceRedisService.getDeviceOsd(dockSn, OsdDock.class);
            Optional<OsdDockDrone> deviceOsdOpt = deviceRedisService.getDeviceOsd(
                    gateway.getChildDeviceSn(), OsdDockDrone.class);
            if (dockOsdOpt.isEmpty() || deviceOsdOpt.isEmpty()
                    || DockModeCodeEnum.WORKING != dockOsdOpt.get().getModeCode()) {
                return WaylineJobStatusEnum.UNKNOWN;
            }
            modeCode = deviceOsdOpt.get().getModeCode();
        } else {
            return WaylineJobStatusEnum.UNKNOWN;
        }

        if (DroneModeCodeEnum.WAYLINE == modeCode
                || DroneModeCodeEnum.KML_ROUTE_MODE == modeCode
                || DroneModeCodeEnum.MANUAL == modeCode
                || DroneModeCodeEnum.TAKEOFF_AUTO == modeCode) {
            if (StringUtils.hasText(waylineRedisService.getPausedWaylineJobId(dockSn))) {
                return WaylineJobStatusEnum.PAUSED;
            }
            if (waylineRedisService.getRunningWaylineJob(dockSn).isPresent()) {
                return WaylineJobStatusEnum.IN_PROGRESS;
            }
        }
        return WaylineJobStatusEnum.UNKNOWN;
    }

    private WaylineJobDTO entity2Dto(WaylineJobEntity entity) {
        if (entity == null) {
            return null;
        }

        WaylineJobDTO.WaylineJobDTOBuilder builder = WaylineJobDTO.builder()
                .jobId(entity.getJobId())
                .jobName(entity.getName())
                .fileId(entity.getFileId())
                .fileName(waylineFileService.getWaylineByWaylineId(entity.getWorkspaceId(), entity.getFileId())
                        .orElse(new GetWaylineListResponse()).getName())
                .dockSn(entity.getDockSn())
                .dockName(deviceService.getDeviceBySn(entity.getDockSn())
                        .orElse(DeviceDTO.builder().build()).getNickname())
                .username(entity.getUsername())
                .workspaceId(entity.getWorkspaceId())
                .status(WaylineJobStatusEnum.IN_PROGRESS.getVal() == entity.getStatus() &&
                        entity.getJobId().equals(waylineRedisService.getPausedWaylineJobId(entity.getDockSn())) ?
                        WaylineJobStatusEnum.PAUSED.getVal() : entity.getStatus())
                .code(entity.getErrorCode())
                .beginTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(entity.getBeginTime()), ZoneId.systemDefault()))
                .endTime(Objects.nonNull(entity.getEndTime()) ?
                        LocalDateTime.ofInstant(Instant.ofEpochMilli(entity.getEndTime()), ZoneId.systemDefault()) : null)
                .executeTime(Objects.nonNull(entity.getExecuteTime()) ?
                        LocalDateTime.ofInstant(Instant.ofEpochMilli(entity.getExecuteTime()), ZoneId.systemDefault()) : null)
                .completedTime(WaylineJobStatusEnum.find(entity.getStatus()).getEnd() ?
                        LocalDateTime.ofInstant(Instant.ofEpochMilli(entity.getUpdateTime()), ZoneId.systemDefault()) : null)
                .taskType(TaskTypeEnum.find(entity.getTaskType()))
                .waylineType(WaylineTypeEnum.find(entity.getWaylineType()))
                .rthAltitude(entity.getRthAltitude())
                .outOfControlAction(OutOfControlActionEnum.find(entity.getOutOfControlAction()))
                .mediaCount(entity.getMediaCount());

        if (Objects.nonNull(entity.getEndTime())) {
            builder.endTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(entity.getEndTime()), ZoneId.systemDefault()));
        }
        if (WaylineJobStatusEnum.IN_PROGRESS.getVal() == entity.getStatus()) {
            builder.progress(waylineRedisService.getRunningWaylineJob(entity.getDockSn())
                    .map(EventsReceiver::getOutput)
                    .map(FlighttaskProgress::getProgress)
                    .map(FlighttaskProgressData::getPercent)
                    .orElse(null));
        }

        if (entity.getMediaCount() == 0) {
            return builder.build();
        }

        String key = RedisConst.MEDIA_HIGHEST_PRIORITY_PREFIX + entity.getDockSn();
        String countKey = RedisConst.MEDIA_FILE_PREFIX + entity.getDockSn();
        Object mediaFileCount = RedisOpsUtils.hashGet(countKey, entity.getJobId());
        if (Objects.nonNull(mediaFileCount)) {
            builder.uploadedCount(((MediaFileCountDTO) mediaFileCount).getUploadedCount())
                    .uploading(RedisOpsUtils.checkExist(key) && entity.getJobId().equals(((MediaFileCountDTO) RedisOpsUtils.get(key)).getJobId()));
            return builder.build();
        }

        int uploadedSize = fileService.getFilesByWorkspaceAndJobId(entity.getWorkspaceId(), entity.getJobId()).size();
        if (uploadedSize >= entity.getMediaCount()) {
            return builder.uploadedCount(uploadedSize).build();
        }
        RedisOpsUtils.hashSet(countKey, entity.getJobId(),
                MediaFileCountDTO.builder()
                        .jobId(entity.getJobId())
                        .mediaCount(entity.getMediaCount())
                        .uploadedCount(uploadedSize).build());
        return builder.build();
    }
}
