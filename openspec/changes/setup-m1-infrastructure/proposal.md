## Why

Milestone 1 of the Cloud Architectures capstone requires setting up the infrastructure for the project.

M1 is built in the AWS Academy Cloud Architecting Sandbox. Sessions there last 3 hours, all resources are deleted when the session ends, and every session is a new AWS account. There is no LabRole in this sandbox - the session runs as the role `voclabs`, which cannot create or attach IAM policies and roles, so access-control resources must be described in IaC instead of deployed.

M2 and later milestones run in the AWS Academy Learner Lab instead (agreed with the tutor). There, resources persist between sessions (EC2 instances stop and restart automatically), there is a fixed budget shown on the lab page, and the role `LabRole` plus the instance profile `LabInstanceProfile` exist.

The network created in M1 is the base that M2 will build on.

## What Changes

- Write down what the session role `voclabs` allows and denies in the Cloud Architecting Sandbox, and how this was found out (console, CLI, policy simulator).
- Write down what `LabRole` allows and denies in the Learner Lab, and how this was found out (console, CLI, policy simulator).
- Add an IaC template for an IAM policy that allows only viewing resources. Not attached in the sandbox and delivered with an explanation of what it would do.
- Add an IaC template for a Role that has this policy attached and could be assumed by another principal. Delivered with an explanation of why the sandbox refuses it.
- Add a VPC with public and private subnets. The public subnet has an Internet Gateway; private subnets are for internal access only.
- Add an IaC stack holding the network resources, exporting the VPC ID and subnet IDs so the M2 application stack can import them.
- Document the service quotas needed in Milestone 2 that can limit us, using the Learner Lab limits since M2 runs there.
- Document the monthly budget for Milestone 2 in the Learner Lab, assuming 2 shards running 24/7 (730 h/month), compared against the remaining budget shown on the Learner Lab page.
- Prove recreatability: tear the whole stack down and bring it back from IaC alone, in the same Cloud Architecting Sandbox session, recording how long it took and what broke on the first attempt.

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
- New documentation: role dissection (`voclabs` in the sandbox, `LabRole` in the Learner Lab), quota review and budget, recreatability test, VPC creation, role/policy creation.
- README: how to bring the stack up, how to verify it, what changed since the previous milestone (none for M1).
- AWS resources are created in the AWS Academy Cloud Architecting Sandbox only (network stack) for M1. The sandbox session lasts 3 hours, operates under the `voclabs` role, and resources are deleted when it ends, so the stack must be redeployed from IaC each session. IAM policy and role are not deployed.
- M2 and later milestones run in the AWS Academy Learner Lab instead: resources persist between sessions (EC2 instances stop and restart automatically) and a fixed budget shown on the lab page applies.