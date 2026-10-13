## ADDED Requirements

### Requirement: LabRole analysis
The project SHALL document what the LabRole allows, what it denies, and how this was found out (console, CLI, policy simulator), reviewing LabRole separately in the AWS Academy sandbox (operated under the `voclabs` role) and in the AWS Learner Lab environment.

#### Scenario: LabRole is documented for the AWS Academy sandbox
- **WHEN** a reviewer opens the LabRole analysis
- **THEN** it lists what LabRole allows, what it denies, and the method used to find out each, for the AWS Academy sandbox

#### Scenario: LabRole is documented for the AWS Learner Lab environment
- **WHEN** a reviewer opens the LabRole analysis
- **THEN** it lists what LabRole allows, what it denies, and the method used to find out each, for the AWS Learner Lab environment

### Requirement: Read-only IAM policy in IaC
The project SHALL provide an IaC template for an IAM policy that allows ONLY viewing resources and grants no write access. 
The policy is not attached in the sandbox; the project SHALL explain what it would do.

#### Scenario: Policy grants no write access
- **WHEN** a reviewer reads the policy template
- **THEN** it contains only view/read actions and no write actions

#### Scenario: Policy is explained
- **WHEN** a reviewer reads the accompanying explanation
- **THEN** it states what the policy would do if attached

### Requirement: Assumable role in IaC
The project SHALL provide an IaC template for a Role that has the read-only policy attached and could be assumed by another principal. The project SHALL explain why the sandbox refuses it.

#### Scenario: Role is assumable and carries the policy
- **WHEN** a reviewer reads the role template
- **THEN** it attaches the read-only policy and defines a trust policy allowing another principal to assume it

#### Scenario: Sandbox refusal is explained
- **WHEN** a reviewer reads the accompanying explanation
- **THEN** it states why the sandbox refuses to create the role