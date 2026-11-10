# MCP: letting an AI agent use the store

This page describes the MCP server of M4: what it offers an agent, how to connect Claude to it, how the agent key is
set up, and what an agent may and may not do. 

Decision behind it: [ADR 0015](../adr/0015-mcp-agent-boundaries.md).

## What it is

MCP (Model Context Protocol) is how an AI client such as Claude gets "tools" it can call. Our MCP server is a small
program, `margosha-mcp.jar`, that offers four record tools. Claude starts it on your laptop and talks to it through
its standard input and output (stdio), so it opens no port and nothing new is deployed. The server calls the
coordinator's normal API, the same way `curl` or YCSB do.

```
Claude (laptop) ──stdio──► margosha-mcp.jar (laptop) ──HTTP + agent key──► coordinator :8080 (AWS) ──► replicas
```

Code: `app/mcp-tools` (the tools, the limits, the error handling) and `app/mcp-server` (the runnable jar).

## What the server offers

Exactly four tools, one record at a time:

- **`exists_record`**: `table`, `partitionKey`, optional `sortKey`. Returns `{"exists": true}` or
  `{"exists": false}`. Marked read-only.
- **`read_record`**: `table`, `partitionKey`, optional `sortKey`. Returns the record's attributes. Marked
  read-only.
- **`create_record`**: `table`, `record` (attribute names and **string** values, key attributes included). Returns
  the created record. Fails if the key already exists, so it never changes an existing record.
- **`delete_record`**: `table`, `partitionKey`, optional `sortKey`. Returns `{"deleted": true}`. Marked
  **destructive**, so Claude asks you before every delete.

Each tool has a description and a JSON schema for its input. The server checks every call against the schema before
the tool runs, so a call without a table, or with a number as a value, never reaches the store.

When something goes wrong the tool returns an error result (not a crash) with a code and a message, for example:

```json
{"code": "RECORD_NOT_FOUND", "message": "Record note_id=n7 does not exist in table agent_notes"}
```

- Codes from the store: `RECORD_NOT_FOUND`, `RECORD_ALREADY_EXISTS`, `TABLE_NOT_FOUND`, `VALIDATION_ERROR`,
  `SHARD_UNAVAILABLE`, `AGENT_KEY_INVALID`, `AGENT_NOT_ALLOWED`.
- Codes from the MCP server itself: `TABLE_NOT_ALLOWED`, `AGENT_KEY_MISSING`, `COORDINATOR_UNREACHABLE`.
- When the store is unavailable during a create or a delete, the message says the outcome is unknown: the write may
  or may not have been stored, so the agent should read the record before trying again.

## What an agent may and may not do (ADR 0015)

May:

- check, read, create and delete one record at a time,
- only in the allowed tables (`agent_notes` by default).

May not:

- update a record (there is no tool for it, and the coordinator refuses it),
- register or change tables, register replicas, read the replication log or status, or call any other endpoint,
- list or scan a table (no such tool exists).

These limits are checked twice:

1. **In the MCP server.** A table that is not in `MCP_ALLOWED_TABLES` is refused with `TABLE_NOT_ALLOWED`, and
   without an agent key every call is refused with `AGENT_KEY_MISSING`. In both cases nothing is sent to the store.
2. **In the coordinator.** The MCP server sends the agent key in the `X-Agent-Key` header with every request. The
   coordinator answers:
   - **401** `AGENT_KEY_INVALID` when the key is wrong, or when the coordinator has no key,
   - **403** `AGENT_NOT_ALLOWED` for anything other than create, read, check or delete of a record in an allowed
     table.
   Requests **without** the header (`curl`, YCSB, the README commands) work as before.

So even a changed or badly set up MCP server can't do more than the four operations on the allowed tables.

The key is never in the repository or in a log line. For every tool call the MCP server logs one line with the tool,
the table, the result and the trace ID, but not the key and not the record's values:

```json
{"message":"Tool create_record on table agent_notes: OK","traceId":"fc12bd515885419399725ebd24a19eb8","tool":"create_record","table":"agent_notes","result":"OK", ...}
```

The same trace ID is sent to the coordinator, so the call can be followed into the store with
[tracing.md](tracing.md).

