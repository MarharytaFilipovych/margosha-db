# ADR 0015: What an agent may do through MCP

## Context

AI agents such as Claude get access to the store through an MCP server: a small program that offers them four tools,
EXISTS, CREATE, READ and DELETE. We need to decide what an agent may and may not do, where the server runs, and how
the store knows a request is allowed. Today the coordinator has no login: only the firewall (security group), which
lets in the operator's IP, protects its API.

## Alternatives considered

### Where the server runs

- **On the coordinator, over HTTP:** agents anywhere could connect, but it is a new public address, and MCP then asks
  for OAuth login on it. Rejected.
- **On the operator's laptop, over stdio:** Claude starts the server as a local program and talks to it through its
  input and output. No new port and nothing deployed. The server calls the coordinator's normal API.

### How requests are allowed

- **Only the MCP server checks, the firewall protects the rest:** simple, but a changed or badly set up MCP server
  has no limits at all. Rejected.
- **OAuth:** the standard for MCP servers on the internet, far too heavy here. Rejected.
- **An agent key that the coordinator checks:** the MCP server sends the key with every request, and the coordinator
  lets those requests use only the four operations on allowed tables. The store itself enforces the limits.
- **A key for every client:** would stop all direct calls, but YCSB, the README commands and the pipeline would all
  need a key. Rejected for now.

### Tables

- **All tables:** the agent could read and delete any record it can name, benchmark data included. Rejected.
- **A list of allowed tables:** only the tables we configure.

### Delete

- **A `confirm` field:** the agent can fill it in by itself, so it protects nothing. Rejected.
- **Marked as destructive:** clients such as Claude then ask the user before every delete.

## Decision

The MCP server is a separate Spring Boot jar using Spring AI's MCP server starter, and it runs over stdio on the
operator's laptop. The rest of the project is Spring Boot, so the tools are normal annotated methods, written like
the coordinator. The protocol uses the console output, so the server turns off Spring's banner and console logs.

An agent may use EXISTS, READ, CREATE and DELETE, one record at a time, and only on allowed tables. READ and EXISTS
are marked read-only and DELETE is marked destructive. Everything else is refused: UPDATE, registering tables or
replicas, the replication and status endpoints, and any other call. No tool lists or scans a whole table.

The MCP server sends an agent key in a header with every request. The coordinator reads its own copy from SSM
Parameter Store when it starts. A wrong key gets 401. A request with the right key may only use the four operations
on allowed tables, and gets 403 otherwise. The key is stored as a GitHub secret, which the pipeline copies to SSM,
and in an environment variable in Claude's MCP settings on the laptop. It is never in the repository or the logs.
For every call we log the tool, table, result and trace ID, but never the key or the record's values.

A record may contain text written to give the agent orders (prompt injection). The tool descriptions say that
returned values are data, not instructions. The agent has no update or admin tools, so the worst it can do is create
records or delete allowed ones, and the user must approve every delete.

## Consequences

- No new port and nothing new to deploy; the server runs only while Claude uses it.
- Spring Boot makes the jar bigger and adds a few seconds each time Claude starts the server.
- The store itself limits agent requests, not only the MCP server.
- Requests without a key work as before. An agent that can run commands (for example Claude Code with `curl`) could
  skip the MCP server and call the API without limits. Accepted: the API only accepts the operator's IP.
- Agents on other machines (for example n8n) can't connect; that would need HTTP and OAuth.
- An agent can delete data, but only one allowed record at a time, after the user approves.
- The coordinator gets a small key check and reads one SSM value at start.
