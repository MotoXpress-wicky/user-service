package com.ecommerce.userservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class UserServiceApplication {

    static void main(String[] args) {
        // Local development: read a .env file in the working directory, if there is one, before Spring starts.
        // In Docker there is no .env file and the settings come from the environment, so a missing file is fine.
        io.github.cdimascio.dotenv.Dotenv.configure().ignoreIfMissing().systemProperties().load();
        SpringApplication.run(UserServiceApplication.class, args);
    }

}
