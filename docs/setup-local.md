# Local development setup

This guide takes you from a fresh clone to a running backend that the Android app can call.

## 1. Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 25 | The Gradle toolchain picks up JDK 25 (IntelliJ: *File → Project Structure → SDK*). |
| Docker Desktop | 4.x or later | Must be **running** before you start the app or run tests. |
| Git | any | |
| IntelliJ IDEA | recommended | Enable *Annotation Processing* for Lombok. |

You do not need to install PostgreSQL, Redis or MinIO. They all run in Docker.

## 2. Environment variables

```sh
cp .env.example .env
```

Both Docker Compose (`compose.yaml`) and Spring Boot (`spring.config.import`) read `.env`, so you configure everything in one place. `.env` is gitignored, so **never commit it**. When you add a new variable, add it to `.env.example` too.

Key variables:

| Variable | Default | Purpose |
|---|---|---|
| `POSTGRES_PORT` | `5433` | Host port of the PostGIS container. It is not `5432`, to avoid clashing with a PostgreSQL installed directly on the machine. |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `nearby` | Database credentials inside the container. |
| `REDIS_PORT` | `6379` | Redis (cache, rate limiting, idempotency). |
| `JWT_SECRET` | dev value | HS256 signing key, at least 32 bytes. Generate one with `openssl rand -base64 48`. |
| `MINIO_ENDPOINT` | `http://localhost:9000` | Address the backend uses to reach MinIO. |
| `MINIO_PUBLIC_ENDPOINT` | `http://10.0.2.2:9000` | Host the Android app uses for presigned image URLs (see section 6). |
| `SERVER_PORT` | `8080` | API port. |
| `SMS_PROVIDER` | `log` | `log` writes the one-time code to the backend log (search for `[sms disabled]`); `esms` sends it through eSMS.vn (section 2.1). |
| `ESMS_API_KEY` / `ESMS_SECRET_KEY` / `ESMS_BRANDNAME` | empty | eSMS credentials and the registered brandname. Required when `SMS_PROVIDER=esms`; the app refuses to start without them. |
| `ESMS_SANDBOX` | `true` | `true`: eSMS checks the request but neither delivers nor charges. Set `false` to send real SMS. |
| `SMS_OTP_TEMPLATE` | Vietnamese text with `{code}` | SMS body. Must match the template registered with eSMS character for character. |
| `OTP_HASH_SECRET` | dev value | HMAC key for code hashes stored in Redis, at least 32 bytes. |
| `MAIL_ENABLED` | `false` | When `false`, verification and password reset links are written to the backend log (search for `[mail disabled]`) instead of being emailed. |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` / `MAIL_PASSWORD` | Gmail / `587` / empty | SMTP server and login (section 2.2). |
| `MAIL_SMTP_AUTH` / `MAIL_STARTTLS` | `true` | Required by Gmail and most providers. |
| `MAIL_FROM` | `Nearby <no-reply@nearby.local>` | Sender. With Gmail use the same address as `MAIL_USERNAME`. |
| `EMAIL_VERIFICATION_URL` / `PASSWORD_RESET_URL` | `nearby://...` | Links in account emails. The token is appended as `?token=...`. |
| `ADMIN_EMAIL` / `ADMIN_PASSWORD` | `admin@nearby.local` / `Nearby2026` | First admin account, created on startup if no account uses the email yet. |
| `WS_RELAY_ENABLED` | `true` | `true`: chat goes through the RabbitMQ STOMP relay, so two backend instances can reach each other's users. `false`: an in-memory broker, correct for one instance only (section 9.1). |
| `RABBITMQ_HOST` / `RABBITMQ_STOMP_PORT` / `RABBITMQ_USER` / `RABBITMQ_PASSWORD` / `RABBITMQ_VHOST` | `localhost` / `61613` / `nearby` / `nearby-secret` / `/` | Where the backend finds the STOMP port of RabbitMQ. |
| `RABBITMQ_AMQP_PORT` / `RABBITMQ_MANAGEMENT_PORT` | `5672` / `15672` | Host ports of the RabbitMQ container (management UI at `http://localhost:15672`). |
| `FCM_CREDENTIALS` | empty | Path of the Firebase service account JSON file. Empty: pushes are only logged and marked `SKIPPED` (section 9.2). |
| `FCM_BASE_URL` / `FCM_TIMEOUT` | `https://fcm.googleapis.com` / `5s` | FCM host (changed only by tests) and the longest one push may take. |

