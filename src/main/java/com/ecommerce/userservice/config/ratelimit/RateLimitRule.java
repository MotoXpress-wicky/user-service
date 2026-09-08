package com.ecommerce.userservice.config.ratelimit;

/**
 * Every rate limit in the application, in one place.
 * <p>
 * Three things are baked into the rule itself because they are decisions about
 * WHAT is being protected, not about tuning. The numbers (capacity and period)
 * live in application.yaml instead, because those you will change after reading
 * your logs.
 * <p>
 * The naming convention is deliberate: the second half of the name is the
 * DISCRIMINATOR - what the bucket is counted per. LOGIN_EMAIL counts per email
 * address, LOGIN_IP counts per IP. Getting this wrong is the classic rate
 * limiting mistake, so the name states it out loud.
 */
public enum RateLimitRule {

    /*
     * The blanket safety net. Deliberately LOCAL: a slightly different count on
     * each instance does not matter for a limit this loose, and keeping it out
     * of Redis saves a network round trip on literally every request.
     */
    GLOBAL_IP("global:ip", Scope.LOCAL, FailurePolicy.FAIL_OPEN),

    /*
     * Brute force protection. The attacker picks one account and tries many
     * passwords. He can change his IP cheaply; he cannot change the email he is
     * attacking, so the email is what we count.
     *
     * FAIL_CLOSED: if Redis is down we would rather reject logins than leave
     * brute force protection switched off. This is the one limit worth an
     * outage.
     */
    LOGIN_EMAIL("login:email", Scope.SHARED, FailurePolicy.FAIL_CLOSED),

    /*
     * Credential stuffing protection. A leaked email/password list tried one
     * pair at a time never triggers LOGIN_EMAIL, because each email is only
     * attempted once. But the volume comes from few machines, so IP catches it.
     */
    LOGIN_IP("login:ip", Scope.SHARED, FailurePolicy.FAIL_CLOSED),

    /* Bot signups, and it slows down email enumeration via the 409 response. */
    REGISTER_IP("register:ip", Scope.SHARED, FailurePolicy.FAIL_CLOSED),

    /*
     * Stops email bombing: someone typing a victim's address into the form
     * hundreds of times so the victim's inbox fills with reset links.
     *
     * This is NOT the same as the cooldown already inside PasswordResetService.
     * That cooldown only runs AFTER findByEmail succeeds, so it does nothing
     * against a flood of addresses that do not exist. This rule runs first.
     */
    FORGOT_EMAIL("forgot:email", Scope.SHARED, FailurePolicy.FAIL_CLOSED),

    /* The fake-address flood the per-user cooldown cannot see. */
    FORGOT_IP("forgot:ip", Scope.SHARED, FailurePolicy.FAIL_CLOSED),

    /*
     * Protects CPU, not the token. A 256-bit token is not guessable, but every
     * submission costs a database lookup plus a BCrypt hash (~80ms of CPU).
     */
    RESET_IP("reset:ip", Scope.SHARED, FailurePolicy.FAIL_CLOSED),

    /* Same, and it is a GET, so it is trivially easy to spam from a browser. */
    VALIDATE_IP("validate:ip", Scope.SHARED, FailurePolicy.FAIL_CLOSED),

    /*
     * Every call writes twice to refresh_tokens (revoke old, insert new).
     *
     * FAIL_OPEN on purpose: if Redis is down, failing this closed would sign
     * out every active user across the whole site. The damage from an outage
     * is far worse than the damage from an unlimited refresh endpoint.
     */
    REFRESH_IP("refresh:ip", Scope.SHARED, FailurePolicy.FAIL_OPEN);

    /** Where the token counts are stored. */
    public enum Scope {
        /** In this JVM only. Fast, no network, but each instance counts separately. */
        LOCAL,
        /** In Redis. All instances share one count. */
        SHARED
    }

    /** What to do when the store cannot be reached. */
    public enum FailurePolicy {
        /** Let the request through and log loudly. Availability wins. */
        FAIL_OPEN,
        /** Reject the request. Security wins. */
        FAIL_CLOSED
    }

    private final String keyPrefix;
    private final Scope scope;
    private final FailurePolicy failurePolicy;

    RateLimitRule(String keyPrefix, Scope scope, FailurePolicy failurePolicy) {
        this.keyPrefix = keyPrefix;
        this.scope = scope;
        this.failurePolicy = failurePolicy;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public Scope getScope() {
        return scope;
    }

    public boolean isFailOpen() {
        return failurePolicy == FailurePolicy.FAIL_OPEN;
    }
}
