# contacts-api

A Quarkus REST API for managing contacts and organizing them into contact groups.


## Requirements

- Java 21
- Maven
- MySQL 8

## Database Setup

Create a MySQL database:

```sql
CREATE DATABASE contacts_db;
```

Set the database password before running the application:

```bash
export DB_PASSWORD=your_mysql_password
```

Flyway migrations run automatically when the application starts and create the required database schema.

## Running the Application

Start the application in development mode:

```bash
mvn quarkus:dev
```

The API runs at:

```text
http://localhost:8080
```

## Testing the API

Swagger UI can be used to test all API endpoints:

```text
http://localhost:8080/q/swagger-ui/
```

The exported OpenAPI contract is available at:

```text
docs/openapi.yaml
```


## Design Decisions

- The application uses a layered architecture with separate resource, service, repository, entity, and DTO packages.
- Resources handle HTTP requests and delegate business logic to services.
- DTOs are used for API requests and responses so JPA entities are not exposed directly.
- Panache repositories are used for database access.
- Flyway manages the database schema, and Hibernate automatic schema generation is disabled.
- A contact may belong to one contact group, and the group is optional.
- Contact email and contact group name uniqueness are enforced by the database.
- Contact and group listing supports pagination and filtering.
- API errors are handled centrally and use a consistent `status`, `error`, and `message` response structure.
- The API is versioned under `/api/v1`.

## Packaging and running the application

The application can be packaged using:

```shell script
mvn package
```

It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.

The application is now runnable using `java -jar target/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:

```shell script
mvn package -Dquarkus.package.jar.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar target/*-runner.jar`.

## Creating a native executable

You can create a native executable using:

```shell script
mvn package -Dnative
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using:

```shell script
mvn package -Dnative -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./target/contacts-api-1.0.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult <https://quarkus.io/guides/maven-tooling>.
