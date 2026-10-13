## ADDED Requirements

### Requirement: Milestone 2 service quota review
The project SHALL review the service quotas needed in Milestone 2 and write down the ones that can limit it, performing the review separately in the AWS Academy sandbox and in the AWS Learner Lab environment.

#### Scenario: Limiting quotas are recorded for the AWS Academy sandbox
- **WHEN** a reviewer opens the quota review
- **THEN** it lists the service quotas that can limit Milestone 2 in the AWS Academy sandbox

#### Scenario: Limiting quotas are recorded for the AWS Learner Lab environment
- **WHEN** a reviewer opens the quota review
- **THEN** it lists the service quotas that can limit Milestone 2 in the AWS Learner Lab environment

### Requirement: Milestone 2 budget
The project SHALL calculate the monthly budget for Milestone 2, assuming only 2 shards, and compare it against the sandbox credit ceiling.

#### Scenario: Budget is compared to the ceiling
- **WHEN** a reviewer opens the budget
- **THEN** it shows the monthly cost for 2 shards and how it compares to the sandbox credit ceiling

### Requirement: Recreatability
The project SHALL prove recreatability by tearing the whole stack down and bringing it back from IaC alone, recording how long it took and what broke (or did not) on the first attempt.

#### Scenario: Stack is recreated from IaC
- **WHEN** the stack is torn down and redeployed from IaC alone
- **THEN** the stack is restored and the record states the time taken and what broke on the first attempt (if sth)