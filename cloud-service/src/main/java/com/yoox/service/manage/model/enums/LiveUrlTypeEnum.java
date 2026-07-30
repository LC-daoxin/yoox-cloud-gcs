package com.yoox.service.manage.model.enums;

public enum LiveUrlTypeEnum {

    RTMP(1),

    RTSP(2),

    GB28181(3),

    UNKNOWN(-1);

    private int val;

    LiveUrlTypeEnum(int val) {
        this.val = val;
    }

    public static LiveUrlTypeEnum find(Integer val) {
        if (val == null) {
            return UNKNOWN;
        }
        if (RTMP.val == val) {
            return RTMP;
        }
        if (RTSP.val == val) {
            return RTSP;
        }
        if (GB28181.val == val) {
            return GB28181;
        }
        return UNKNOWN;
    }
}
