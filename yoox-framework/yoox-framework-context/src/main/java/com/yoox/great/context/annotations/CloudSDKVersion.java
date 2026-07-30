package com.yoox.great.context.annotations;


import com.yoox.great.context.enums.version.CloudSDKVersionEnum;
import com.yoox.great.context.enums.version.GatewayTypeEnum;

import java.lang.annotation.*;


@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface CloudSDKVersion {

    CloudSDKVersionEnum since() default CloudSDKVersionEnum.V0_0_1;

    CloudSDKVersionEnum deprecated() default CloudSDKVersionEnum.V99;

    GatewayTypeEnum[] include() default {};

    GatewayTypeEnum[] exclude() default {};

}
