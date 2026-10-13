## Why

Milestone 1 of the Cloud Architectures capstone requires setting up the infrastructure for the project inside the AWS Academy sandbox. 
The sandbox gives a fixed role (LabRole) and does not allow creating or attaching our own IAM policies and roles, 
so access-control resources must be described in IaC instead of deployed. The network created here is the base that later milestones will build on.
The AWS Academy sandbox session lasts about 3 hours and is operated under the `voclabs` role. Two of the planning-only tasks - the LabRole analysis and the Milestone 2 quota review - are carried out against both this sandbox and a separate AWS Learner Lab environment, since quotas and policy specifics can differ between them.

## What Changes

- Write down the LabRole: what it allows, what it denies, and how this was found out (console, CLI, policy simulator) - done once against the AWS Academy sandbox and once against the AWS Learner Lab environment.
- Add an IaC template for an IAM policy that allows only viewing resources. Not attached in the sandbox and delivered with an explanation of what it would do.
- Add an IaC template for a Role that has this policy attached and could be assumed by another principal. Delivered with an explanation of why the sandbox refuses it.
- Add a VPC with public and private subnets. The public subnet has an Internet Gateway; private subnets are for internal access only.
- Add an IaC stack holding the network resources.
- Document the service quotas needed in Milestone 2 that can limit us - done once against the AWS Academy sandbox and once against the AWS Learner Lab environment.
- Document the monthly budget for Milestone 2, assuming 2 shards, compared against the sandbox credit ceiling.
- Prove recreatability: tear the whole stack down and bring it back from IaC alone, recording how long it took and what broke on the first attempt.

## Decisions required before implementation (ADRs)

- Choice of IaC tool
- Stack layout (one stack, or network separate from application)
- CIDR addressing scheme

These are not decided in this proposal. Each is recorded in its own ADR.

## Capabilities

### New Capabilities

- `sandbox-iam`: LabRole analysis, read-only IAM policy and assumable role described in IaC.
- `network`: VPC with public and private subnets, held in an IaC stack.
- `infra-operations`: Milestone 2 service quotas, Milestone 2 budget, and the recreatability test.

## Impact

- New IaC templates in the repository (policy, role, network stack).
- New documentation: LabRole dissection, quota review and budget, recreatability test, VPC creation, role/policy creation.
- README: how to bring the stack up, how to verify it, what changed since the previous milestone (none for M1).
- AWS resources are created in the AWS Academy sandbox only (network stack). The sandbox session lasts 3 hours, operates under the `voclabs` role, and resources are deleted when it ends, so the stack must be redeployed from IaC each session. IAM policy and role are not deployed.