OTP limits (`otp.ttl_s`, `otp.max_attempts`, `otp.resend_s`, `otp.max_per_day`) and policy versions (`consent.terms_version`, `consent.privacy_version`) are runtime parameters in the `app_parameters` table, seeded by Flyway, not environment variables.

Real OS environment variables, and IntelliJ *Run Configuration* variables, always override values in `.env`.

### 2.1 Real SMS codes with eSMS.vn

Vietnamese carriers only deliver application SMS from a **registered brandname**, so every provider needs one.

1. Create an account at https://account.esms.vn and open **Quản lý API** to copy the `ApiKey` and `SecretKey`.
2. Get a brandname: register your own (for example `NEARBY`, needs legal documents and takes days to weeks), or ask eSMS support for their shared test brandname for developers.
3. Register a **customer care (CSKH) template** for that brandname, for example `{code} la ma xac minh Nearby cua ban, het han sau 5 phut. Khong chia se ma nay.` Put the same text in `SMS_OTP_TEMPLATE`. A mismatch makes eSMS answer code `146`.
4. Top up the account balance.
5. Set `SMS_PROVIDER=esms`, the three credentials and keep `ESMS_SANDBOX=true`. Call `POST /api/v1/auth/register/otp`; the log line `SMS to *********678 accepted by eSMS` means the key, brandname and template are right.
6. Set `ESMS_SANDBOX=false` and send to your own phone.

Common eSMS codes: `100` accepted, `101` wrong key, `104` brandname not found, `146` template mismatch. Any failure is answered `503 SMS_UNAVAILABLE` and does not use up the daily quota.

### 2.2 Real emails with Gmail

1. Turn on **2-Step Verification** for the Google account that sends the mail.
2. Open https://myaccount.google.com/apppasswords, create an app password named `Nearby backend` and copy the 16 characters.
3. In `.env`: `MAIL_ENABLED=true`, `MAIL_HOST=smtp.gmail.com`, `MAIL_PORT=587`, `MAIL_USERNAME=<address>@gmail.com`, `MAIL_PASSWORD=<app password>`, `MAIL_SMTP_AUTH=true`, `MAIL_STARTTLS=true`, `MAIL_FROM=Nearby <<address>@gmail.com>`.
4. Gmail allows about 500 emails per day; the backend also limits each address to 5 account emails per hour.

## 3. Run the app

### Option 1: IntelliJ

Open `NearbyBackendApplication` and click *Run*. On first start, `spring-boot-docker-compose` runs `docker compose up` for PostGIS, Redis and MinIO.

### Option 2: Terminal

```sh
./gradlew bootRun          # Windows: gradlew.bat bootRun
```

On first start, Flyway creates the schema and seeds the reference data.

### Load sample listings for database tests

Use a fresh development database for the demo. Flyway V14 rebuilds the listing tables, so back up
any existing listing data before applying that migration to a database that already contains listings.

After Flyway has run, import the optional [demo seed](../src/test/resources/db/demo/seed_demo.sql)
from the repository root. With the default Compose settings on Linux or macOS, run:

```sh
docker compose exec -T postgres psql -U nearby -d nearby -v ON_ERROR_STOP=1 --single-transaction -f - < src/test/resources/db/demo/seed_demo.sql
```

On PowerShell, pipe the file as UTF-8:

```powershell
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
Get-Content -Raw -Encoding utf8 src/test/resources/db/demo/seed_demo.sql | docker compose exec -T postgres psql -U nearby -d nearby -v ON_ERROR_STOP=1 --single-transaction -f -
```

The seed uses the `ELECTRONICS` reference category and creates two sample users, two verified areas,
and 20 listings with one image record each. Ten listings are within 1 km of latitude `10.8700`,
longitude `106.8000`. Use a registered account for login tests. The image keys are placeholders for SQL tests.

Run `./gradlew test --tests '*ListingDemoIntegrationTests'` with Docker running to verify the seed.
The test also places two demo listings on either side of 1 km and checks that the search API uses
their public locations for filtering and returns no coordinates.

### Check that the app is running

