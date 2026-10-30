# Spec Delta

## MODIFIED Requirements

### Requirement: Application defined in IaC
The artifacts bucket, the coordinator, the shard replicas, their security groups and the YCSB client SHALL be defined in a CloudFormation template in the repository. The application stack SHALL import the network IDs from the `margosha-vpc` stack exports instead of hardcoding them.

#### Scenario: Deploy from templates alone
- **WHEN** the application stack is deployed into a Learner Lab account where only `margosha-vpc` exists, and the jar is uploaded
- **THEN** the coordinator and all 6 replicas come up, every replica registers, and the store accepts requests, with no manual console steps

### Requirement: Placement of nodes
The coordinator SHALL run in the public subnet. Each shard's 3 replicas SHALL run in the private subnets, with at least one replica of every shard in each private subnet (`us-east-1a` and `us-east-1b`); the two shards' leaders SHALL be in different private subnets. No replica SHALL have a public IP address.

#### Scenario: Shards are private
- **WHEN** the application stack is deployed
- **THEN** every replica is in private subnet A or B with no public IPv4 address, each shard has replicas in both subnets, and the leaders of shard A and shard B are in different subnets

### Requirement: Network access between nodes
Replicas SHALL accept API traffic only from the coordinator's security group and from the other replicas, and SSH only from the coordinator's security group. The coordinator SHALL accept API traffic from inside the VPC and from an operator CIDR given as a parameter, and SSH only from that operator CIDR.

#### Scenario: Shard not reachable from the client
- **WHEN** the YCSB client tries to connect to a replica's API port directly
- **THEN** the connection is refused or times out

#### Scenario: Coordinator reachable from the client
- **WHEN** the YCSB client connects to the coordinator's private address on the API port
- **THEN** the connection succeeds

#### Scenario: Replicas of a shard reach each other
- **WHEN** a follower and its leader connect to each other's private address on the API port for replication
- **THEN** the connection succeeds

### Requirement: Coordinator address for shards
Each replica SHALL learn the coordinator's private address from the stack, and each follower its leader's private address, so they can register and replicate without manual configuration. These private addresses SHALL stay the same when the Learner Lab stops and starts the instances.

#### Scenario: Lab session restart
- **WHEN** a Learner Lab session ends and a new one starts the instances again
- **THEN** every replica registers with the coordinator again and every follower resumes replicating from its leader, with no change to the template
