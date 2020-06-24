/*
 *  JavaSMT is an API wrapper for a collection of SMT solvers.
 *  This file is part of JavaSMT.
 *
 *  Copyright (C) 2007-2016  Dirk Beyer
 *  All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.sosy_lab.java_smt.domain_optimization;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.Formula;
import org.sosy_lab.java_smt.api.SolverException;


public class BasicDomainOptimizer implements DomainOptimizer{
  private final DomainOptimizerSolverContext delegate;
  private final DomainOptimizerProverEnvironment wrapped;
  final List<Formula> usedVariables = new ArrayList<>();
  final Set<BooleanFormula> constraints = new LinkedHashSet<>();
  private LinkedHashMap<Formula, SolutionSet> domainDictionary = new LinkedHashMap<>();
  DomainOptimizerProverEnvironment env;
  DomainOptimizerFormulaRegister register;

  public BasicDomainOptimizer(DomainOptimizerSolverContext delegate,
                              DomainOptimizerProverEnvironment wrapped) {

    this.delegate = delegate;
    this.wrapped = wrapped;
    this.register = new DomainOptimizerFormulaRegister(this);
  }

  @Override
  public DomainOptimizerSolverContext getDelegate() {
    return this.delegate;
  }

  @Override
  public DomainOptimizerProverEnvironment getWrapped() {
    return this.wrapped;
  }


  @Override
  public void visit(Formula f) {
    this.register.visit(f);
  }

  @Override
  public List<Formula> getVariables() {
    return this.usedVariables;
  }

  @Override
  public Set<BooleanFormula> getConstraints() {
    return this.constraints;
  }

  public void removeConstraint(BooleanFormula constraint) {
    this.constraints.remove(constraint);
  }

  @Override
  public void pushVariable(Formula var) {
    if (!this.usedVariables.contains(var)) {
      SolutionSet domain = new SolutionSet();
      this.pushDomain(var, domain);
      this.usedVariables.add(var);
    }
  }

  @Override
  public void pushConstraint(BooleanFormula constraint) throws InterruptedException {
    this.register.visit(constraint);
    if (this.register.isCaterpillar(constraint)) {
      this.wrapped.addConstraint(constraint);
      this.register.processConstraint(constraint);
      this.constraints.add(constraint);
    }
  }

  @Override
  public void pushDomain(Formula var, SolutionSet domain) {
    this.domainDictionary.put(var, domain);
  }

  @Override
  public boolean isUnsat() throws SolverException, InterruptedException {
    return this.wrapped.isUnsat();
  }

  @Override
  public SolutionSet getSolutionSet(Formula var) {
    SolutionSet domain = this.domainDictionary.get(var);
    return domain;
  }

  @Override
  public void replace() throws InterruptedException {
    Set<BooleanFormula> constraints = this.getConstraints();
    Set<BooleanFormula> newConstraints = new LinkedHashSet<>();
    for (BooleanFormula constraint : constraints) {
      constraint = (BooleanFormula) this.register.replaceVariablesWithSolutionSets(constraint);
      this.register.processConstraint(constraint);
    }
  }

}