# rhythmnest_billing

This project was created using the [Ktor Project Generator](https://start.ktor.io).

Here are some useful links to get you started:
 * [Ktor Documentation](https://ktor.io/docs/home.html)
 * [Ktor GitHub page](https://github.com/ktorio/ktor)
 * [Ktor Slack chat](https://app.slack.com/client/T09229ZC6/C0A974TJ9). [Request an invite](https://surveys.jetbrains.com/s3/kotlin-slack-sign-up).


## Features
Here's a list of features included in this project:

| Name | Description |
|------|-------------|

## Building & Running

Build the tested server distribution in the Nix sandbox:

```shell
nix build
```

The output is available at `result/bin/rhythmnest-billing`, with its runtime
libraries under `result/lib`. Run it from any directory. If that directory
contains a `.env`, its values are exported before startup; otherwise the server
uses the inherited environment unchanged. It uses the JDK packaged by Nix and
does not need Gradle at runtime.

Run a disposable local environment with its own temporary PostgreSQL database:

```shell
nix run .#dev
```

The development command loads the project root `.env`, but overrides
`HERMIMORPH_BILL_DB_JDBC_URL`, `HERMIMORPH_BILL_DB_USER`, and
`HERMIMORPH_BILL_DB_PASSWORD` with an isolated temporary PostgreSQL connection.
The database is removed when the process exits. Set
`HERMIMORPH_BILL_DEV_DB_PORT` in `.env` to resolve a local database port
conflict.

For production, either export the required environment variables externally or
create `.env` from `.env.example`, then run the packaged server:

```shell
/path/to/result/bin/rhythmnest-billing
```

The flake app has the same current-directory behavior. Point `nix run` at the
project when invoking it elsewhere, for example
`nix run /path/to/rhythmnest_billing#default`.

Both commands run the server distribution built by Nix. They do not require
Gradle, the ignored project-local `kotlin` launcher, or Kotlin CLI at runtime.

Other project tasks are available in the Nix development shell:

```shell
nix develop
gradle-project test
gradle-project build
```

After changing Gradle plugins or dependencies, refresh the reproducible Nix
dependency lock:

```shell
"$(nix build .#server.mitmCache.updateScript --no-link --print-out-paths)"
```

If the server starts successfully, you'll see the following output:
```
2024-12-04 14:32:45.584 [main] INFO  Application - Application started in 0.303 seconds.
2024-12-04 14:32:45.682 [main] INFO  Application - Responding at http://0.0.0.0:8080
```

## Database

The server requires `HERMIMORPH_BILL_HOST`, `HERMIMORPH_BILL_PORT`, `HERMIMORPH_BILL_API_TOKEN`, `HERMIMORPH_BILL_DB_JDBC_URL`, `HERMIMORPH_BILL_DB_USER`, `HERMIMORPH_BILL_DB_PASSWORD`, and `HERMIMORPH_BILL_INITIAL_ADMIN_ID` environment variables. See `.env.example` for example values. `nix run .#default` loads these values from the project root `.env`; the application itself does not load `.env` files.

Exposed operations must be submitted through the application-wide `DatabaseQueue`. A single worker executes transactions in queue order, and HikariCP maintains one PostgreSQL connection. This ordering guarantee only applies to a single running application instance.

Authenticated operations that identify an operator require an
`X-Request-Timestamp` header. Its value is the request instant as Unix epoch
milliseconds (UTC) and must be within 60 seconds of the server's receive time.
The server stores this request time separately from its processing time. For
billing, epoch instants are converted into the server's configured local time
zone before matching rate periods; clients must not encode a local wall-clock
time as a Unix timestamp.

Billing units are consecutive 30-minute intervals anchored at the guest's entry
time. The rate active at the start of a unit applies to the whole unit. If a
local rate-period boundary falls inside a unit, the new rate starts at the next
entry-aligned unit; a final partial unit is charged as one started unit.

## OpenAPI

Generate the OpenAPI 3.1 document during the build process:

```shell
gradle-project generateOpenApi
```

The generated document is written to `build/openapi/openapi.json`. The application does not expose OpenAPI or Swagger endpoints at runtime.
