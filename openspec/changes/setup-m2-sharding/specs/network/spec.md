# Spec Delta

## ADDED Requirements

### Requirement: Private subnets reach S3 through a gateway endpoint
The network stack SHALL attach an S3 gateway VPC endpoint to the private route table, so instances in the private subnets can reach S3 without internet access. Its policy SHALL allow reading the Amazon Linux 2023 package repositories and the project's artifacts bucket.

#### Scenario: Shard installs packages
- **WHEN** an instance in a private subnet runs `dnf install`
- **THEN** the packages are downloaded through the S3 gateway endpoint

#### Scenario: Private subnets still have no internet
- **WHEN** the network is deployed with the endpoint
- **THEN** the private route table has the local route and the S3 prefix-list route, and no route to the Internet Gateway
