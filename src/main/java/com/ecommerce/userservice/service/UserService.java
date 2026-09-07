package com.ecommerce.userservice.service;


import com.ecommerce.userservice.dto.ProfileResponse;
import com.ecommerce.userservice.entity.User;
import com.ecommerce.userservice.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository userRepository;

    @Autowired
    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public ProfileResponse getProfileData(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new RuntimeException("User not found"));

        return ProfileResponse
                .builder()
                .email(user.getEmail())
                .roles(user.getRoles())
                .name(user.getName())
                .build();
    }

}
