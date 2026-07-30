package com.yoox.great.context.exception;


import com.yoox.great.context.enums.version.CloudSDKVersionEnum;

public class CloudSDKVersionException extends CloudSDKException {

    public CloudSDKVersionException(String thingVersion) {
        super(String.format("The current CloudSDK version(%s) does not support this thing version(%s), " +
                "please replace the corresponding CloudSDK version.)", CloudSDKVersionEnum.DEFAULT.getVersion(), thingVersion));
    }

}
