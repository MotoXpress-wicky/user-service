# user-service

Accounts and login. Port **8080**. Database: `user_db`. Design: `infrastructure/docs/mvp/05-components/user-service.md`.

This service is **domain-free**: the words order, product, part, vehicle, cart and motoXpress never appear in its code
(a test checks this). It sends **no email**: it publishes events and notification-service sends the emails.

## API

| Method | Path | Does | Who |
|---|---|---|---|
| POST | `/api/v1/user/auth/register` | New account (needs the captcha token) | everyone |
| POST | `/api/v1/user/auth/login` | Login. Sets the cookies `access_token` (15 min) and `refresh_token` (14 days) | everyone |
| POST | `/api/v1/user/auth/refresh` | New cookies. The old refresh token ends; using it twice ends every session | cookie |
| POST | `/api/v1/user/auth/logout` | Clears the cookies **and ends the refresh token on the server** | everyone |
| GET | `/api/v1/user/auth/me` | Who am I | logged in |
| POST | `/api/v1/user/auth/forgot-password` | Asks for a reset link (same answer for unknown emails) | everyone |
| GET / POST | `/api/v1/user/auth/reset-password/validate`, `/reset-password` | Check the link, set a new password | everyone |
| GET | `/api/v1/user/profile` | Name, email, roles | logged in |
| GET | `/api/v1/user/admin/users` | List accounts (`q`, `page`, `size`) | ROLE_ADMIN |
| PATCH | `/api/v1/user/admin/users/{id}` | `{"locked": true}` and/or `{"admin": true}`. You cannot change yourself | ROLE_ADMIN |
| GET | `/api/v1/user/admin/audit-log` | Who did what to which account | ROLE_ADMIN |

Every error uses the one shared JSON: `timestamp, status, code, message, path, refreshable, details`.

## First admin

Nobody can make the first admin from the admin site, so at start-up, **if no admin exists**, the account in
`ADMIN_BOOTSTRAP_EMAIL` becomes admin, or is created with `ADMIN_BOOTSTRAP_PASSWORD` if it does not exist yet. It does
nothing once an admin exists. On a server, use your own email and a long random password, and remove the password line
after the first start.

## Events (the outbox)

`user.registered`, `user.password.reset` and `user.password.changed` are saved in the table `outbox_events` in the same
transaction as the change, then sent to RabbitMQ by one background thread. If RabbitMQ is down the events wait and are
sent later. The body of a sent event is wiped, because the reset event holds the raw reset link. Decision M46.
The names of the events are settings (`events.*`).

## Settings

| Setting | Env name | Meaning |
|---|---|---|
| `spring.datasource.*` | `DB_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | Its own database (Flyway makes the tables, Hibernate only checks them) |
| `auth.token.secret` | `JWT_SECRET` | Signs the login cookie. The other services use the same value for now (M39) |
| `admin.bootstrap.*` | `ADMIN_BOOTSTRAP_EMAIL`, `_PASSWORD`, `_NAME` | The first admin |
| `captcha.*` | `TURNSTILE_SECRET_KEY` | Cloudflare Turnstile |
| `rate-limit.*`, `spring.data.redis` | `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | Tries per IP and email |
| `spring.security.oauth2.client.*` | `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | "Continue with Google" |
| `spring.rabbitmq.*` | `SPRING_RABBITMQ_HOST`, `_PORT`, `_USERNAME`, `_PASSWORD` | RabbitMQ |

## Run and test

```bash
./mvnw test        # all tests, on H2, no Docker needed
./mvnw spring-boot:run
```

Check: after the start, `POST /api/v1/user/auth/login` with the first admin answers `200` and sets two cookies.
