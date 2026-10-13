# ADR 0002: Stack layout

## Context

We need to decide how to split our infrastructure into CloudFormation stacks. 
In M1 we only have network resources (VPC, subnets, Internet Gateway, route tables). 
From M2 on we will also have the application (coordinator and shards), which will change much more often during development than the network.

There is also a sandbox limitation: the session lasts 3 hours and all resources are deleted when it ends, so we redeploy everything from scratch every session.

## Alternatives considered

- **One stack for everything (network + application)**: simplest to deploy and tear down, just one stack. 
    However, every app change would update the same stack as the network, and if an app update fails, the rollback happens in the stack that also holds the network. 
    Since we expect a lot of app changes during development -> **rejected**.
- **Separate stacks, with the app stack using hardcoded VPC and subnet IDs**: the IDs change every time the network is recreated, which in our sandbox means every session, so hardcoded IDs would break all the time -> **rejected**.
- **Separate stacks, with the app stack importing the network stack's outputs**: the network stack exports its IDs (`Outputs` with `Export`) and the app stack reads them with `!ImportValue`. The IDs are always the current ones, even after recreation.

## Decision

Two separate stacks:

- **Network stack** (`margosha-vpc`): VPC, Internet Gateway, public subnet, two private subnets, route tables. It exports the IDs that the application will need.
- **Application stack**: will be added in M2. It imports the network IDs with `!ImportValue`.

In M1 only the network stack exists.

## Consequences

- App updates can't break the network. A failed app deployment rolls back only the app stack.
- During the development we can redeploy just the app stack without touching the VPC.
- Deploy order matters: network stack first, then app stack, because the app stack depends on the network's exports.
- Teardown order also matters: app stack first, then network stack. CloudFormation refuses to delete a stack whose exports are still imported by another stack.
- Because of the 3-hour limitation, we do this two-step redeploy every session. It's one extra command, and in M2 the CI/CD pipeline can run both steps in the right order.
- Inside the network stack, all resources reference each other with `!Ref` instead of hardcoded IDs, so the template always deploys cleanly into an empty account.