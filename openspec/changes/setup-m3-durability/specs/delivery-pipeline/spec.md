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
