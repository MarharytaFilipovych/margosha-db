# ADR 0001: Choice of IaC tool

## Context

We need an IaC tool to describe our AWS infrastructure (VPC, subnets, IAM policy and role) as code. Everything runs in the AWS Academy sandbox. The sandbox session lasts 3 hours and all resources are deleted when it ends, so whatever tool we pick has to make redeploying from scratch easy.

## Alternatives considered

- **Terraform**: works with many clouds thanks to its providers, but we only use AWS, so we don't need that. It also needs a state file (`.tfstate`). If we kept the state in S3, the bucket would be deleted together with everything else at the end of each session. Keeping it locally works, but it's one more thing to manage and to lose. Rejected because it adds complexity we don't need.
- **AWS CDK**: we would have to write the infrastructure in a programming language and add a build/synth step. In the end CDK generates CloudFormation anyway, so we would get the same engine with an extra layer on top. Rejected.
- **Manual creation in the console**: rejected right away. The task requires tearing everything down and bringing it back from IaC alone, and clicking in the console can't prove that.
- **AWS CloudFormation**: native AWS tool, nothing extra to install, templates can be written in JSON or YAML.

## Decision

AWS CloudFormation, with templates written in YAML.

## Consequences

- Nothing to install except the AWS CLI. This helps in the sandbox, where credentials are short-lived and we start from a fresh session often.
- No state file to manage or lose. AWS keeps track of the stack state itself, so the 3-hour wipe doesn't leave us with a broken or missing state.
- The CLI covers the whole loop: `validate-template` to check the template, `create-stack` / `update-stack` to deploy, `describe-stack-events` to see what failed and why, and `wait stack-create-complete` to wait for it to finish.
- If a deployment fails, CloudFormation rolls the stack back automatically instead of leaving half-created resources. The failure reason shows up in the stack events, which is useful as evidence since the sandbox restricts IAM and we expect some things to fail.
- Drift is a separate thing from failures: it's when the real resources no longer match the template (for example, someone changed something by hand in the console). CloudFormation can check this with drift detection (`detect-stack-drift`).
- We chose YAML over JSON because YAML supports comments and is easier to read and edit by hand. JSON doesn't support comments at all.