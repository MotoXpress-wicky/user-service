package com.ecommerce.userservice.dto;

import com.ecommerce.userservice.entity.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@AllArgsConstructor
@Data
@Builder
public class CurrentUser {
    private Long id;
    private String name;
    private String email;
    List<Role> roles;
}
