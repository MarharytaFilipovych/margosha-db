# ADR 0003: CIDR addressing scheme

## Context

We need to choose IP ranges for the VPC and its subnets.
The ranges must not overlap with each other or with other networks we might connect to, 
and they need to be big enough for the project to grow without readdressing later.

We need one public subnet and two private subnets. The private subnets are located in two different availability zones for availability.

## Alternatives considered

- **Smaller VPC CIDR (like `/20`)**: address space inside a VPC is free, so a smaller range doesn't save anything. It only limits how many subnets we can add later -> **rejected**.
- **Larger subnets (like `/23`)**: `/24` already gives far more addresses than this project will use -> **rejected**.
- **One private subnet instead of two**: simpler, but everything private would be in one AZ, and an outage of that AZ would take it all down -> **rejected**.
- **Both private subnets in the same AZ**: same problem, no protection against an AZ failure -> **rejected**.
- **Separate route table for each private subnet**: would only make sense if the two private subnets needed different routing later (e.g. one gets a NAT Gateway). Right now both are local-only -> **rejected**.

## Decision

| Resource | CIDR | Size | AZ |
|---|---|---|---|
| VPC | `10.0.0.0/16` | 65,536 addresses | — |
| Public subnet | `10.0.0.0/24` | 256 addresses | us-east-1a |
| Private subnet A | `10.0.1.0/24` | 256 addresses | us-east-1a |
| Private subnet B | `10.0.2.0/24` | 256 addresses | us-east-1b |

The public subnet has a route table with `0.0.0.0/0` -> Internet Gateway.
The two private subnets share one route table with no internet route (local traffic only).

## Consequences

- `/16` is the largest CIDR block AWS allows for a VPC, so we have as much room as possible. A `/16` holds 256 `/24` subnets; we use 3, so 253 are left for later (databases, more tiers, etc.).
- `10.0.0.0/16` doesn't overlap with the default VPC, which uses `172.31.0.0/16`.
- `/24` per subnet is easy to work with: 251 usable addresses, because AWS reserves 5 addresses in every subnet.
- Subnets are allocated one after another (`.0`, `.1`, `.2`), so the next one would simply be `10.0.3.0/24`.
- The two private subnets are in different AZs, so anything spread across them survives a single-AZ outage. This costs nothing extra at the network level.
- Both private subnets share one route table since their routing is the same.
- Known limitation: the public subnet exists only in `us-east-1a`, so anything placed in it has no AZ redundancy. In M1 nothing in the public subnet needs high availability, so we accept this for now.
- The CIDRs and the AZs are template parameters with default values, not hardcoded, so the same template can be deployed with different ranges or in another region without editing the resources.