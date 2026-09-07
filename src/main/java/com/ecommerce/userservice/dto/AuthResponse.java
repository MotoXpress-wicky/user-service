package com.ecommerce.userservice.dto;

import com.ecommerce.userservice.entity.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
@Builder
public class AuthResponse {
    private Long id;
    private String email;
    private String name;
    private List<Role> roles;


}
