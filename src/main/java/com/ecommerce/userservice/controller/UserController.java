package com.ecommerce.userservice.controller;


import com.ecommerce.userservice.dto.CurrentUser;
import com.ecommerce.userservice.dto.ProfileResponse;
import com.ecommerce.userservice.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/profile")
    public ResponseEntity<ProfileResponse> getProfile(@AuthenticationPrincipal CurrentUser currentUser) {
        return ResponseEntity.ok(userService.getProfileData(currentUser.getId()));
    }
}
