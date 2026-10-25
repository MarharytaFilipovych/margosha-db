# ADR 0004: API protocol and format

## Context

We need an API for the Coordinator.
Clients use it to register table definitions and to
do CRUD over table records (plus EXISTS).
Shards use it to register with the Coordinator when they start.
The API is a fixed set of simple operations on one item at a time, addressed by key.
The requirements allow any protocol: OpenAPI REST, GraphQL, gRPC, or anything else.

## Alternatives considered

- *gRPC (Protocol Buffers over HTTP/2)*: a good fit for internal communication between microservices.
  It's rejected for the public API because it needs a `.proto` file and a code generation step for every client, and it can't be called with plain `curl` or a browser.
  That adds complexity this project doesn't need.
  It's also less familiar to most people than REST.
- *GraphQL*: a good fit when clients should get data in a flexible shape, for example by choosing the fields they need.
  It's rejected because our operations are fixed key lookups, so that flexibility brings nothing.
  With REST, the server keeps control over what each endpoint accepts and returns.
  In GraphQL, every request is also a `POST` to one URL, so HTTP status codes (404, 409) and methods (`HEAD`, `DELETE`) would no longer carry meaning.
- *Plain REST without a specification*: it's rejected because without the specification, the API would only be described by its implementation.
- *REST described with OpenAPI*: simple and widely used, and most people understand it better than gRPC or GraphQL.
  Data stores use it too: Elasticsearch, for example, exposes its whole API as REST with JSON, and it works fine for its users.
  It's also my personal preference.
  Other well-known benefits of REST:
  - *Resources as URLs*: every table and record has its own address (`/tables/{tableName}/records/{partitionKey}`), so the URL alone shows what a request works on.
  - *Works with standard infrastructure*: load balancers, proxies and API gateways understand plain HTTP, so nothing special is needed between the client and the Coordinator.
  - *Easy to debug*: requests and responses are readable text (JSON), so they can be inspected in logs or with `curl` without extra tools.

## Decision

REST over HTTP, with JSON bodies, described in OpenAPI 3.0.3.

| Operation | Request |
|---|---|
| Register a table | `POST /tables` |
| CREATE a record | `POST /tables/{tableName}/records` |
| READ a record | `GET /tables/{tableName}/records/{partitionKey}?sortKey=…` |
| UPDATE a record | `PUT /tables/{tableName}/records/{partitionKey}?sortKey=…` |
| DELETE a record | `DELETE /tables/{tableName}/records/{partitionKey}?sortKey=…` |
| EXISTS | `HEAD /tables/{tableName}/records/{partitionKey}?sortKey=…` |
| Register a shard | `POST /shards` |

Design choices inside the API:
- **Keys**: every table has a partition key and an optional sort key.
  Each key is declared with a name.
  Key values are strings.
- **Values**: a record's value is a set of named attributes with string values.
  Only the keys are declared in the table definition.
- **No range queries**: the sort key is only used to identify a single record.
- **Tables can only be registered**: the requirements ask only for registering a table definition, so there is no read, update or delete of tables.
  The partition key and the sort key can't be changed after the table is registered.
- **CREATE vs UPDATE**: CREATE fails with `409` if the record already exists.
  UPDATE fails with `404` if it doesn't, and it replaces the whole value.
- **Shard registration**: each shard calls `POST /shards` with its shard ID and the address it serves on when it starts.
  The Coordinator then forwards record requests to the shards over the same REST API, so there is no second protocol between them.
- **Errors**: one shared format, `{code, message}`, used with `400`, `404` and `409`.

## Consequences

- The API stays simple and familiar: anyone who knows REST can read the spec and use the store without learning a new protocol.
- Any HTTP client can call the API: `curl`, Postman, a browser, or any programming language.
  No code generation is needed, which keeps testing and the demo simple.
- HTTP methods and status codes carry meaning on their own.
  EXISTS is a `HEAD` request that returns only `200` or `404`, without a body.
- The OpenAPI file is a single, language-independent contract, written before the Coordinator code.
  Swagger UI can render it, and `openapi-spec-validator` can check it.
- The sort key is a query parameter rather than part of the path, so tables with and without a sort key use the same URLs.
  The cost is that the Coordinator has to return `400` when the sort key is missing for a table that has one, or sent to a table that doesn't.
- JSON is text, so it's larger and slower to parse than gRPC's binary format.
  This is acceptable at this project's scale.
  If the YCSB baseline shows that the API format is a bottleneck, gRPC can be reconsidered.
- Keys and attribute values are plain strings, so the store doesn't check types.
  A client that needs numbers or nested data has to encode them as strings.
- Without range queries, the sort key only identifies a record.
  Range queries could be added later as one new endpoint without changing the existing ones.
