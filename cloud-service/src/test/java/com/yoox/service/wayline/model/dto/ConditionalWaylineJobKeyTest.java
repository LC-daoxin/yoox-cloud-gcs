package com.yoox.service.wayline.model.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConditionalWaylineJobKeyTest {

    @Test
    void parsesValidRedisMember() {
        ConditionalWaylineJobKey key = new ConditionalWaylineJobKey("workspace:gateway:job");

        assertEquals("workspace", key.getWorkspaceId());
        assertEquals("gateway", key.getDockSn());
        assertEquals("job", key.getJobId());
        assertEquals("workspace:gateway:job", key.getKey());
    }

    @Test
    void rejectsNullOrMalformedRedisMember() {
        assertThrows(IllegalArgumentException.class, () -> new ConditionalWaylineJobKey((String) null));
        assertThrows(IllegalArgumentException.class, () -> new ConditionalWaylineJobKey("workspace:gateway"));
        assertThrows(IllegalArgumentException.class, () -> new ConditionalWaylineJobKey("workspace::job"));
        assertThrows(IllegalArgumentException.class, () -> new ConditionalWaylineJobKey("workspace:gateway:job:extra"));
    }
}
