# Spec Delta

## ADDED Requirements

### Requirement: Monitoring within the instance limit
The monitoring stack chosen in the monitoring stack ADR SHALL run on the existing nodes or on managed or external
services, and SHALL add no EC2 instance. Every AWS resource it needs SHALL be defined in the CloudFormation templates
and deployed by the pipeline, and its configuration SHALL be kept in the repository.

#### Scenario: Instance count
- **WHEN** the M4 stack is deployed with `BenchClient` set to `false`
- **THEN** it runs the same 7 instances as in M3, and with `BenchClient` set to `true` it runs 8

#### Scenario: Monitoring after a fresh deploy
- **WHEN** the stacks are deployed by the pipeline into an account where only `margosha-vpc` exists
- **THEN** metrics from all 7 nodes reach the monitoring backend, and the dashboard and alerts exist, with no manual console steps beyond those the monitoring stack ADR documents

### Requirement: Monitoring does not widen access to the nodes
Metrics endpoints SHALL be reachable only by the monitoring collector and from inside the stack, with the
coordinator's endpoints also reachable from the operator CIDR. Replicas SHALL stay unreachable from outside the VPC.
No secret used by the monitoring stack SHALL be kept in the repository.

#### Scenario: Replica metrics not public
- **WHEN** someone outside the VPC tries to reach a replica's metrics endpoint
- **THEN** the connection is refused or times out

#### Scenario: Monitoring secrets
- **WHEN** a reviewer searches the repository for the monitoring stack's credentials or API keys
- **THEN** none are found, and the documentation says where they are set instead
