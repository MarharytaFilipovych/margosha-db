# AI design review — Margosha DB, Milestone 1

Oct 24, 2026

**Reviewed:** `cfn/margosha-vpc-stack.yaml`, `cfn/read-only-role.yaml`, ADR 0001–0003, `m1/role_dissection.md`, `m1/role_policy_creation.md`, `m1/recreatability_test.md`, `m1/quotas_and_budget.md`

**Prompt given to the AI:** "Critique the architecture of my Milestone 1: network stack, IAM policy and role, stack layout, CIDR scheme, and the M2 network and budget plan. Find weaknesses, contradictions and risky assumptions."

## 1. Spreading the shards across two AZs doesn't buy any availability (high)

ADR 0003 rejects putting both private subnets in one AZ because "an outage of that AZ would take it all down". But the public subnet, and so the coordinator, exists only in `us-east-1a`. If 1a fails, you lose the coordinator, which is the only entry point, and shard A. Shard B in 1b survives but nobody can reach it. So the two-AZ layout doesn't survive an outage of 1a. The only AZ it survives is 1b, and that case is no better than putting everything in 1a. Meanwhile it adds about $0.02/GB of cross-AZ traffic and extra latency on every request that goes to shard B (`quotas_and_budget.md` §3.3). Either the ADR's availability argument should be scoped down ("prepares for M3 replicas"), or the two-AZ choice needs a different justification.

## 2. The read-only policy is a hand-made list when AWS already has one (medium)

`read-only-role.yaml` lists Describe/List actions for about 25 services by hand. Any service not on the list is invisible: API Gateway, Step Functions, Kinesis, EFS, Backup and others. AWS ships the managed policy `arn:aws:iam::aws:policy/job-function/ViewOnlyAccess` for exactly this purpose: metadata only, no data contents. AWS maintains it as services are added. Attaching it through `ManagedPolicyArns` would be shorter and complete. The counterargument is that the task says "create an IAM policy", so you may need to show your own.

## 3. Trusting `:root` lets anyone in the account assume the role (medium)

The example deploy uses `TrustedPrincipalArn=arn:aws:iam::${ACCOUNT_ID}:root`. In practice this delegates the decision to identity policies: any user or role in the account with `sts:AssumeRole` on this ARN can use it. The trust policy has no `Condition`: no `aws:MultiFactorAuthPresent` and no `sts:ExternalId` for cross-account use. For a read-only role the risk is low, but `iam:Get*` plus `iam:List*` includes `GetAccountAuthorizationDetails`, which dumps the account's whole permission map. That is useful reconnaissance for an attacker.

## 4. The network stack doesn't enforce "internal only" beyond routing (low–medium)

Private subnets are "internal only" because they have no `0.0.0.0/0` route. The template defines no security groups and no NACLs, so the VPC's default security group (all traffic allowed between its members) and the default NACL (all allowed) apply. Routing stops traffic to and from the internet, but nothing in IaC controls traffic between the public and private tiers. The counterargument: security groups belong to the instances, so they go in the M2 app stack (ADR 0002).

## 5. The M2 access plan means opening SSH to the internet (medium)

Without NAT or SSM endpoints, shards are reached by SSH through the coordinator with `vockey` (`quotas_and_budget.md`). That means port 22 on a public instance, which is also the database's front door. A shared lab key is then the only thing separating the internet from the shards. You could restrict port 22 to your own IP, or since Session Manager works on the coordinator, use SSM port forwarding to the shards instead of SSH from the internet.

## 6. Standard-mode T3 credits could invalidate the YCSB baseline (medium)

Standard mode is a good budget safeguard. But `t3.micro` baseline is 10% per vCPU, and YCSB load keeps CPU high. Once credits run out, throughput drops to the baseline in the middle of a run. The M2 numbers are the baseline for M3 and M5, so comparisons could reflect credit balances rather than the design. The doc mentions checking the credit balance, but no rule makes the runs comparable, such as starting each run with a full balance or using a fixed run length.

## 7. Small template issues (low)

- AZ defaults are hardcoded (`us-east-1a/b`). `!Select [0, !GetAZs '']` would make the template portable across regions, which ADR 0003 claims as a goal.
- The S3 gateway endpoint planned for M2 is a network resource. By ADR 0002's own rule, it belongs in the network stack, not the app stack.
