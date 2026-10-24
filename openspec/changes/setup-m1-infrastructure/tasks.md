## 1. Decisions (before any code)

- [x] ADR: choice of IaC tool
- [x] ADR: stack layout (one stack, or network separate from application)
- [x] ADR: CIDR addressing scheme

## 2. Role dissection

- [x] Document what the session role `voclabs` allows and denies in the Cloud Architecting Sandbox, and how this was found out (console, CLI, policy simulator)
- [x] Document what `LabRole` allows and denies in the Learner Lab, and how this was found out (console, CLI, policy simulator)

## 3. IAM in IaC (not deployed)

- [x] IaC template: IAM policy that allows ONLY viewing resources (no write access)
- [x] Explain what the policy would do
- [x] IaC template: Role with this policy attached, assumable by another principal
- [x] Explain why the sandbox refuses it

## 4. Network

- [x] IaC: VPC
- [x] IaC: public subnet with Internet Gateway
- [x] IaC: private subnets for internal access only
- [x] IaC stack holding the network resources, exporting the VPC ID and subnet IDs
- [x] Deploy the stack in the AWS Academy Cloud Architecting Sandbox

## 5. Planning for Milestone 2

- [x] Review service quotas needed in Milestone 2 in the Learner Lab; write down the ones that can limit us
- [x] Calculate the monthly budget for Milestone 2 in the Learner Lab, assuming 2 shards running 24/7 (730 h/month)
- [x] Compare the budget against the remaining budget shown on the Learner Lab page

## 6. Recreatability

- [x] Tear the whole stack down
- [x] Bring it back from IaC alone
- [x] Record how long it took
- [x] Record what broke on the first attempt

## 7. Deliverables (apply to every milestone)

- [x] README: how to bring it up, how to verify it, what changed since the previous milestone
- [ ] Demo
- [x] Transcript of the AI critique of the architecture