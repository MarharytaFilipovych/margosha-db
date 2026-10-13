# ADR 0001: Choice of IaC tool

## Context

We need an IaC tool to describe our AWS infrastructure (VPC, subnets, IAM policy and role, and from M2 the application) as code.

The project runs in two AWS Academy environments:

- **M1: Cloud Architecting Sandbox.** The session lasts 3 hours, and all resources are deleted when it ends (new AWS account every session).
- **M2 and later: Learner Lab.** Resources are kept between sessions, but there is a fixed budget, and credentials still change every session.

In both, credentials are temporary and IAM is restricted. Whatever tool we pick has to work in both, make redeploying from scratch easy, and work from a CI/CD pipeline later (M2).

## Alternatives considered

- **Terraform**: works with many clouds thanks to its providers, but we only use AWS, so we don't need that. 
    It also needs a state file (`.tfstate`) that has to be stored somewhere. 
    In the sandbox, a state kept in S3 would be deleted together with everything else at the end of each session. 
    In the Learner Lab it would survive, but then the CI/CD pipeline (M2) would also need access to that state file and its locking, on top of the AWS credentials. 
    Keeping it locally works only for one person on one machine. Rejected because it adds complexity we don't need in either environment.
- **AWS CDK**: we would have to write the infrastructure in a programming language and add a build/synth step. 
   In the end CDK generates CloudFormation anyway, so we would get the same engine with an extra layer on top. Rejected.
- **Manual creation in the console**: rejected right away. The task requires tearing everything down and bringing it back from IaC alone, and clicking in the console can't prove that.
- **AWS CloudFormation**: native AWS tool, available in both environments, nothing extra to install, templates can be written in JSON or YAML.

## Decision

AWS CloudFormation, with templates written in YAML.

## Consequences

- Nothing to install except the AWS CLI. This helps because credentials are short-lived in both environments, so we often start in a fresh terminal.
- No state file to manage or lose. AWS keeps track of the stack state itself. 
  In the sandbox, the 3-hour wipe can't leave us with a broken or missing state; in the Learner Lab, the CI/CD pipeline only needs AWS credentials to deploy, 
  not access to a separate state file.
- The CLI covers the whole loop: `validate-template` to check the template, `create-stack` / `update-stack` to deploy, `describe-stack-events` to see what failed and why, 
  and `wait stack-create-complete` to wait for it to finish.
- If a deployment fails, CloudFormation rolls the stack back automatically instead of leaving half-created resources. 
  The failure reason shows up in the stack events, which is useful as evidence since both environments restrict IAM and we expect some things to fail.
- Drift is a separate thing from failures: it's when the real resources no longer match the template (for example, someone changed something by hand in the console). 
  CloudFormation can check this with drift detection (`detect-stack-drift`). This matters more in the Learner Lab, where resources live long enough to drift.
- We chose YAML over JSON because YAML supports comments and is easier to read and edit by hand. JSON doesn't support comments at all.