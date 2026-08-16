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
To build or run the project, use one of the following tasks:


| Task | Description |
|------|-------------|
| `./kotlin test`    | Run the tests     |
| `./kotlin build`   | Build the project |
| `./kotlin run`     | Run the server    |

If the server starts successfully, you'll see the following output:
```
2024-12-04 14:32:45.584 [main] INFO  Application - Application started in 0.303 seconds.
2024-12-04 14:32:45.682 [main] INFO  Application - Responding at http://0.0.0.0:8080
```

## Database

The server requires `HERMIMORPH_BILL_HOST`, `HERMIMORPH_BILL_PORT`, `HERMIMORPH_BILL_API_TOKEN`, `HERMIMORPH_BILL_DB_JDBC_URL`, `HERMIMORPH_BILL_DB_USER`, `HERMIMORPH_BILL_DB_PASSWORD`, and `HERMIMORPH_BILL_INITIAL_ADMIN_ID` environment variables. See `.env.example` for example values. The application does not load `.env` files automatically.

Exposed operations must be submitted through the application-wide `DatabaseQueue`. A single worker executes transactions in queue order, and HikariCP maintains one PostgreSQL connection. This ordering guarantee only applies to a single running application instance.

## OpenAPI

Generate the OpenAPI 3.1 document during the build process:

```shell
./kotlin test --include-test=io.github.hemimogph.OpenApiGenerationTest.generateOpenApi
```

The generated document is written to `build/openapi/openapi.json`. The application does not expose OpenAPI or Swagger endpoints at runtime.
