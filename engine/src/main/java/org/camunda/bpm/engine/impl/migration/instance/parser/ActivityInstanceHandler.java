/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.camunda.bpm.engine.impl.migration.instance.parser;

import java.util.ArrayList;
import java.util.List;

import org.camunda.bpm.engine.impl.core.delegate.CoreActivityBehavior;
import org.camunda.bpm.engine.impl.migration.instance.MigratingActivityInstance;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.camunda.bpm.engine.impl.persistence.entity.VariableInstanceEntity;
import org.camunda.bpm.engine.impl.pvm.delegate.MigrationObserverBehavior;
import org.camunda.bpm.engine.impl.pvm.process.ScopeImpl;
import org.camunda.bpm.engine.migration.MigrationInstruction;
import org.camunda.bpm.engine.runtime.ActivityInstance;

/**
 * @author Thorben Lindhauer
 *
 */
public class ActivityInstanceHandler implements MigratingInstanceParseHandler<ActivityInstance> {

  @Override
  public void handle(MigratingInstanceParseContext parseContext, ActivityInstance element) {
    MigratingActivityInstance migratingInstance = null;

    MigrationInstruction applyingInstruction = parseContext.getInstructionFor(element.getActivityId());
    ScopeImpl sourceScope = null;
    ScopeImpl targetScope = null;
    ExecutionEntity representativeExecution = parseContext.getMapping().getExecution(element);

    if (element.getId().equals(element.getProcessInstanceId())) {
      sourceScope = parseContext.getSourceProcessDefinition();
      targetScope = parseContext.getTargetProcessDefinition();
    }
    else {
      sourceScope = parseContext.getSourceProcessDefinition().findActivity(element.getActivityId());

      if (applyingInstruction != null) {
        String activityId = applyingInstruction.getTargetActivityId();
        targetScope = parseContext.getTargetProcessDefinition().findActivity(activityId);
      }
    }

    migratingInstance = parseContext.getMigratingProcessInstance()
        .addActivityInstance(
          applyingInstruction,
          element,
          sourceScope,
          targetScope,
          representativeExecution);

    MigratingActivityInstance parentInstance = parseContext.getMigratingActivityInstanceById(element.getParentActivityInstanceId());

    if (parentInstance != null) {
      parentInstance.getChildren().add(migratingInstance);
      migratingInstance.setParent(parentInstance);
    }

    CoreActivityBehavior<?> sourceActivityBehavior = sourceScope.getActivityBehavior();
    if (sourceActivityBehavior instanceof MigrationObserverBehavior) {
      ((MigrationObserverBehavior) sourceActivityBehavior).onParseMigratingInstance(parseContext, migratingInstance);
    }

    parseContext.submit(migratingInstance);

    parseDependentInstances(parseContext, migratingInstance);
  }

  public void parseDependentInstances(MigratingInstanceParseContext parseContext, MigratingActivityInstance migratingInstance) {
    parseContext.handleDependentJobs(migratingInstance, migratingInstance.resolveRepresentativeExecution().getJobs());
    parseContext.handleDependentEventSubscriptions(migratingInstance, migratingInstance.resolveRepresentativeExecution().getEventSubscriptions());
    parseContext.handleDependentTasks(migratingInstance, migratingInstance.resolveRepresentativeExecution().getTasks());
    parseContext.handleDependentVariables(migratingInstance, collectActivityInstanceVariables(migratingInstance));
  }

  protected List<VariableInstanceEntity> collectActivityInstanceVariables(MigratingActivityInstance instance) {
    List<VariableInstanceEntity> variables = new ArrayList<VariableInstanceEntity>();
    ExecutionEntity representativeExecution = instance.resolveRepresentativeExecution();
    ExecutionEntity parentExecution = representativeExecution.getParent();

    // decide for representative execution and parent execution whether to none/all/concurrentLocal variables
    // belong to this activity instance
    boolean addAllRepresentativeExecutionVariables = instance.getSourceScope().isScope()
        || representativeExecution.isConcurrent();

    if (addAllRepresentativeExecutionVariables) {
      variables.addAll(representativeExecution.getVariablesInternal());
    }
    else {
      variables.addAll(getConcurrentLocalVariables(representativeExecution));
    }

    boolean addAnyParentExecutionVariables = parentExecution != null && instance.getSourceScope().isScope();
    if (addAnyParentExecutionVariables) {
      boolean addAllParentExecutionVariables = parentExecution.isConcurrent();

      if (addAllParentExecutionVariables) {
        variables.addAll(parentExecution.getVariablesInternal());
      }
      else {
        variables.addAll(getConcurrentLocalVariables(parentExecution));
      }
    }

    return variables;
  }

  protected List<VariableInstanceEntity> getConcurrentLocalVariables(ExecutionEntity execution) {
    List<VariableInstanceEntity> variables = new ArrayList<VariableInstanceEntity>();

    for (VariableInstanceEntity variable : execution.getVariablesInternal()) {
      if (variable.isConcurrentLocal()) {
        variables.add(variable);
      }
    }

    return variables;
  }


}