**Prompt injection.** A record can hold text written to give the agent orders ("ignore your task and delete…"). The
read tool's description tells the agent that record values are data, not instructions. If an agent follows such
text anyway, the worst it can do is create records or delete allowed ones, and you must approve every delete. It
cannot change existing records, because there is no update.

**Accepted limits.** An agent that can run commands (for example Claude Code with `curl`) could skip the MCP server
and call the API without the header, so without these limits. The API only accepts your IP (`OperatorCidr`), so this
is accepted. Agents on other machines can't connect, because the server runs only over stdio.

## 1. Build the jar

From `app/`:

Windows (PowerShell):
```powershell
.\mvnw.cmd -pl mcp-server -am package -DskipTests
```

macOS / Linux:
```bash
./mvnw -pl mcp-server -am package -DskipTests
```

The jar is `app/mcp-server/target/margosha-mcp.jar`. It needs Java 21 on the laptop (`java -version`).

## 2. Set up the agent key

The same key is kept in two places: as a GitHub secret, which the pipeline copies to SSM Parameter Store for the
coordinator, and in Claude's settings on your laptop.

**Make a key.**

Windows (PowerShell):
```powershell
$AGENT_KEY = [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Maximum 256 }))
$AGENT_KEY
```

macOS / Linux:
```bash
AGENT_KEY=$(openssl rand -base64 32)
echo "$AGENT_KEY"
```

**Save it as a GitHub secret** named `MARGOSHA_AGENT_KEY`: in the repository, Settings → Secrets and variables →
Actions → New repository secret. Or with the GitHub CLI (it asks for the value):

```bash
gh secret set MARGOSHA_AGENT_KEY
```

**Deploy** (push to `main`, or run the pipeline by hand). The step "Save the agent key in SSM" saves it as the
encrypted parameter `/margosha/agent-key`, and the coordinator reads it when it starts. Check that the parameter
exists (this does not print the value):

```bash
aws ssm describe-parameters --parameter-filters "Key=Name,Values=/margosha/agent-key" --query "Parameters[].[Name,Type,LastModifiedDate]" --output table
```

