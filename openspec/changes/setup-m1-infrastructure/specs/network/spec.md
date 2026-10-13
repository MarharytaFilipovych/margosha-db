## ADDED Requirements

### Requirement: VPC with public and private subnets
The project SHALL create a VPC for its resources containing public and private subnets. 
The public subnet SHALL have an Internet Gateway. Private subnets SHALL be for internal access only.

#### Scenario: Public subnet reaches the internet
- **WHEN** the network is deployed
- **THEN** the public subnet has a route to the Internet Gateway

#### Scenario: Private subnets are internal only
- **WHEN** the network is deployed
- **THEN** the private subnets have no route to the Internet Gateway

### Requirement: Network IaC stack
The network resources SHALL be held in an IaC stack.

#### Scenario: Network deployed from IaC
- **WHEN** the stack is deployed
- **THEN** the VPC, subnets and Internet Gateway are created from the IaC definition alone