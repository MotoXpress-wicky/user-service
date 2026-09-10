package com.ecommerce.userservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)   // if Cloudflare adds new fields later, do not crash
public record TurnstileVerifyResponse(

        boolean success,                              // true if the token is genuine and user is human

        String hostname,                              // the domain where the widget was actually solved

        @JsonProperty("challenge_ts")                 // Incoming response is ex - "challenge_ts": "2022-02-28T15:14:30.096Z", so manually map.
        String challengeTs,                           // The date and time the challenge was solved

        @JsonProperty("error-codes")                  // JSON name has a dash, which Java cannot use
        List<String> errorCodes                       // why it failed, for your logs only
) {
}