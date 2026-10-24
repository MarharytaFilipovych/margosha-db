# Margosha DB

A "light" version of a serverless key-value store like DynamoDB, built as the capstone project for the Cloud Architectures course (2026/27).

- **Decisions:** [adr/](adr/)
- **OpenSpec change:** [openspec/changes/setup-m1-infrastructure/](openspec/changes/setup-m1-infrastructure/)
- **IaC templates (AWS CloudFormation):** [cfn/](cfn/)

---

# Milestone 1: Infrastructure setup

**Environment:** AWS Academy Cloud Architecting Sandbox, region `us-east-1`, session role `voclabs`.

## What's in M1

| Task | Document | Template |
|---|---|---|
| 1. Dissect the LabRole | [role_dissection.md](m1/role_dissection.md) | — |
| 2–3. Read-only policy + assumable role (described in IaC, not deployable in the sandbox) | [role_policy_creation.md](m1/role_policy_creation.md) | [read-only-role.yaml](cfn/read-only-role.yaml) |
| 4–5. VPC with public and private subnets, in an IaC stack | [vpc_creation.md](m1/vpc_creation.md) | [margosha-vpc-stack.yaml](cfn/margosha-vpc-stack.yaml) |
| 6–7. Service quotas and budget for M2 | [quotas_and_budget.md](m1/quotas_and_budget.md) | — |
| 8. Recreatability | [recreatability_test.md](m1/recreatability_test.md) | [margosha-vpc-stack.yaml](cfn/margosha-vpc-stack.yaml) |

ADRs: [0001 IaC tool](adr/0001-iac-tool.md), [0002 stack layout](adr/0002-stack-layout.md), [0003 CIDR addressing](adr/0003-cidr-addressing.md).

## How to bring it up

The only deployable piece in M1 is the network stack `margosha-vpc`. The read-only policy and role template can't be deployed in the sandbox (`iam:CreatePolicy` and `iam:CreateRole` are denied), see [role_policy_creation.md](m1/role_policy_creation.md).

**1. Credentials and region.** Paste the AWS CLI credentials from the lab page into `~/.aws/credentials` and set the region (details in [vpc_creation.md](m1/vpc_creation.md#setup-default-region-and-credentials)):
```bash
export AWS_DEFAULT_REGION=us-east-1
aws sts get-caller-identity   # must return assumed-role/voclabs/...
```

**2. Deploy** (from the `cfn/` folder):
```bash
cd cfn
aws cloudformation validate-template --template-body file://margosha-vpc-stack.yaml
aws cloudformation create-stack \
  --stack-name margosha-vpc \
  --template-body file://margosha-vpc-stack.yaml
aws cloudformation wait stack-create-complete --stack-name margosha-vpc
```
It takes about a minute. The sandbox deletes everything when the 3-hour session ends, so this is repeated in every new session.

## How to verify it

**Stack status** should be `CREATE_COMPLETE`:
```bash
aws cloudformation describe-stacks --stack-name margosha-vpc --query 'Stacks[0].StackStatus'
```

**Public subnet reaches the internet:** its route table has a `0.0.0.0/0` route to the Internet Gateway (`igw-...`):
```bash
aws ec2 describe-route-tables \
  --filters "Name=tag:Name,Values=margosha-rt-public" \
  --query "RouteTables[0].Routes"
```

**Private subnets are internal only:** their route table has only the `local` route (`10.0.0.0/16`):
```bash
aws ec2 describe-route-tables \
  --filters "Name=tag:Name,Values=margosha-rt-private" \
  --query "RouteTables[0].Routes"
```

**Exports for the M2 application stack:** 4 exports, `margosha-vpc-VpcId`, `-PublicSubnetId`, `-PrivateSubnetAId` and `-PrivateSubnetBId`:
```bash
aws cloudformation list-exports --query "Exports[?starts_with(Name, 'margosha-vpc-')]"
```

Expected outputs and console screenshots are in [vpc_creation.md](m1/vpc_creation.md#verification).

**Tear down** (used in the [recreatability test](m1/recreatability_test.md)):
```bash
aws cloudformation delete-stack --stack-name margosha-vpc
aws cloudformation wait stack-delete-complete --stack-name margosha-vpc
```

## What changed since the previous milestone

M1 is the first milestone, so there is no previous one. Everything listed in [What's in M1](#whats-in-m1) is new.