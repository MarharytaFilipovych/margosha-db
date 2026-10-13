# ADR 0002: Stack layout

## Context

We need to decide how to split our infrastructure into CloudFormation stacks.
In M1 we only have network resources (VPC, subnets, Internet Gateway, route tables).
From M2 on we will also have the application (coordinator and shards), which will change much more often during development than the network.

The project runs in two environments:

- **M1: Cloud Architecting Sandbox.** All resources are deleted at the end of each 3-hour session, so everything is redeployed from scratch every session.
- **M2 and later: Learner Lab.** Resources are kept between sessions, so the network stays deployed and mostly the application changes.

## Alternatives considered

- **One stack for everything (network + application)**: simplest to deploy and tear down, just one stack. 
   But every app change would update the same stack as the network, and if an app update fails, the rollback happens in the stack that also holds the network.
   Rejected because we expect a lot of app changes during development.
- **Separate stacks, with the app stack using hardcoded VPC and subnet IDs**: rejected. 
   The IDs change every time the network is recreated: every session in the sandbox, and whenever we tear down and recreate the network 
   in the Learner Lab (for example in the recreatability test). Hardcoded IDs would break each time.
- **Separate stacks, with the app stack importing the network stack's outputs**:
   the network stack exports its IDs (`Outputs` with `Export`) and the app stack reads them with `!ImportValue`. The IDs are always the current ones, even after recreation.

## Decision

Two separate stacks:

- **Network stack** (`margosha-vpc`): VPC, Internet Gateway, public subnet, two private subnets, route tables. It exports the IDs that the application will need.
- **Application stack**: added in M2. It imports the network IDs with `!ImportValue`.

In M1 only the network stack exists.

## Consequences

- App updates can't break the network. A failed app deployment rolls back only the app stack.
- During the development we can redeploy just the app stack without touching the VPC. 
  In the Learner Lab this is the normal case: the network stack stays deployed, and the CI/CD pipeline mostly updates the app stack.
- Deploy order matters: network stack first, then app stack, because the app stack depends on the network's exports.
  In the sandbox this two-step deploy happens every session; in the Learner Lab only when the network is (re)created. The CI/CD pipeline can run both steps in the right order.
- Teardown order also matters: app stack first, then network stack. CloudFormation refuses to delete a stack whose exports are still imported by another stack.
- An export can't be changed or removed while another stack imports it, so changing the network's outputs later means updating the app stack first.
- Inside the network stack, all resources reference each other with `!Ref` instead of hardcoded IDs, so the template always deploys cleanly into an empty account.