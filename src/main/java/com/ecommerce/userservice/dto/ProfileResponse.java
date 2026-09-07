package com.ecommerce.userservice.dto;


import com.ecommerce.userservice.entity.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
public class ProfileResponse {
    private String name;
    private String email;
    private List<Role> roles;
}
