package com.ecommerce.userservice.config.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The first admin (admin.bootstrap.* in application.yaml). Empty email = the bootstrap does nothing.
 *
 * @param email    the account that becomes the first admin
 * @param password used only to CREATE that account when it does not exist yet (at least 8 characters)
 * @param name     the display name of a created account
 */
@ConfigurationProperties(prefix = "admin.bootstrap")
public record AdminBootstrapProperties(String email, String password, String name) {
}
