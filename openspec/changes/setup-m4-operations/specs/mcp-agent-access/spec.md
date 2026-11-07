# Spec Delta

## Purpose

Lets AI agents use the key-value store through an MCP server: which tools they get, the schemas and errors of those
tools, what an agent is allowed and refused, and how its requests are authorised.

## ADDED Requirements

### Requirement: MCP server with record tools
An MCP server SHALL offer exactly four tools to agents: EXISTS, CREATE, READ and DELETE of a record. Each tool SHALL
perform the matching store operation through the coordinator's API and SHALL behave as that operation does in the
key-value API.

#### Scenario: Tool list
- **WHEN** an MCP client connects and lists the tools
- **THEN** it receives the EXISTS, CREATE, READ and DELETE tools and no others

#### Scenario: Create then read
- **WHEN** an agent creates a record with the CREATE tool and then calls READ with its key
- **THEN** READ returns the record's key values and attributes

#### Scenario: Exists after delete
- **WHEN** an agent deletes a record with DELETE and then calls EXISTS with its key
- **THEN** EXISTS returns false

### Requirement: Tool input schemas
Every tool SHALL publish a JSON schema for its input: the table name and, for EXISTS, READ and DELETE, the partition
key value and the sort key value where the table has one; for CREATE, the record as string attributes with its key
attributes included, as in the key-value API. Each tool SHALL publish a description an
agent can use to choose it.

#### Scenario: Schema published
- **WHEN** an MCP client lists the tools
- **THEN** each tool has a description and an input schema that names its required fields and their types

#### Scenario: Input that does not match the schema
- **WHEN** an agent calls CREATE without a table name, or with an attribute value that is not a string
- **THEN** the tool returns a validation error and nothing is sent to the store

### Requirement: Store errors returned as tool errors
When the store refuses or fails an operation, the tool SHALL return an MCP tool error that includes the store's error
code and message, not a protocol failure. A service-unavailable answer to CREATE or DELETE SHALL say that the outcome
is unknown.

#### Scenario: Record not found
- **WHEN** an agent reads a key that has no record
- **THEN** the tool returns an error result with the not-found code and the store's message

#### Scenario: Record already exists
- **WHEN** an agent creates a record whose key already exists
- **THEN** the tool returns an error result with the conflict code and the existing record is unchanged

#### Scenario: Store unavailable
- **WHEN** the coordinator cannot be reached or answers service-unavailable to a CREATE
- **THEN** the tool returns an error result that says the store is unavailable and the write's outcome is unknown

### Requirement: What an agent may not do
Through the MCP server an agent SHALL NOT update records, register or change tables, register replicas, read the
replication log or status, or call any coordinator endpoint other than the four record operations. Every further
limit chosen in the MCP boundaries ADR (for example allowed tables) SHALL be enforced by the server
and refused with a tool error naming the limit.

#### Scenario: Table not allowed
- **WHEN** the ADR limits agents to configured tables and an agent calls a tool on another table
- **THEN** the tool returns an error that the table is not allowed, and nothing is sent to the store

#### Scenario: No way to update
- **WHEN** an agent wants to change an existing record
- **THEN** no tool lets it update the record in place

### Requirement: Agent requests are authorised
Every request made for an agent SHALL be authorised as decided in the MCP boundaries ADR. A request without valid
credentials SHALL be refused and SHALL change nothing. Credentials SHALL NOT be kept in the repository or written to
logs.

#### Scenario: Missing or wrong credentials
- **WHEN** the MCP server is started without valid credentials, or with wrong ones, and an agent calls CREATE
- **THEN** the call is refused with an authorisation error and no record is created

#### Scenario: Credentials not logged
- **WHEN** the MCP server and the coordinator handle an agent's request
- **THEN** none of their log lines contain the credential value

### Requirement: Verified with an MCP client
The MCP server SHALL be checked with an MCP client (for example Claude), and the repository SHALL document how to
connect the client and contain a transcript of a session that uses all four tools and is refused at least once.

#### Scenario: Client session
- **WHEN** a reviewer follows the documented steps to connect an MCP client
- **THEN** the client lists the four tools and can create, check, read and delete a record in the deployed store