| URL | Expected result |
|---|---|
| http://localhost:8080/actuator/health | `{"status":"UP"}` |
| http://localhost:8080/swagger-ui.html | Swagger UI |
| http://localhost:8080/api/test | `{"status":"ok", ...}` |
| http://localhost:9001 | MinIO Console. Log in with `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY`. |

To browse the database with DBeaver or DataGrip, use host `localhost`, port `5433`, and `nearby` as the database, user and password.

## 4. Run the tests

```sh
./gradlew build            # tests + format check + module boundaries + coverage
./gradlew test             # tests only
./gradlew test --tests '*TokenServiceTests'
./gradlew test --tests '*ListingDemoIntegrationTests'
./gradlew format           # format code before committing
```

- Integration tests use Testcontainers with real PostGIS and Redis, so Docker must be running. Tests do not use `.env` or the `compose.yaml` containers.
- The coverage report is at `build/reports/jacoco/test/html/index.html`. The build fails if overall line coverage is under 60%, or under 80% for `domain` / `application` packages.
- The module report is at `build/spring-modulith-docs/`.
- Unformatted code fails the build. Run `./gradlew format` and build again.

## 5. Code structure

```
src/main/java/vn/edu/uit/nearby/nearby_backend/
├── identity/          Identity + Admin
├── listing/           Listing + Search
├── transaction/       Transaction
├── areaverification/  Area Verification
├── reputation/        Reputation
├── chat/              Chat + Notify
└── shared/            Shared kernel: constant, error, security, storage, websocket, ratelimit, geo, web
```

- Each module is split into `web` / `application` / `domain` / `infrastructure` layers. Only the root package is public; it holds Facade interfaces and domain events.
- `ModularityTests` fails the build when a module reaches into another module's internals.
- Coding rules live in `.claude/clean-code-skill/`.

### Constants

Shared constants live in `shared/constant/`, one class per concern, so every name says what it is:

| Class | Holds |
|---|---|
| `HeaderNames` | Custom HTTP headers, for example `IDEMPOTENCY_KEY` |
| `ApiPaths` | URL paths and security matcher patterns |
| `WebSocketDestinations` | STOMP destination prefixes |
| `SecurityConstants` | Bearer scheme, JWT claim names, authority prefix |
| `Roles` | `USER`, `ADMIN` |
| `ErrorCodes` | Problem-detail `code` values and the problem type base URI |
| `RedisKeyPrefixes` | Redis key namespaces |
| `ApiLimits` | Page sizes and header length limits |
| `GeoConstants` | `WGS84_SRID` |

Rules:

- Standard HTTP headers come from Spring's `org.springframework.http.HttpHeaders` (`AUTHORIZATION`, `CONTENT_TYPE`...). Do not redefine them.
- Add a new constant to the class of its concern, or create a new concern class. Never use a vague name such as `HEADER` or `PREFIX`.
- A value that is an internal detail of one class stays `private` in that class. Examples are the cursor encoding separator and a request attribute name.
- Values that may change at runtime belong in `app_parameters`, not in code.

## 6. Calling the API from the Android app

| Device | Base URL | `MINIO_PUBLIC_ENDPOINT` |
|---|---|---|
| Android Emulator | `http://10.0.2.2:8080` | `http://10.0.2.2:9000` |
| Physical phone on the same Wi-Fi | `http://<machine-LAN-IP>:8080` | `http://<machine-LAN-IP>:9000` |

- Inside the emulator, `localhost` is the emulator itself, not your computer.
- Android blocks plain HTTP by default. For debug builds, add a `network_security_config` that allows cleartext traffic to `10.0.2.2` or your LAN IP.
- Presigned URLs are signed for a specific host, so `MINIO_PUBLIC_ENDPOINT` must be exactly the host the phone calls. Change it in `.env` and restart the app.
- API contracts are in [docs/api/](api/). To try the account flow, import `docs/postman/nearby-identity.postman_collection.json` and `nearby-local.postman_environment.json` into Postman, then run the folders in order.
- Every API except `/api/v1/auth/**` needs `Authorization: Bearer <access-token>`. Errors follow RFC 9457 (`application/problem+json`) and include a `code` field.
- WebSocket: STOMP at `ws://<host>:8080/ws`. Send `Authorization: Bearer <token>` in the CONNECT frame.

## 6.1 Calling the API from a browser (CORS)

