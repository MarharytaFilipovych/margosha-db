## 1. Decisions (before any code)

- [ ] ADR: choice of IaC tool
- [ ] ADR: stack layout (one stack, or network separate from application)
- [ ] ADR: CIDR addressing scheme

## 2. LabRole

- [ ] Document what LabRole allows and denies in the AWS Academy sandbox (session role: `voclabs`), and how this was found out (console, CLI, policy simulator)
- [ ] Document what LabRole allows and denies in the AWS Learner Lab environment, and how this was found out (console, CLI, policy simulator)

## 3. IAM in IaC (not deployed)

- [ ] IaC template: IAM policy that allows ONLY viewing resources (no write access)
- [ ] Explain what the policy would do
- [ ] IaC template: Role with this policy attached, assumable by another principal
- [ ] Explain why the sandbox refuses it

## 4. Network

- [ ] IaC: VPC
- [ ] IaC: public subnet with Internet Gateway
- [ ] IaC: private subnets for internal access only
- [ ] IaC stack holding the network resources
- [ ] Deploy the stack in the AWS Academy sandbox

## 5. Planning for Milestone 2

- [ ] Review service quotas needed in Milestone 2 in the AWS Academy sandbox; write down the ones that can limit us
- [ ] Review service quotas needed in Milestone 2 in the AWS Learner Lab environment; write down the ones that can limit us
- [ ] Calculate the monthly budget for Milestone 2 with 2 shards
- [ ] Compare the budget against the sandbox credit ceiling

## 6. Recreatability

- [ ] Tear the whole stack down
- [ ] Bring it back from IaC alone
- [ ] Record how long it took
- [ ] Record what broke on the first attempt

## 7. Deliverables (apply to every milestone)

- [ ] README: how to bring it up, how to verify it, what changed since the previous milestone
- [ ] Demo
- [ ] Transcript of the AI critique of the architecture