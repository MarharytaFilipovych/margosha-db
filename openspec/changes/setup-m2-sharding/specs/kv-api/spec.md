# Spec Delta

## Purpose

Defines the client-facing contract of the key-value store: the API specification document, table definitions with a compound primary key, and the record operations with their results and errors.

## ADDED Requirements

### Requirement: API specification document
The project SHALL keep a machine-readable API specification in the repository, in the protocol and format chosen by the API protocol ADR. It SHALL describe registering a table, the record operations and shard registration, with their inputs, results and errors.

#### Scenario: Specification covers every operation
- **WHEN** a reviewer opens the API specification
- **THEN** it describes register table, EXISTS, CREATE, READ, UPDATE, DELETE on records, and register shard

### Requirement: Register a table definition
The API SHALL let a client register a table with a name, the name of its partition key attribute and, optionally, the name of its sort key attribute. Together, the partition key and the sort key (if defined) form the table's primary key.

#### Scenario: Table with partition key only
- **WHEN** a client registers table `users` with partition key `user_id`
- **THEN** the table is created and every record in it is identified by `user_id` alone

#### Scenario: Table with partition key and sort key
- **WHEN** a client registers table `orders` with partition key `customer_id` and sort key `order_id`
- **THEN** the table is created and every record in it is identified by the pair (`customer_id`, `order_id`)

#### Scenario: Invalid table definition
- **WHEN** a client registers a table with an empty name or without a partition key
- **THEN** the request is rejected with a validation error and no table is created

#### Scenario: Table already exists
- **WHEN** table `users` exists and a client registers `users` again
- **THEN** the request is rejected with a conflict error and the existing table is kept

### Requirement: Record shape
A record SHALL consist of its primary key values and a set of named attributes with string values. Primary key values SHALL be non-empty strings.

#### Scenario: Missing key attribute
- **WHEN** a client sends a record for table `orders` without a value for the sort key `order_id`
- **THEN** the request is rejected with a validation error

### Requirement: CREATE puts a new record
The CREATE operation SHALL store a record under its primary key. If a record with that key already exists, the request SHALL be rejected with a conflict error and the existing record SHALL be kept.

#### Scenario: New record
- **WHEN** a client creates record `user_id=42` with attribute `name=Ann` in table `users`
- **THEN** a later READ of `user_id=42` returns `name=Ann`

#### Scenario: Record already exists
- **WHEN** record `user_id=42` exists and a client creates `user_id=42` again
- **THEN** the request is rejected with a conflict error and the existing record is unchanged

### Requirement: READ returns a record
The READ operation SHALL return the record stored under the given primary key, with all its attributes.

#### Scenario: Record exists
- **WHEN** a client reads a key that has a record
- **THEN** it receives the record's key values and attributes

#### Scenario: Record does not exist
- **WHEN** a client reads a key that has no record
- **THEN** the request fails with a not-found error

### Requirement: EXISTS checks for a record
The EXISTS operation SHALL return whether a record is stored under the given primary key, without returning the record.

#### Scenario: Present and absent keys
- **WHEN** a client checks a key that has a record and a key that has none
- **THEN** it receives true for the first and false for the second

### Requirement: UPDATE replaces a record
The UPDATE operation SHALL replace all attributes of an existing record with the given ones. Updating a key that has no record SHALL fail with a not-found error.

#### Scenario: Update existing record
- **WHEN** record `user_id=42` has attributes `name=Ann, city=Kyiv` and a client updates it with `name=Bob`
- **THEN** a later READ of `user_id=42` returns only `name=Bob`

#### Scenario: Update of missing record
- **WHEN** a client updates a key that has no record
- **THEN** the request fails with a not-found error and no record is created

### Requirement: DELETE removes a record
The DELETE operation SHALL remove the record stored under the given primary key. Deleting a key that has no record SHALL succeed and report that nothing was removed.

#### Scenario: Delete existing record
- **WHEN** a client deletes `user_id=42` and then checks it with EXISTS
- **THEN** EXISTS returns false

#### Scenario: Delete missing record
- **WHEN** a client deletes a key that has no record
- **THEN** the request succeeds and reports that no record was removed

### Requirement: Operations on unknown tables
Every record operation on a table that is not registered SHALL fail with a not-found error for the table.

#### Scenario: Record operation on unknown table
- **WHEN** a client creates a record in table `ghost` that was never registered
- **THEN** the request fails with a not-found error for the table and nothing is stored
