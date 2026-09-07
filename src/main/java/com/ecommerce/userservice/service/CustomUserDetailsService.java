package com.ecommerce.userservice.service;

import com.ecommerce.userservice.entity.User;
import com.ecommerce.userservice.exception.UserEmailNotFoundException;
import com.ecommerce.userservice.repository.UserRepository;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;


@Service
public class CustomUserDetailsService implements UserDetailsService {
    private final UserRepository userRepository;

    @Autowired
    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }


    // Query the user table and grap hashed password, email, roles and put it inside userdetails.User Object
    // Here we are using the email as the username.

    @Override
    @NullMarked
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {

        User user = userRepository.findByEmail(username)
                .orElseThrow(() -> new UsernameNotFoundException("No account for email: " + username));


        /**
         * Things acceped by UserDetailsClass.This object is used by the Authentication manager
         *
         *        new org.springframework.security.core.userdetails.User(
         *                 username,
         *                 password,
         *                 enabled,
         *                 accountNonExpired,
         *                 credentialsNonExpired,
         *                 accountNonLocked,
         *                 authorities
         *         );
         *
         * */


        return new org.springframework.security.core.userdetails.User(
                user.getEmail(),
                user.getPassword(),
                true,               // enabled
                true,               // accountNonExpired
                true,               // credentialsNonExpired
                !user.isLocked(),   // accountNonLocked
                user.getRoles().stream().map(r -> new SimpleGrantedAuthority(r.name())).toList()
        );


    }
}
