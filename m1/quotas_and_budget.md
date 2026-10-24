# Milestone 2: service quotas and budget

**Environment:** AWS Academy Learner Lab, region `us-east-1` (M2 runs there, see [proposal](../openspec/changes/setup-m1-infrastructure/proposal.md)).
**Budget on the Learner Lab page at the time of calculation (2026-10-24):** \$0 used of \$50.
**The \$50 is for all milestones (M2–M5), not per month.** If it is exceeded, the lab account is disabled and all resources are lost (Learner Lab readme).

This document has three parts:

1. What M2 will run: the basis for both the quotas and the budget.
2. Service quotas that can limit M2.
3. Monthly budget for M2 and comparison with the \$50.

---

## 1. What M2 will run

M2 needs a coordinator, 2 shards (task: "assuming there will be only 2 shards") and a YCSB benchmark.

| Component | Count | Subnet | Instance type | Root volume | Public IPv4 | Running hours / month |
|---|---|---|---|---|---|---|
| Coordinator | 1 | public (`10.0.0.0/24`, us-east-1a) | `t3.micro` (2 vCPU, 1 GiB) | 8 GiB gp3 | yes | 730 (24/7) |
| Shard | 2 | private A (`10.0.1.0/24`, us-east-1a), private B (`10.0.2.0/24`, us-east-1b) | `t3.micro` (2 vCPU, 1 GiB) | 8 GiB gp3 each | no | 730 (24/7) |
| YCSB client | 1 | public | `t3.small` (2 vCPU, 2 GiB) | 8 GiB gp3, deleted when the instance is terminated after each benchmark | yes | ~10 (only while benchmarking) |
| S3 gateway VPC endpoint | 1 | route table of the private subnets | — | — | — | free |
| CI/CD: GitHub Actions | — | runs on GitHub-hosted runners, outside AWS | — | — | — | free (public repository) |

