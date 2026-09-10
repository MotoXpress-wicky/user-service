package com.ecommerce.userservice.config.captcha;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "captcha")
@Getter
@Setter
public class TurnstileProperties {

    private boolean enabled;
    private String provider;
    private String secretKey;
    private String verifyUrl;
    private List<String> allowedHostnames;
}