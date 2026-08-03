package com.yoox.service.control.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Server-owned metadata for one DRC lease.
 *
 * <p>The active gateway key stores only {@link #generation}. Keeping the
 * generation separate from the metadata lets Redis compare-and-delete the
 * lease without an old request deleting a newer session.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DrcSession {

    private String gatewaySn;

    private String workspaceId;

    private String userId;

    private String browserClientId;

    private String deviceClientId;

    /**
     * Serial number used by the browser/device DRC data topics. For remote
     * controllers this is the connected aircraft SN; for docks it is the
     * gateway SN. Persisting it prevents an active lease from being reused
     * after a gateway changes aircraft.
     */
    private String controlTopicSn;

    private String generation;

    private String pausedJobId;

    private long createdAt;

    /**
     * Timestamp from the device-side services_reply that established this
     * generation. DRC status events are only allowed to clear a generation
     * when they carry a timestamp from the same device clock at or after this
     * watermark.
     */
    private Long deviceTimestampWatermark;
}