All instances: Amazon Linux 2023 AMI, `LabInstanceProfile` (I can't create roles, see [role dissection](role_dissection.md)), CPU credits in **standard** mode.

### Why these choices

**`t3.micro` for the coordinator and the shards.**
- `t3.small` for all three would cost \$51.49 a month at 24/7. That is more than the whole \$50 (see part 3).
- `t3.nano` (0.5 GiB) leaves very little memory once the OS is running.
- `t3.micro` is the smallest size with some memory headroom. It is allowed by the lab ("nano, micro, small, medium, and large").
- If M2 shows the app needs more memory, this is revisited in M2.

**`t3.small` for the YCSB client.** YCSB runs on Java and only during benchmarks, so a bigger size costs almost nothing here (~10 h).

**8 GiB gp3 root volume, no extra data volume.**
- Size: one YCSB record is about 1 KB with default settings (10 fields × 100 bytes). Even 1,000,000 records are about 1 GB, split across 2 shards, so the root volume is enough and no separate data volume is needed.
- 8 GiB is the size of the AMI's root volume (confirmed with the command in part 2.2). A volume can't be smaller than the AMI snapshot, so 8 GiB is also the minimum.
- Type: gp3 is cheaper than gp2 (\$0.08 vs \$0.10 per GB-month) and includes 3,000 IOPS for free. It is one of the types the lab allows (gp2, gp3, sc1, standard). It is also the AL2023 default.

**CPU credits in standard mode.**
- T3 instances start in **unlimited** mode by default. Sustained CPU above the baseline (10% per vCPU for `t3.micro`) is then charged at \$0.05 per vCPU-hour.
- A benchmark keeps the CPU high. The worst case for 3 instances × 2 vCPU × 730 h is \$219, which would end the lab.
- In standard mode the instance is slowed down to the baseline when its credits run out, instead of being charged.
- Trade-off: a long benchmark can be throttled, so the CPU credit balance must be checked when the YCSB numbers are recorded.

**GitHub Actions for CI/CD (M2 task 6).**
- The repository is public, and GitHub Actions is free for public repositories on standard GitHub-hosted runners. The pipeline runs outside AWS, so it uses no EC2 hours.
- AWS side of a deploy:
    - CloudFormation has no extra charge for AWS resource types.
    - Data uploaded into AWS is free.
    - The build artifact kept in S3 for the shards is a few MB, under \$0.01 a month.
- If the repository becomes private, GitHub Free still includes 2,000 Actions minutes a month.
- Not a cost but a constraint: in the Learner Lab I can't create IAM users or roles, so GitHub can't get its own AWS identity (no access keys of its own, no OIDC role). The workflow has to use the lab session credentials (`aws_access_key_id`, `aws_secret_access_key`, `aws_session_token` from **AWS Details**) stored as repository secrets. They expire with every session and must be updated before each pipeline run.

### How instances reach what they need (network option)

The private subnets have no internet route ([ADR 0003](../adr/0003-cidr-addressing.md)). The shards still need to install packages and get the application.

| Option | Monthly cost (730 h) | Verdict |
|---|---|---|
| NAT Gateway in the public subnet | \$32.85 + \$0.045/GB | Rejected: 66% of the whole \$50 for one component |
| Interface endpoints for Session Manager (`ssm`, `ssmmessages`, `ec2messages`) | \$43.80 in 2 AZs, \$21.90 in 1 AZ | Rejected: too expensive |
| All instances in the public subnet with public IPv4 | \$7.30 more than the chosen option | Rejected: shards would be reachable from the internet (only security groups would protect them), which defeats the private subnets |
| **Coordinator in the public subnet, shards in private subnets + S3 gateway endpoint** | **\$3.65** (coordinator public IPv4 only) | **Chosen** |

Why the chosen option works:
- **Gateway endpoints for S3 are free** (no hourly or data processing charge).
- AL2023 package repositories are stored in S3, so the shards can run `dnf` through the S3 gateway endpoint without internet. The endpoint policy must allow `s3:GetObject` on `arn:aws:s3:::al2023-repos-us-east-1-de612dc2/*`.
- The application can also be delivered to the shards through S3.

Consequences:
- **Session Manager does not work on the shards** (no route to SSM). I connect to them with SSH through the coordinator as a jump host (`vockey`).
- **Session Manager works on the coordinator** through its public IP.
- Anything outside AWS (e.g. Docker Hub, EPEL) is not reachable from the shards.

---

## 2. Service quotas that can limit M2

### 2.1 Learner Lab limits (from the Learner Lab readme and the Learner Lab page)

These are enforced by the lab, not by AWS Service Quotas, so they don't appear in the Service Quotas console. They are stricter than the AWS defaults.

| Limit | Value | M2 uses | Can limit M2? |
|---|---|---|---|
| Regions | `us-east-1` and `us-west-2` only | `us-east-1` | No |
| EC2 instance sizes | nano, micro, small, medium, large | micro, small | No, but rules out anything bigger than large |
| Purchase option | On-Demand only | On-Demand | No, but Spot (cheaper) is not available |
| Concurrently running EC2 instances | max 9, counted across all services that create instances | 4 | No (5 left). **In M3** (3+ replicas per shard): 2 × 3 shard nodes + coordinator + YCSB client = 8, so it gets close |
| vCPUs of running instances | max 32 | 4 × 2 = 8 | No |
| 20 or more running instances | account deactivated, everything deleted | — | Must never happen (e.g. a misconfigured Auto Scaling group in M5) |
| EBS volume size | max 100 GB | 8 GiB | No |
| EBS volume types | gp2, gp3, sc1, standard; no Provisioned IOPS | gp3 | No |
| IAM roles | can't create roles; use `LabRole` / `LabInstanceProfile` | `LabInstanceProfile` | Yes: every instance must use `LabInstanceProfile` |
| Session length | 4 hours by default; pressing **Start Lab** again resets the timer | — | No, but see the next row |
| End of session | EC2 instances are shut down automatically; other resources (e.g. RDS, Elastic Load Balancer, NAT Gateway) keep running and keep charging between sessions | coordinator, shards, YCSB client (EC2) | No for M2: everything in M2 is EC2 or free. A load balancer or NAT Gateway added later would charge between sessions |
| Budget | \$50 total for the whole course; budget shown on the lab page updates only every 8–12 h | see part 3 | **Yes: the main limit** |

### 2.2 AWS Service Quotas

Run the commands from the repository root. They save the full lists to `m1/command_outputs/quotas_and_budget/` and print only the quotas that matter for M2.

```bash
mkdir -p m1/command_outputs/quotas_and_budget
```

**EC2: running On-Demand vCPUs (T instances count here)**
```bash
aws service-quotas list-service-quotas --service-code ec2 --region us-east-1 \
  > m1/command_outputs/quotas_and_budget/ec2-quotas.json

aws service-quotas list-service-quotas --service-code ec2 --region us-east-1 \
  --query "Quotas[?contains(QuotaName, 'Running On-Demand Standard')].[QuotaName,QuotaCode,Value]" \
  --output table
```
```text
---------------------------------------------------------------------------------------------
|                                     ListServiceQuotas                                     |
+-------------------------------------------------------------------+-------------+---------+
|  Running On-Demand Standard (A, C, D, H, I, M, R, T, Z) instances |  L-1216C47A |  256.0  |
+-------------------------------------------------------------------+-------------+---------+
```
Full list: [ec2-quotas.json](command_outputs/quotas_and_budget/ec2-quotas.json)

**VPC: VPCs, subnets, Internet Gateways, gateway endpoints, security group rules**
```bash
aws service-quotas list-service-quotas --service-code vpc --region us-east-1 \
  > m1/command_outputs/quotas_and_budget/vpc-quotas.json

aws service-quotas list-service-quotas --service-code vpc --region us-east-1 \
  --query "Quotas[?contains(QuotaName, 'VPCs per Region') || contains(QuotaName, 'Subnets per VPC') || contains(QuotaName, 'Internet gateways per Region') || contains(QuotaName, 'Gateway VPC endpoints per Region') || contains(QuotaName, 'rules per security group')].[QuotaName,QuotaCode,Value]" \
  --output table
```
```text
-------------------------------------------------------------------------
|                           ListServiceQuotas                           |
+-----------------------------------------------+-------------+---------+
|  Gateway VPC endpoints per Region             |  L-1B52E74A |  20.0   |
|  Inbound or outbound rules per security group |  L-0EA8095F |  60.0   |
|  Internet gateways per Region                 |  L-A4707A72 |  5.0    |
|  Subnets per VPC                              |  L-407747CB |  200.0  |
|  VPCs per Region                              |  L-F678F1CE |  5.0    |
+-----------------------------------------------+-------------+---------+
```
Full list: [vpc-quotas.json](command_outputs/quotas_and_budget/vpc-quotas.json)

**EBS: gp3 storage**
```bash
aws service-quotas list-service-quotas --service-code ebs --region us-east-1 \
  > m1/command_outputs/quotas_and_budget/ebs-quotas.json

aws service-quotas list-service-quotas --service-code ebs --region us-east-1 \
  --query "Quotas[?contains(QuotaName, 'gp3')].[QuotaName,QuotaCode,Value]" \
  --output table
```
```text
------------------------------------------------------------------------------------------------
|                                       ListServiceQuotas                                      |
+-----------------------------------------------------------------------+-------------+--------+
|  Storage modifications for General Purpose SSD (gp3) volumes, in TiB  |  L-59C8FC87 |  500.0 |
|  Storage for General Purpose SSD (gp3) volumes, in TiB                |  L-7A658B76 |  50.0  |
+-----------------------------------------------------------------------+-------------+--------+
```
Full list: [ebs-quotas.json](command_outputs/quotas_and_budget/ebs-quotas.json)

The first row limits volume modifications (resizing or changing type), which M2 doesn't do. The second row is the one M2 uses.

**S3: number of buckets (for the build artifacts)**
```bash
aws service-quotas list-service-quotas --service-code s3 --region us-east-1 \
  > m1/command_outputs/quotas_and_budget/s3-quotas.json

aws service-quotas list-service-quotas --service-code s3 --region us-east-1 \
  --query "Quotas[?contains(QuotaName, 'bucket')].[QuotaName,QuotaCode,Value]" \
  --output table
```
```text
------------------------------------------------------
|                  ListServiceQuotas                 |
+--------------------------+--------------+----------+
|  General purpose buckets |  L-DC2B2D3D  |  10000.0 |
+--------------------------+--------------+----------+
```
Full list: [s3-quotas.json](command_outputs/quotas_and_budget/s3-quotas.json). This quota is per account, not per region (`"GlobalQuota": true` in the JSON).

**CloudFormation: number of stacks**
```bash
aws service-quotas list-service-quotas --service-code cloudformation --region us-east-1 \
  > m1/command_outputs/quotas_and_budget/cloudformation-quotas.json

aws service-quotas list-service-quotas --service-code cloudformation --region us-east-1 \
  --query "Quotas[?QuotaName=='Stacks'].[QuotaName,QuotaCode,Value]" \
  --output table
```
```text
------------------------------------
|         ListServiceQuotas        |
+--------+--------------+----------+
|  Stacks|  L-0485CB21  |  2000.0  |
+--------+--------------+----------+
```
Full list: [cloudformation-quotas.json](command_outputs/quotas_and_budget/cloudformation-quotas.json)

**AMI root volume size and type (for the 8 GiB gp3 in part 1)**
```bash
aws ec2 describe-images --owners amazon --region us-east-1 \
  --filters "Name=name,Values=al2023-ami-2023*-x86_64" "Name=state,Values=available" \
  --query 'sort_by(Images, &CreationDate)[-1].[ImageId,Name,BlockDeviceMappings[0].Ebs.VolumeSize,BlockDeviceMappings[0].Ebs.VolumeType]' \
  --output table
```
```text
-----------------------------------------------------
|                  DescribeImages                   |
+---------------------------------------------------+
|  ami-0d6e34c14757a048b                            |
|  al2023-ami-2023.12.20261021.0-kernel-6.1-x86_64  |
|  8                                                |
|  gp3                                              |
+---------------------------------------------------+
```
The latest Amazon Linux 2023 AMI has an 8 GiB gp3 root volume, which confirms the root volume in part 1.

### 2.3 Result

| Quota | Code | Value | M2 needs | Can limit M2? |
|---|---|---|---|---|
| Running On-Demand Standard (A, C, D, H, I, M, R, T, Z) instances (vCPUs) | `L-1216C47A` | 256 | 8 | No. The Learner Lab limit (32 vCPUs and 9 instances, see 2.1) is much stricter and is reached first |
| VPCs per Region | `L-F678F1CE` | 5 | 2 (default VPC + `margosha-vpc`) | No (3 left) |
| Subnets per VPC | `L-407747CB` | 200 | 3 | No |
| Internet gateways per Region | `L-A4707A72` | 5 | 2 (default VPC + `margosha-vpc`) | No (3 left) |
| Gateway VPC endpoints per Region | `L-1B52E74A` | 20 | 1 (S3) | No |
| Inbound or outbound rules per security group | `L-0EA8095F` | 60 | a few per group | No |
| Storage for gp3 volumes (TiB) | `L-7A658B76` | 50 | 0.03 (4 × 8 GiB) | No. The Learner Lab limit (100 GB per volume, see 2.1) is stricter, and M2 uses 8 GiB per volume |
| General purpose buckets (S3, per account) | `L-DC2B2D3D` | 10000 | 1 (build artifacts) | No |
| Stacks (CloudFormation) | `L-0485CB21` | 2000 | 2 (`margosha-vpc` + application stack) | No |

**Conclusion:** none of the AWS Service Quotas can limit M2; each has plenty of room. What can limit M2 are the Learner Lab limits in 2.1, mainly the \$50 budget and the maximum of 9 running instances (which gets close in M3).

---

## 3. Monthly budget for M2

### 3.1 Prices (us-east-1, On-Demand, Linux)

| Item | Price |
|---|---|
| `t3.micro` | \$0.0104 / h |
| `t3.small` | \$0.0208 / h |
| EBS gp3 | \$0.08 / GB-month (charged also while the instance is stopped) |
| Public IPv4 address | \$0.005 / h (an auto-assigned IP is released when the instance stops, so it is charged only while running) |
| S3 gateway VPC endpoint | free |
| Data transfer between AZs | \$0.01 / GB in each direction |
| NAT Gateway (rejected, for comparison) | \$0.045 / h + \$0.045 / GB processed |
| Interface VPC endpoint (rejected, for comparison) | \$0.01 / h per AZ + \$0.01 / GB |
| T3 unlimited surplus CPU credits (avoided with standard mode) | \$0.05 / vCPU-hour |
| GitHub Actions, public repository, standard GitHub-hosted runner | free |
| CloudFormation (AWS resource types) | free |

### 3.2 Calculation (2 shards + coordinator running 24/7, 730 h)

| Item | Calculation | Monthly cost |
|---|---|---|
| Coordinator `t3.micro` | 730 h × \$0.0104 | \$7.59 |
| 2 shards `t3.micro` | 2 × 730 h × \$0.0104 | \$15.18 |
| EBS for coordinator + shards | 3 × 8 GB × \$0.08 | \$1.92 |
| Public IPv4 for coordinator | 730 h × \$0.005 | \$3.65 |
| YCSB client `t3.small` (10 h, see 3.3) | 10 h × \$0.0208 | \$0.21 |
| Public IPv4 for YCSB client | 10 h × \$0.005 | \$0.05 |
| EBS for YCSB client (exists only while the instance does: 10 h of 730) | 8 GB × \$0.08 × 10 ÷ 730 | \$0.01 |
| S3 gateway endpoint | — | \$0.00 |
| Cross-AZ traffic (coordinator in 1a, shard B in 1b; 5 GB, see 3.3) | 5 GB × \$0.02 | \$0.10 |
| CI/CD: GitHub Actions + CloudFormation | — | \$0.00 |
| S3 bucket for build artifacts (a few MB) | — | < \$0.01 |
| **Total** | | **\$28.71** |

### 3.3 Where the benchmark numbers come from

The 10 h and the 5 GB depend on how the M2 benchmark (M2 task 7) will be run, which is not designed yet. So they are planning values, based on the assumption below, to be replaced by measured values after M2.

**Assumption:** 5 benchmark runs. Each run loads 1,000,000 records and then executes 1,000,000 operations. A YCSB record is about 1 KB (default 10 fields × 100 bytes).

**YCSB client hours.** The instance is billed for every hour it is running, including setup (installing Java and YCSB), the load phase, the run phase and repeated attempts. 5 runs × ~2 h = 10 h.

**Cross-AZ traffic.**
- The YCSB client and the coordinator are both in us-east-1a, so the traffic between them is free.
- Only the traffic between the coordinator (1a) and shard B (1b) crosses AZs.
- With consistent hashing about half of the keys land on shard B.
- Per run: (1,000,000 records + 1,000,000 operations) × 1 KB ÷ 2 ≈ 1 GB.
- 5 runs ≈ 5 GB. It is billed \$0.01/GB when it leaves 1a and \$0.01/GB when it enters 1b, so \$0.02/GB.

**How much they matter.** Each extra YCSB client hour adds \$0.0258 (instance + public IPv4). Each extra cross-AZ GB adds \$0.02. Even 4 times more (40 h and 20 GB) adds only \$1.07, so the total stays under \$30.

**How to replace them after M2:**
- **Hours:** the time between launching and terminating the YCSB client instance (EC2 console -> instance -> **Launch time**, and the stack events of its termination).
- **Cross-AZ GB:** **Cost Explorer** (available in the Learner Lab) -> Group by **Usage type** -> look for `USE1-DataTransfer-Regional-Bytes`, which is data transfer between AZs in us-east-1.

### 3.4 Comparison with the Learner Lab budget

| | Value |
|---|---|
| Remaining budget on the Learner Lab page | \$50.00 |
| M2 cost for one month at 24/7 | \$28.71 (AWS Pricing Calculator: \$28.67, see 3.5) |
| Share of the whole budget | 57.4% |
| How long \$50 lasts at 24/7 | ~1.7 months |
| Same stack with `t3.small` instead of `t3.micro` | \$51.49 / month: over the budget |
| Same stack with a NAT Gateway added | \$61.56 / month (AWS Pricing Calculator: \$61.52): over the budget |

**Conclusion:** the M2 stack fits in \$50 for one month, but \$50 has to cover M2, M3, M4 and M5, and M3 adds replicas. Running 24/7 is not affordable for the whole course. So:

- The stack runs only while I work. Running cost is \$0.0362 per hour (3 × `t3.micro` + coordinator IPv4). While it is stopped, only EBS is charged (\$1.92 / month for the 3 volumes of the coordinator and shards).
- The Learner Lab shuts EC2 instances down when a session ends (sessions last 4 hours by default) but **starts them again when the next session starts** (readme). So the 24/7 figure is the upper bound: in practice instances run only during sessions. I stop them manually after starting a session in which I don't need them.
- The stacks stay deployed between milestones, because M3–M5 build on the same coordinator and shards, and the Learner Lab keeps resources between sessions. Stopped instances keep their disks, so the data in the store survives. The stacks are deleted with CloudFormation only after the capstone is done.
- The YCSB client is terminated after each benchmark. Before that, the benchmark results are copied off it (to the repository or to S3), because terminating deletes its disk and the M2 numbers are the baseline for M3 and M5.
- The budget on the lab page lags 8–12 h, so I don't rely on it right after heavy usage.

### 3.5 Check in AWS Pricing Calculator

The prices above were collected from the pages in [Price sources](#price-sources). The calculator recomputes them from AWS's own price list. It needs no AWS account.

Labels below are from the AWS Pricing Calculator user guide. A few (the usage-hours field, data transfer, VPC fields) may be worded slightly differently on screen.

**Service 1: coordinator + 2 shards**
1. Open `https://calculator.aws/#/` and choose **Create estimate**.
2. On **Add service**, search `Amazon EC2` and choose **Configure**.
3. **Description:** `M2 coordinator + 2 shards`. **Location type:** Region. **Region:** US East (N. Virginia).
4. **Select your tenancy:** Shared Instances. **Select your operating system:** Linux.
5. **Workloads:** Constant usage. **Number of EC2 instances:** `3`.
6. **Choose your instance type:** search `t3.micro` and select it.
7. **Pricing model:** On-Demand. **Expected utilization of EC2 instances:** 100% (730 hours/month).
8. **EBS -> Storage for each EC2 instance:** General Purpose SSD (gp3). **IOPS:** `3000`. **Throughput:** `125` MBps (both are the free gp3 baseline, so they add no cost). **Storage amount:** `8 GB`. **Snapshot Frequency:** No snapshot storage.
9. **Data transfer:** enter `5 GB` of intra-region data transfer (between AZs), if the field is shown.
10. Choose **Save and add service**. Expected: about \$22.78 for instances, \$1.92 for EBS, \$0.10 for data transfer.

**Service 2: YCSB client**
1. On **My estimate**, choose **Add service** -> `Amazon EC2` -> **Configure**.
2. **Description:** `M2 YCSB client`. Same region, tenancy and OS as above.
3. **Number of EC2 instances:** `1`. **Instance type:** `t3.small`.
4. **Pricing model:** On-Demand. **Usage:** `1.37`, **Usage type:** Utilization percent per month (10 h ÷ 730 h = 1.37%).
5. **EBS:** same as Service 1 (gp3, `3000` IOPS, `125` MBps, `8 GB`, no snapshots). With usage under 100% the calculator assumes the volume is deleted while the instance is not running, which matches terminating the client after each benchmark.
6. **Save and add service**. Expected: about \$0.21 for the instance, \$0.01 for EBS.

**Service 3: public IPv4 address of the coordinator**
1. **Add service** -> search `Amazon Virtual Private Cloud (VPC)` -> **Configure**. Region: US East (N. Virginia).
2. In the public IPv4 address section: `1` in-use public IPv4 address for the whole month (730 h).
3. Leave NAT Gateway and endpoints empty.
4. **Save and add service**. Expected: \$3.65.

**Service 4 (comparison only): NAT Gateway**

To show why the NAT Gateway was rejected, Service 3 was edited and exported a second time:
1. **Number of NAT Gateways:** `1`. **Data Processed per NAT Gateway:** `0` GB per month.
2. The calculator doesn't accept fewer than 1 Regional NAT Gateway once the NAT Gateway feature is on, so **Number of Regional NAT Gateways** was left at `1` with `0` GB processed. It added nothing: the VPC line grew from \$3.65 to \$36.50, exactly the \$32.85 of the standard NAT Gateway.

**Result**

| Estimate | Coordinator + 2 shards | YCSB client | VPC | Monthly total | Export |
|---|---|---|---|---|---|
| Chosen design (no NAT Gateway) | \$24.80 | \$0.22 | \$3.65 | **\$28.67** | [estimate-without-nat.pdf](command_outputs/quotas_and_budget/estimate-without-nat.pdf) |
| With a NAT Gateway | \$24.80 | \$0.22 | \$36.50 | **\$61.52** | [estimate-with-nat.pdf](command_outputs/quotas_and_budget/estimate-with-nat.pdf) |

Saved estimate: [AWS Pricing Calculator estimate](https://calculator.aws/#/estimate?id=0c980c37dc10267a5387b74aa89016bf45a82b8b)

- **Without NAT:** the calculator gives \$28.67, against \$28.71 in 3.2. The \$0.04 difference is the YCSB client's public IPv4 (10 h × \$0.005), which was not entered in the calculator.
- **With NAT:** \$61.52 is over the whole \$50 budget in one month, which confirms the decision in part 1.

### Price sources
- [EC2 T3 instances](https://aws.amazon.com/ec2/instance-types/t3/): unlimited mode by default, \$0.05 per vCPU-hour, baseline per size
- [t3.micro price, us-east-1](https://calculator.holori.com/aws/ec2/t3.micro/us-east-1), [t3.small price, us-east-1](https://calculator.holori.com/aws/ec2/t3.small/us-east-1)
- [Amazon EBS pricing](https://aws.amazon.com/ebs/pricing): gp3 and gp2 per GB-month, gp3 free baseline
- [Amazon Linux 2023: gp3 as default volume type](https://docs.aws.amazon.com/linux/al2023/ug/continuing-al2-filesystem.html)
- [Amazon VPC pricing](https://aws.amazon.com/vpc/pricing/): NAT Gateway, public IPv4, gateway endpoints, cross-AZ
- [AWS public IPv4 address charge](https://aws.amazon.com/blogs/aws/new-aws-public-ipv4-address-charge-public-ip-insights)
- [AWS PrivateLink pricing](https://aws.amazon.com/privatelink/pricing/): interface endpoints
- [Update packages without internet access (S3 gateway endpoint, AL2023 repo bucket)](https://repost.aws/knowledge-center/ec2-al1-al2-update-yum-without-internet)
- [About billing for GitHub Actions](https://docs.github.com/en/billing/managing-billing-for-github-actions/about-billing-for-github-actions): free for public repositories on standard runners, 2,000 minutes for GitHub Free private repositories
- [AWS Pricing Calculator: create an estimate](https://docs.aws.amazon.com/pricing-calculator/latest/userguide/create-configure-estimate.html), [EC2 estimates](https://docs.aws.amazon.com/pricing-calculator/latest/userguide/ec2-estimates.html)