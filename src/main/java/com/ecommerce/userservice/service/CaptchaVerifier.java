package com.ecommerce.userservice.service;


public interface CaptchaVerifier {
    /**
     * While it is technically optional, passing the user's IP address to the Turnstile verification
     * endpoint drastically improves its ability to tell human users apart from automated bots.
     *
     **/
    void verify(String token, String remoteIp);
}
