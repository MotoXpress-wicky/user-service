package com.ecommerce.userservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class UserServiceApplication {

    static void main(String[] args) {
        io.github.cdimascio.dotenv.Dotenv.load();
        SpringApplication.run(UserServiceApplication.class, args);
    }

}
