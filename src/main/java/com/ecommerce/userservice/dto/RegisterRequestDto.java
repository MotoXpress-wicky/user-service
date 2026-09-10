package com.ecommerce.userservice.dto;


import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Why didn't put not blank on captchaToken?
 * <p>
 * We can put it, but the problem arise in developments,when we are testing using postman (or unit,integration tests.)
 * Currently we use captcha.enable=false in application.properties, to stop the captcha from being used. In service fles
 * but if we put @NotBlank on captchaToken, we need to send some random value to the captchaToken field value every time.
 * we do postman api testing, It's a bit headache.
 *
 **/
@Data
@NoArgsConstructor
public class RegisterRequestDto {
    @NotBlank(message = "Name is required")
    @Size(min = 3, message = "Name must be at least 3 characters")
    private String name;
    @NotBlank(message = "Email is required")
    @Email(message = "Enter a valid email")
    private String email;
    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    private String captchaToken;

}
