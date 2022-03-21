// This file is part of JavaSMT,
// an API wrapper for a collection of SMT solvers:
// https://github.com/sosy-lab/java-smt
//
// SPDX-FileCopyrightText: 2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.java_smt.solvers.cvc5;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import io.github.cvc5.api.CVC5ApiException;
import io.github.cvc5.api.Result;
import io.github.cvc5.api.Solver;
import io.github.cvc5.api.Term;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.java_smt.api.BasicProverEnvironment;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.BooleanFormulaManager;
import org.sosy_lab.java_smt.api.Model.ValueAssignment;
import org.sosy_lab.java_smt.api.ProverEnvironment;
import org.sosy_lab.java_smt.api.SolverContext.ProverOptions;
import org.sosy_lab.java_smt.api.SolverException;
import org.sosy_lab.java_smt.basicimpl.AbstractProverWithAllSat;

/*
 * TODO: import/export of expressions is currently not supported, hence we need to use 1 solver
 * (context) for everything! They are working on it. See CVC5 github discussion.
 */
class CVC5TheoremProver extends AbstractProverWithAllSat<Void>
    implements ProverEnvironment, BasicProverEnvironment<Void> {

  private final CVC5FormulaCreator creator;
  private final Solver solver;
  private boolean changedSinceLastSatQuery = false;

  /** Tracks formulas on the stack, needed for model generation. */
  protected final Deque<List<Term>> assertedFormulas = new ArrayDeque<>();

  /**
   * Tracks provided models to inform them when the solver is closed. We can no longer access model
   * evaluation after closing the solver.
   */
  private final Set<CVC5Model> models = new LinkedHashSet<>();

  // TODO: does CVC5 support separation logic in incremental mode?
  private final boolean incremental;

  protected CVC5TheoremProver(
      CVC5FormulaCreator pFormulaCreator,
      ShutdownNotifier pShutdownNotifier,
      @SuppressWarnings("unused") int randomSeed,
      Set<ProverOptions> pOptions,
      BooleanFormulaManager pBmgr,
      Solver pSolver) {
    super(pOptions, pBmgr, pShutdownNotifier);

    creator = pFormulaCreator;
    solver = pSolver;
    incremental = !enableSL;
    assertedFormulas.push(new ArrayList<>()); // create initial level

    // We would set some of these options twice now as we only use 1 solver at all times (per
    // context)
    // setOptions(randomSeed, pOptions);
  }

  // Keep this until we have more solvers
  @SuppressWarnings("unused")
  private void setOptions(int randomSeed, Set<ProverOptions> pOptions) {
    solver.setOption("incremental", "true");
    if (pOptions.contains(ProverOptions.GENERATE_MODELS)) {
      solver.setOption("produce-models", "true");
    }
    if (pOptions.contains(ProverOptions.GENERATE_UNSAT_CORE)) {
      solver.setOption("produce-unsat-cores", "true");
    }
    solver.setOption("produce-assertions", "true");
    solver.setOption("dump-models", "true");
    solver.setOption("output-language", "smt2");
    solver.setOption("random-seed", String.valueOf(randomSeed));
    // Set Strings option to enable all String features (such as lessOrEquals)
    solver.setOption("strings-exp", "true");
    // Enable more complete quantifier solving (for more information see
    // CVC5QuantifiedFormulaManager)
    solver.setOption("full-saturate-quant", "true");
  }

  protected void setOptionForIncremental() {
    solver.setOption("incremental", "true");
  }

  @Override
  public void push() {
    Preconditions.checkState(!closed);
    setChanged();
    assertedFormulas.push(new ArrayList<>());
    if (incremental) {
      try {
        solver.push();
      } catch (CVC5ApiException e) {
        throw new IllegalStateException(
            "You tried to use push() on an CVC5 assertion stack illegally.", e);
      }
    }
  }

  @Override
  public void pop() {
    Preconditions.checkState(!closed);
    setChanged();
    assertedFormulas.pop();
    Preconditions.checkState(!assertedFormulas.isEmpty(), "initial level must remain until close");
    if (incremental) {
      try {
        solver.pop();
      } catch (CVC5ApiException e) {
        throw new IllegalStateException(
            "You tried to use pop() on an CVC5 assertion stack illegally.", e);
      }
    }
  }

  @Override
  public @Nullable Void addConstraint(BooleanFormula pF) throws InterruptedException {
    Preconditions.checkState(!closed);
    setChanged();
    Term exp = creator.extractInfo(pF);
    assertedFormulas.peek().add(exp);
    if (incremental) {
      solver.assertFormula(exp);
    }
    return null;
  }

  @Override
  public CVC5Model getModel() {
    Preconditions.checkState(!closed);
    checkGenerateModels();
    return getModelWithoutChecks();
  }

  @Override
  protected CVC5Model getModelWithoutChecks() {
    Preconditions.checkState(!changedSinceLastSatQuery);
    CVC5Model model = new CVC5Model(this, creator, getAssertedExpressions());
    models.add(model);
    return model;
  }

  void unregisterModel(CVC5Model model) {
    models.remove(model);
  }

  private void setChanged() {
    if (!changedSinceLastSatQuery) {
      changedSinceLastSatQuery = true;
      closeAllModels();
    }
  }

  /**
   * whenever the SmtEngine changes, we need to invalidate all models.
   *
   * <p>See for details <a href="https://github.com/CVC4/CVC4/issues/2648">Issue 2648</a> . This is
   * legacy CVC4. TODO: decide whether we need this or not
   */
  private void closeAllModels() {
    for (CVC5Model model : ImmutableList.copyOf(models)) {
      model.close();
    }
    Preconditions.checkState(models.isEmpty(), "all models should be closed");
  }

  @Override
  public ImmutableList<ValueAssignment> getModelAssignments() throws SolverException {
    Preconditions.checkState(!closed);
    Preconditions.checkState(!changedSinceLastSatQuery);
    try (CVC5Model model = getModel()) {
      return model.toList();
    }
  }

  @Override
  @SuppressWarnings("try")
  public boolean isUnsat() throws InterruptedException, SolverException {
    Preconditions.checkState(!closed);
    closeAllModels();
    changedSinceLastSatQuery = false;
    if (!incremental) {
      for (Term term : getAssertedExpressions()) {
        // We can not translate terms as CVC5 does not support it. We need to use the same solver
        // for creation, assertion and solving!
        solver.assertFormula(term);
      }
    }

    /* Shutdown currently not possible in CVC5. */
    Result result = solver.checkSat();
    shutdownNotifier.shutdownIfNecessary();
    return convertSatResult(result);
  }

  private boolean convertSatResult(Result result) throws InterruptedException, SolverException {
    if (result.isSatUnknown()) {
      if (result.getUnknownExplanation().equals(Result.UnknownExplanation.INTERRUPTED)) {
        throw new InterruptedException();
      } else {
        throw new SolverException("CVC5 returned null or unknown on sat check (" + result + ")");
      }
    }
    return result.isUnsat();
  }

  @Override
  public List<BooleanFormula> getUnsatCore() {
    Preconditions.checkState(!closed);
    checkGenerateUnsatCores();
    Preconditions.checkState(!changedSinceLastSatQuery);
    List<BooleanFormula> converted = new ArrayList<>();
    for (Term aCore : solver.getUnsatCore()) {
      converted.add(creator.encapsulateBoolean(aCore));
    }
    return converted;
  }

  @Override
  public boolean isUnsatWithAssumptions(Collection<BooleanFormula> pAssumptions)
      throws SolverException, InterruptedException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<List<BooleanFormula>> unsatCoreOverAssumptions(
      Collection<BooleanFormula> pAssumptions) throws SolverException, InterruptedException {
    throw new UnsupportedOperationException();
  }

  protected Collection<Term> getAssertedExpressions() {
    List<Term> result = new ArrayList<>();
    assertedFormulas.forEach(result::addAll);
    return result;
  }

  @Override
  public void close() {
    if (!closed) {
      closeAllModels();
      assertedFormulas.clear();
      solver.resetAssertions();
      // Dont close the solver here, currently we use one solver instance for all stacks + the
      // context!
      // TODO: revisit once the devs enable formula translation.
      closed = true;
    }
  }
}
