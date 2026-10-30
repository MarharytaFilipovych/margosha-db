# Spec Delta

## Purpose

Describes the CI/CD pipeline that builds and tests every change and deploys infrastructure and application updates to the Learner Lab; later milestones extend it.

## ADDED Requirements

### Requirement: Build and test on every push
On every push and pull request, the pipeline SHALL build the application and run its automated tests. A failure SHALL fail the pipeline.

#### Scenario: Failing test
- **WHEN** a commit with a failing unit test is pushed
- **THEN** the pipeline fails and nothing is deployed

### Requirement: Deploy from main
After the build and tests pass on a push to `main`, or when started by hand, the pipeline SHALL deploy in order: the network stack, the application stack, the application jar, and then reboot the coordinator and the shards so they run the new jar. Stacks with no changes SHALL be left as they are without failing the run.

#### Scenario: Application-only change
- **WHEN** a commit that changes only application code is pushed to `main`
- **THEN** the pipeline uploads the new jar, the stacks report no changes, and after the reboot the nodes run the new jar

#### Scenario: Infrastructure change
- **WHEN** a commit that changes a CloudFormation template is pushed to `main`
- **THEN** the pipeline updates that stack through CloudFormation
