# Spec Delta

## MODIFIED Requirements

### Requirement: Deploy from main
After the build and tests pass on a push to `main`, or when started by hand, the pipeline SHALL deploy in order: the network stack, the application stack, the application jar, and then reboot the coordinator and every replica so they run the new jar. It SHALL then wait until every node runs the new jar and every replica is registered with the coordinator, and fail otherwise. Stacks with no changes SHALL be left as they are without failing the run.

#### Scenario: Application-only change
- **WHEN** a commit that changes only application code is pushed to `main`
- **THEN** the pipeline uploads the new jar, the stacks report no changes, and after the reboot every node runs the new jar

#### Scenario: Infrastructure change
- **WHEN** a commit that changes a CloudFormation template is pushed to `main`
- **THEN** the pipeline updates that stack through CloudFormation

#### Scenario: Records survive a deploy
- **WHEN** records are written and then a commit is deployed from `main`
- **THEN** after the deploy the records can still be read

#### Scenario: New jar does not start
- **WHEN** a deployed jar fails to start on a node
- **THEN** the pipeline run fails and names that node

## ADDED Requirements

### Requirement: Versioned releases
Every deploy SHALL have a version: the commit it was built from. The pipeline SHALL upload the jars to `releases/<version>/` in the artifacts bucket and pass the version to the application stack as its `AppVersion` parameter, and each node SHALL run the jar of the version the stack names.

#### Scenario: Each version keeps its own jars
- **WHEN** a commit is deployed from `main`
- **THEN** its jars are in `releases/<commit>/`, the application stack's `AppVersion` is that commit, and every node runs that version's jar

### Requirement: Every change is rolled out
Every commit deployed from `main` SHALL update the application stack with its version and roll the nodes out, whether it changed application code, a template, or both. Deploying a version that the stack and every node already run SHALL not reboot the nodes.

#### Scenario: Any change is rolled out
- **WHEN** a commit that changes application code, a template, or both is pushed to `main`
- **THEN** the application stack is updated with the new version and after the reboot every node runs that version's jar

#### Scenario: Nothing changed
- **WHEN** a deploy is started by hand for the commit that is already deployed, and every node runs it
- **THEN** the stacks report no changes and no node is rebooted

### Requirement: Roll back to an earlier version
A deploy started by hand with the version of an earlier deploy SHALL deploy that version again without rebuilding it. A version that has no jar in the bucket SHALL fail the run before any stack is changed.

#### Scenario: Roll back to an earlier version
- **WHEN** a deploy is started by hand with the version of an earlier deploy
- **THEN** the application stack is deployed with that version, without rebuilding it, and every node runs that version's jar

#### Scenario: Unknown version
- **WHEN** a deploy is started by hand with a version that has no jar in the bucket
- **THEN** the pipeline run fails before changing any stack and lists the versions in the bucket
