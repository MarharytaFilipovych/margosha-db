# infra-operations Specification

## Purpose
TBD - created by archiving change setup-m1-infrastructure. Update Purpose after archive.

## Requirements

### Requirement: Milestone 2 service quota review
The project SHALL review the service quotas needed in Milestone 2 in the AWS Academy Learner Lab, since M2 runs there, and write down the ones that can limit it.

#### Scenario: Limiting quotas are recorded for the Learner Lab
- **WHEN** a reviewer opens the quota review
- **THEN** it lists the service quotas that can limit Milestone 2 in the Learner Lab

### Requirement: Milestone 2 budget
The project SHALL calculate the monthly budget for Milestone 2 in the AWS Academy Learner Lab, assuming only 2 shards running 24/7 (730 hours a month), and compare it against the budget shown on the Learner Lab page.

#### Scenario: Budget is compared to the Learner Lab budget
- **WHEN** a reviewer opens the budget
- **THEN** it shows the monthly cost for 2 shards running 24/7 and how it compares to the remaining budget on the Learner Lab page

### Requirement: Recreatability
The project SHALL prove recreatability by tearing the whole stack down and bringing it back from IaC alone, recording how long it took and what broke (or did not) on the first attempt.

#### Scenario: Stack is recreated from IaC
- **WHEN** the stack is torn down and redeployed from IaC alone
- **THEN** the stack is restored and the record states the time taken and what broke on the first attempt (if sth)