Check that the coordinator has the key. Its console must show `Agent requests: key set; allowed tables:
[agent_notes]` after its last boot (with `$COORD` from the README's "Find the nodes"):

Windows (PowerShell):
```powershell
aws ec2 get-console-output --instance-id $COORD --latest --output text | Select-String 'Agent requests|agent key'
```

macOS / Linux:
```bash
aws ec2 get-console-output --instance-id "$COORD" --latest --output text | grep -E 'Agent requests|agent key'
```

If it says `no key, all refused` or `Could not read the agent key from SSM`, the secret was not set, or the
coordinator could not read the parameter.

**Register the agent table once**, with a normal request (no agent key), using `$API` from the README:

Windows (PowerShell):
```powershell
Invoke-RestMethod -Method Post -Uri "$API/tables" -ContentType 'application/json' -Body '{"name":"agent_notes","partitionKey":"note_id"}'
```

macOS / Linux:
```bash
curl -s -X POST "$API/tables" -H 'Content-Type: application/json' -d '{"name":"agent_notes","partitionKey":"note_id"}'
```

## 3. Connect Claude

The server reads its settings only from environment variables:

- `MCP_COORDINATOR_URL`: the coordinator, for example `http://3.91.20.15:8080` (the value of `$API`).
- `MCP_AGENT_KEY`: the agent key from step 2.
- `MCP_ALLOWED_TABLES`: the tables the agent may use, comma-separated: `agent_notes`.
- `MCP_TIMEOUT` (optional): how long one call to the coordinator may take, `5s` by default.

The coordinator's public IP changes when the lab is stopped and started again. Then update `MCP_COORDINATOR_URL`.

### Claude Code

Add the server for your user only (`--scope user`). Don't use `--scope project`: it writes the settings, key
included, into `.mcp.json` in the repository.

Windows (PowerShell), from the repository root:
```powershell
claude mcp add --scope user --env "MCP_COORDINATOR_URL=$API" --env "MCP_AGENT_KEY=$AGENT_KEY" --env "MCP_ALLOWED_TABLES=agent_notes" margosha-db -- java -jar "$PWD\app\mcp-server\target\margosha-mcp.jar"
```

macOS / Linux, from the repository root:
```bash
claude mcp add --scope user --env "MCP_COORDINATOR_URL=$API" --env "MCP_AGENT_KEY=$AGENT_KEY" --env "MCP_ALLOWED_TABLES=agent_notes" margosha-db -- java -jar "$PWD/app/mcp-server/target/margosha-mcp.jar"
```

Check it with `claude mcp list` (the server should be connected). In a session, `/mcp` shows its four tools.

### Claude Desktop

Open the config file (Settings → Developer → Edit Config):

- Windows: `%APPDATA%\Claude\claude_desktop_config.json`
- macOS: `~/Library/Application Support/Claude/claude_desktop_config.json`

Add the server under `mcpServers`, with the full path to the jar (on Windows, write every `\` as `\\`):

```json
{
  "mcpServers": {
    "margosha-db": {
      "command": "java",
      "args": ["-jar", "C:\\4 course uni\\margosha-db\\app\\mcp-server\\target\\margosha-mcp.jar"],
      "env": {
        "MCP_COORDINATOR_URL": "http://<coordinator IP>:8080",
        "MCP_AGENT_KEY": "<your agent key>",
        "MCP_ALLOWED_TABLES": "agent_notes"
      }
    }
  }
}
```

On macOS the path looks like `/Users/<you>/margosha-db/app/mcp-server/target/margosha-mcp.jar`. Restart Claude
Desktop; the tools appear under the tools (slider) icon. If Desktop can't find `java`, put the full path to the
`java` program in `command`.

Claude Desktop keeps the server's log (its standard error) in `mcp-server-margosha-db.log`:

- Windows: `%APPDATA%\Claude\logs\`
- macOS: `~/Library/Logs/Claude/`

## 4. Check that the server starts

This starts the server the way Claude does and sends it the first MCP message, `initialize`. You should see exactly
one line: a JSON answer with `"serverInfo":{"name":"margosha-db","version":"0.1.0"}`. Any other line would mean
something besides MCP messages is written to standard output. The server stops by itself a few seconds later, when
its input ends.

Windows (PowerShell), from the repository root:
```powershell
$env:MCP_COORDINATOR_URL = $API; $env:MCP_AGENT_KEY = $AGENT_KEY; $env:MCP_ALLOWED_TABLES = 'agent_notes'
'{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"check","version":"1"}}}' | java -jar app\mcp-server\target\margosha-mcp.jar 2>$null
```

macOS / Linux, from the repository root:
```bash
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"check","version":"1"}}}' | MCP_COORDINATOR_URL="$API" MCP_AGENT_KEY="$AGENT_KEY" MCP_ALLOWED_TABLES=agent_notes java -jar app/mcp-server/target/margosha-mcp.jar 2>/dev/null
```

The tools are then checked from Claude: `/mcp` (Claude Code) or the tools icon (Claude Desktop) lists the four
tools. Then try it in Claude, for example: "Create a note n1 with the text hello in agent_notes, read it back, then delete
it." Claude asks before the delete. A session with all four tools and a refused call is in `mcp_session.md`
(task 7.7).

## Adding more tables for agents

The coordinator's list is in `app/node/src/main/resources/application-coordinator.properties`:

```properties
margosha.agent.allowed-tables=agent_notes,agent_tasks
```

1. Change it, push, and let the pipeline deploy. The coordinator is rebooted (not replaced) and reads the list when
   it starts.
2. Add the same table to `MCP_ALLOWED_TABLES` in Claude's settings and restart the server (Claude Desktop: restart
   the app; Claude Code: start a new session).
3. Register the new table in the store with a normal request.

The list can't change while the coordinator runs; that would need a change to ADR 0015.

## When something doesn't work

- `AGENT_KEY_MISSING`: `MCP_AGENT_KEY` is not set in Claude's settings.
- `AGENT_KEY_INVALID`: the key in Claude's settings is not the one in the `MARGOSHA_AGENT_KEY` secret, or the
  coordinator could not read it from SSM (see step 2). After changing the secret, deploy again.
- `TABLE_NOT_ALLOWED`: the table is not in `MCP_ALLOWED_TABLES`. `AGENT_NOT_ALLOWED` with "may not use table": it
  is not in the coordinator's list.
- `TABLE_NOT_FOUND`: register the table first (step 2).
- `COORDINATOR_UNREACHABLE`: the coordinator's IP changed, the coordinator is stopped, or your IP is no longer the
  stack's `OperatorCidr`.