# Spec Delta

## Purpose

Describes the application's infrastructure as code: where the coordinator, the shards and the benchmark client run, how they reach each other, and how the application gets onto them in the AWS Academy Learner Lab.

## ADDED Requirements

### Requirement: Application defined in IaC
The artifacts bucket, the coordinator, the shards, their security groups and the YCSB client SHALL be defined in a CloudFormation template in the repository. The application stack SHALL import the network IDs from the `margosha-vpc` stack exports instead of hardcoding them.

#### Scenario: Deploy from templates alone
- **WHEN** the application stack is deployed into a Learner Lab account where only `margosha-vpc` exists, and the jar is uploaded
- **THEN** the coordinator and both shards come up, the shards register, and the store accepts requests, with no manual console steps

### Requirement: Placement of nodes
The coordinator SHALL run in the public subnet. The two shards SHALL run one in each private subnet (`us-east-1a` and `us-east-1b`) and SHALL have no public IP address.

#### Scenario: Shards are private
- **WHEN** the application stack is deployed
- **THEN** shard A is in private subnet A, shard B is in private subnet B, and neither has a public IPv4 address

### Requirement: Instance settings within the Learner Lab limits
All instances SHALL use `LabInstanceProfile`, Amazon Linux 2023, an 8 GiB gp3 root volume and T3 CPU credits in standard mode. The coordinator and the shards SHALL be `t3.micro`; the YCSB client SHALL be `t3.small`.

#### Scenario: Standard credits
- **WHEN** a reviewer inspects any deployed instance
- **THEN** its credit specification is `standard`, its instance profile is `LabInstanceProfile`, and its type matches its role

### Requirement: Network access between nodes
Shards SHALL accept API traffic only from the coordinator's security group, and SSH only from the coordinator's security group. The coordinator SHALL accept API traffic from inside the VPC and from an operator CIDR given as a parameter, and SSH only from that operator CIDR.

#### Scenario: Shard not reachable from the client
- **WHEN** the YCSB client tries to connect to a shard's API port directly
- **THEN** the connection is refused or times out

#### Scenario: Coordinator reachable from the client
- **WHEN** the YCSB client connects to the coordinator's private address on the API port
- **THEN** the connection succeeds

### Requirement: Application delivered through S3
The application SHALL be published as a jar in the artifacts bucket. Each node SHALL download the jar from the bucket every time it boots, and SHALL retry until the jar is there.

#### Scenario: New version deployed
- **WHEN** a new jar is uploaded to the artifacts bucket and the nodes are rebooted
- **THEN** every node runs the new jar

### Requirement: Coordinator address for shards
Each shard SHALL learn the coordinator's private address from the stack, so it can register without manual configuration. The coordinator's private address SHALL stay the same when the Learner Lab stops and starts the instances.

#### Scenario: Lab session restart
- **WHEN** a Learner Lab session ends and a new one starts the instances again
- **THEN** both shards register with the coordinator again with no change to the template

### Requirement: Benchmark client only while benchmarking
The YCSB client SHALL be created in the public subnet only when the application stack's `BenchClient` parameter is `true`, so it exists only while benchmarking.

#### Scenario: Client removed after a benchmark
- **WHEN** `BenchClient` is set back to `false`
- **THEN** the YCSB client instance and its volume are gone, and the coordinator and the shards are not replaced
