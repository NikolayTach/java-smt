// This file is part of JavaSMT,
// an API wrapper for a collection of SMT solvers:
// https://github.com/sosy-lab/java-smt
//
// SPDX-FileCopyrightText: 2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.java_smt.api;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.sosy_lab.java_smt.api.SolverContext.ProverOptions;

/**
 * Super interface for {@link ProverEnvironment} and {@link InterpolatingProverEnvironment} that
 * provides only the common operations. In most cases, just use one of the two sub-interfaces
 */
public interface BasicProverEnvironment<T> extends AutoCloseable {

  String NO_MODEL_HELP = "Model computation failed. Are the pushed formulae satisfiable?";

  /**
   * Push a backtracking point and add a formula to the environment stack, asserting it. The return
   * value may be used to identify this formula later on in a query (this depends on the sub-type of
   * the environment).
   */
  @Nullable
  @CanIgnoreReturnValue
  default T push(BooleanFormula f) throws InterruptedException {
    push();
    return addConstraint(f);
  }

  /** Remove one formula from the environment stack. */
  void pop();

  /** Add constraint to the context. */
  @Nullable
  @CanIgnoreReturnValue
  T addConstraint(BooleanFormula constraint) throws InterruptedException;

  /** Create backtracking point. */
  void push();

  /** Check whether the conjunction of all formulas on the stack is unsatisfiable. */
  boolean isUnsat() throws SolverException, InterruptedException;

  /**
   * Check whether the conjunction of all formulas on the stack together with the list of
   * assumptions is satisfiable.
   *
   * @param assumptions A list of literals.
   */
  boolean isUnsatWithAssumptions(Collection<BooleanFormula> assumptions)
      throws SolverException, InterruptedException;

  /**
   * Get a satisfying assignment. This should be called only immediately after an {@link #isUnsat()}
   * call that returned <code>false</code>. A model might contain additional symbols with their
   * evaluation, if a solver uses its own temporary symbols. There should be at least an
   * value-assignment for each free symbol.
   */
  Model getModel() throws SolverException;

  /**
   * Get a list of satisfying assignments. This is equivalent to <code>
   * ImmutableList.copyOf(getModel())</code>, but removes the need for calling {@link
   * Model#close()}.
   *
   * <p>Note that if you need to iterate multiple times over the model it may be more efficient to
   * use this method instead of {@link #getModel()} (depending on the solver).
   */
  default ImmutableList<Model.ValueAssignment> getModelAssignments() throws SolverException {
    try (Model model = getModel()) {
      return model.asList();
    }
  }

  /**
   * Get an unsat core. This should be called only immediately after an {@link #isUnsat()} call that
   * returned <code>false</code>.
   */
  List<BooleanFormula> getUnsatCore();

  /**
   * Returns an UNSAT core (if it exists, otherwise {@code Optional.empty()}), over the chosen
   * assumptions. Does NOT require the {@link ProverOptions#GENERATE_UNSAT_CORE} option to work.
   *
   * @param assumptions Selected assumptions
   * @return Empty optional if the constraints with assumptions are satisfiable, subset of
   *     assumptions which is unsatisfiable with the original constraints otherwise.
   */
  Optional<List<BooleanFormula>> unsatCoreOverAssumptions(Collection<BooleanFormula> assumptions)
      throws SolverException, InterruptedException;

  /**
   * Closes the prover environment. The object should be discarded, and should not be used after
   * closing.
   */
  @Override
  void close();

  /**
   * Get all satisfying assignments of the current environment with regards to a subset of terms,
   * and create a region representing all those models.
   *
   * @param important A set of (positive) variables appearing in the asserted queries. Only these
   *     variables will appear in the region.
   * @return A region representing all satisfying models of the formula.
   */
  <R> R allSat(AllSatCallback<R> callback, List<BooleanFormula> important)
      throws InterruptedException, SolverException;

  /**
   * Interface for the {@link #allSat} callback.
   *
   * @param <R> The result type of the callback, passed through by {@link #allSat}.
   */
  interface AllSatCallback<R> {

    /**
     * Callback for each possible satisfying assignment to given {@code important} predicates. If
     * the predicate is assigned {@code true} in the model, it is returned as-is in the list, and
     * otherwise it is negated.
     *
     * <p>There is no guarantee that the list of model values corresponds to the list in {@link
     * BasicProverEnvironment#allSat}. We can reorder the variables or leave out values with an
     * freely chosen value.
     */
    void apply(List<BooleanFormula> model);

    /** Returning the result generated after all the {@link #apply} calls have went through. */
    R getResult() throws InterruptedException;
  }
}
