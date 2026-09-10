package com.ecommerce.userservice.service;

import com.ecommerce.userservice.config.captcha.TurnstileProperties;
import com.ecommerce.userservice.dto.TurnstileVerifyResponse;
import com.ecommerce.userservice.exception.InvalidCaptchaException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;


@Service
@Slf4j
public class TurnstileVerifier implements CaptchaVerifier {

    private static final String GENERIC_MESSAGE = "Captcha verification failed. Please try again.";


    private final TurnstileProperties properties;
    private final RestClient restClient;

    @Autowired
    public TurnstileVerifier(TurnstileProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClient = restClientBuilder.build();// Spring Boot pre-configures this builder for you
    }

    @Override
    public void verify(String token, String remoteIp) {

        if (!properties.isEnabled()) {              // switched off in tests and maybe in the dev profile
            return;                                 // do nothing, let the request continue
        }

        if (!StringUtils.hasText(token)) {          // null, empty check
            log.warn("Request arrived with no captcha token");
            throw new InvalidCaptchaException(GENERIC_MESSAGE);
        }

        /**
         * Turnstile accept both json and url encoded formats, but recaptcha v2,v3 only accepts url encoded format
         * Sice we are building this service in a way we can swap providers (turnstile,google captcha)
         * easily, I'm  using url encoded format
         * **/
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", properties.getSecretKey());
        form.add("response", token);                     // the token the browser produced
        if (StringUtils.hasText(remoteIp)) {
            form.add("remoteip", remoteIp);              // optional, but when we provide it, Trunstile give better results
        }

        TurnstileVerifyResponse reply;
        try {
            reply = restClient.post()
                    .uri(properties.getVerifyUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TurnstileVerifyResponse.class);
        } catch (Exception exception) {
            log.error("Could not reach the captcha service", exception);
            throw new InvalidCaptchaException(GENERIC_MESSAGE);
        }

        if (reply == null || !reply.success()) {
            log.warn("Captcha rejected. Reasons: {}",
                    reply == null ? "empty response" : reply.errorCodes());
            throw new InvalidCaptchaException(GENERIC_MESSAGE);
        }

        if (!properties.getAllowedHostnames().contains(reply.hostname())) {
            // the token was genuine, but solved on a page that is not yours - the stolen site key attack
            log.warn("Captcha token came from an unexpected hostname: {}", reply.hostname());
            throw new InvalidCaptchaException(GENERIC_MESSAGE);
        }
    }
}