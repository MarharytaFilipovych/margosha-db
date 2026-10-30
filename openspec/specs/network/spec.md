# network Specification

## Purpose
TBD - created by archiving change setup-m1-infrastructure. Update Purpose after archive.

## Requirements

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

### Requirement: Network stack exports
The network stack SHALL export the VPC ID and the subnet IDs so other stacks can use them.

#### Scenario: IDs are exported
- **WHEN** the network stack is deployed
- **THEN** the VPC ID and the subnet IDs are available as stack outputs that another stack can import

### Requirement: Private subnets reach S3 through a gateway endpoint
The network stack SHALL attach an S3 gateway VPC endpoint to the private route table, so instances in the private subnets can reach S3 without internet access. Its policy SHALL allow reading the Amazon Linux 2023 package repositories and the project's artifacts bucket.

#### Scenario: Shard installs packages
- **WHEN** an instance in a private subnet runs `dnf install`
- **THEN** the packages are downloaded through the S3 gateway endpoint

#### Scenario: Private subnets still have no internet
- **WHEN** the network is deployed with the endpoint
- **THEN** the private route table has the local route and the S3 prefix-list route, and no route to the Internet Gateway