The admin console runs on another origin (Vite dev server `http://localhost:5173`), so the browser needs CORS. `CORS_ALLOWED_ORIGINS` (comma-separated) lists the allowed origins; the default allows `http://localhost:5173`. Tokens travel in the `Authorization` header, so credentials (cookies) stay off. The Android app is not a browser and needs no entry. Leave the variable empty to turn CORS off.

## 6.2 Monitoring (Prometheus + Grafana)

Off by default. Start the backend first (IDE or `./gradlew bootRun`), then:

```sh
docker compose --profile monitoring up -d prometheus grafana
```

| Tool | URL | Notes |
|---|---|---|
| Prometheus | http://localhost:9090 | Scrapes `host.docker.internal:8080/actuator/prometheus` every 15 s. *Status > Targets* must show `nearby-backend` as UP. Alert rules are under *Alerts* |
| Grafana | http://localhost:3000 | Login `admin` / `admin` (`GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD`). Dashboard *Nearby > Nearby backend* is provisioned automatically |

The configuration is shared with production: `deploy/prometheus/alerts.yml` (rules), `deploy/grafana/provisioning` (data source, dashboard provider) and `deploy/grafana/dashboards/nearby-backend.json`. Edit the JSON file, not the dashboard in the UI (UI changes are not saved). Stop with `docker compose --profile monitoring stop prometheus grafana`.

## 7. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `password authentication failed for user "nearby"` | The app connected to a PostgreSQL installed on the machine (port 5432) instead of the container. | Keep `POSTGRES_PORT=5433` in `.env` and run `docker compose up -d` to recreate the container. `docker compose ps` should show `0.0.0.0:5433->5432`. |
| `Port 8080 was already in use` | Another instance is running, usually from IntelliJ. | Stop it, or set `SERVER_PORT=8081` in `.env`. |
| `Could not find a valid Docker environment` during tests | Docker Desktop is not running. | Start Docker Desktop and wait until it shows *Running*. |
| `Image storage not reachable` (WARN) | MinIO is not running. The app still starts. | `docker compose up -d minio` |
| Flyway `checksum mismatch` | A migration that already ran was edited. | Never edit an applied migration; add a new `V9__...sql` instead. To reset local dev data, run `docker compose down -v`, which deletes all local data. |
| `checkFormatMain FAILED` | Code is not formatted. | `./gradlew format` |
| Browser shows a CORS error | The page's origin is not in `CORS_ALLOWED_ORIGINS`. | Add the exact origin (scheme, host and port) and restart the app. |
| Prometheus target `nearby-backend` is DOWN | The backend is not running on port 8080 of the host. | Start the app; on Linux Docker Engine check that `host-gateway` is supported (Docker 20.10+). |
| Log shows `TCP connection failure in session _system_` or `BrokerAvailabilityEvent[available=false` | RabbitMQ is not running, or its STOMP plugin is off. | `docker compose up -d rabbitmq`, then `docker compose exec rabbitmq rabbitmq-plugins list -e` must show `rabbitmq_stomp`. Without RabbitMQ set `WS_RELAY_ENABLED=false`. |
| A user on instance B never receives a message sent on instance A | Instance A or B runs with `WS_RELAY_ENABLED=false`. | Set it to `true` on every instance. |
| Log shows `FCM refused the push with status 401` or `403` | The service account is for another Firebase project, or the Firebase Cloud Messaging API is off. | Download a new key from the right project and enable *Firebase Cloud Messaging API* in Google Cloud. |
| Notifications show `push_status = SKIPPED` | `FCM_CREDENTIALS` is empty, the user has no device token, the category is switched off, or it is quiet hours. | Section 9.2. |

## 8. Useful Docker commands

```sh
docker compose ps                 # container status
docker compose logs -f postgres   # follow logs
docker compose down               # stop, keep data
docker compose down -v            # stop and DELETE local data
```

## 9. RabbitMQ (chat relay) and Firebase (push)

Every new technology the backend needs gets a manual setup and a manual test here, written so that a new developer can do both without asking anyone. Each section says what it is for, how to set it up, how to test it by hand, what a good result looks like, and what was really run.

### 9.1 RabbitMQ: the chat STOMP relay (FR-CH-02, NFR-20, ADR-13)

