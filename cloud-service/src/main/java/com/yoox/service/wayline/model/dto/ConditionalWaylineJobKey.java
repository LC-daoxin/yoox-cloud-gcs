package com.yoox.service.wayline.model.dto;

import com.yoox.great.redis.RedisConst;
import lombok.Data;

import java.util.Objects;

@Data
public class ConditionalWaylineJobKey {

    private String workspaceId;

    private String dockSn;

    private String jobId;

    public ConditionalWaylineJobKey(String workspaceId, String dockSn, String jobId) {
        this.workspaceId = workspaceId;
        this.dockSn = dockSn;
        this.jobId = jobId;
    }

    public ConditionalWaylineJobKey(String key) {
        if (Objects.isNull(key)) {
            throw new IllegalArgumentException("Conditional wayline job key must not be null.");
        }
        String[] keyArr = key.split(RedisConst.DELIMITER, -1);
        if (keyArr.length != 3
                || keyArr[0].isBlank()
                || keyArr[1].isBlank()
                || keyArr[2].isBlank()) {
            throw new IllegalArgumentException("Invalid conditional wayline job key: " + key);
        }
        this.workspaceId = keyArr[0];
        this.dockSn = keyArr[1];
        this.jobId = keyArr[2];
    }

    public String getKey() {
        return String.join(RedisConst.DELIMITER, workspaceId, dockSn, jobId);
    }
}