**What it is for.** Chat messages travel over STOMP. With two backend instances, a message sent on instance A must reach a user connected to instance B. RabbitMQ with its STOMP plugin is the broker both instances share. Tests that run one instance use an in-memory broker instead.

**Setup.**

1. `docker compose up -d rabbitmq` (it starts with the other services when you run the app from IntelliJ or `./gradlew bootRun`). The compose file enables the `rabbitmq_stomp` plugin and creates the user `nearby` / `nearby-secret`.
2. Keep `WS_RELAY_ENABLED=true` (the default). Nothing else to configure locally.
3. For production put the host, port and credentials in `RABBITMQ_*`; the backend needs the **STOMP** port (61613), not AMQP (5672).

**Manual test.**

```sh
docker compose ps rabbitmq                                    # State: running
docker compose exec rabbitmq rabbitmq-plugins list -e         # a line starts with [E*] and ends with rabbitmq_stomp
(echo > /dev/tcp/localhost/61613) && echo "STOMP port is open"  # Git Bash
./gradlew bootRun
```

In the backend log expect, in this order: `StompBrokerRelayMessageHandler : Started.`, then `"System" session connected.`, then `BrokerAvailabilityEvent[available=true`. Open `http://localhost:15672` (user `nearby`, password `nearby-secret`) and under *Connections* a connection from the backend appears. To prove it across instances, start a second instance on another port (`SERVER_PORT=8081 ./gradlew bootRun` in another terminal), connect two STOMP clients (one to each port) and send a message from one: the other receives it. The automated version of this test is `ChatRelayIntegrationTests` (two instances and a real RabbitMQ container).

**Verified.** The compose service, the plugin list, the open STOMP port and the relay connecting from `bootRun` were run on 2026-11-23. The two-instance test passes in CI. Not run by hand: two `bootRun` instances side by side.

### 9.2 Firebase Cloud Messaging: real push (FR-NT-02)

**What it is for.** When a user has no open socket, a message or event becomes a push on their phone. FCM HTTP v1 sends it. With no credentials the backend still creates the in-app notification and marks the push `SKIPPED`.

**Setup.**

1. In the [Firebase console](https://console.firebase.google.com) create a project (or use the Android team's), and add the Android app (its package name must match).
2. *Project settings → Service accounts → Generate new private key* gives a JSON file. Keep it **outside the repository** (for example `D:/secrets/nearby-fcm.json`); never commit it.
3. In `.env` set `FCM_CREDENTIALS=D:/secrets/nearby-fcm.json` and restart the backend. The project id is read from the file.
4. In Google Cloud, for the same project, check that *Firebase Cloud Messaging API* is enabled.
5. The Android app puts `google-services.json` of the same project in its module and calls `PUT /api/v1/devices/{deviceId}` with the token after login (see `docs/api/chat-api.md`, flow FC8).

**Manual test.**

1. Log in as user A on the phone (or register through the API) and register the device token: `PUT /api/v1/devices/{deviceId}` with `{"fcmToken": "<real token>", "platform": "ANDROID"}`.
2. Make sure A has no open socket (close the app or the STOMP client).
3. Cause an event for A: another user sends A a chat message, or register a new account and use *its* phone (the welcome notification is a push too).
4. The phone shows the push; tapping it opens the `deepLink`. In the database:

```sh
docker compose exec postgres psql -U nearby -d nearby -c "select type, push_status, pushed_at from notifications order by created_at desc limit 5"
```

Expect `push_status = SENT` for the push. Other values: `SKIPPED` (no credentials, no token, category off, quiet hours), `FAILED` (FCM refused or timed out; see the backend log line `FCM`), `PENDING` (only for a moment).

5. Dead tokens: register a made-up token such as `fake-token`; the first push to it makes FCM answer 404 or 400 and the row in `device_tokens` is deleted. The in-app notification stays.

**Verified.** Without credentials (`FCM_CREDENTIALS` empty) the flow was run on 2026-11-23 against the running backend: registering an account created the welcome notification, `GET /users/me/badges` showed `notificationUnread: 1`, and `PUT /devices/{id}` stored a token. A real push to a real phone was **not** run, because it needs a Firebase project and a device; the FCM calls are covered against a stub server by `FcmPushGatewayTests`.

For production deployment, see [deploy/README.md](../deploy/README.md).
