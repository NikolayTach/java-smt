// This file is part of JavaSMT,
// an API wrapper for a collection of SMT solvers:
// https://github.com/sosy-lab/java-smt
//
// SPDX-FileCopyrightText: 2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.java_smt.solvers.z3;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.microsoft.z3.Native;
import com.microsoft.z3.enumerations.Z3_ast_print_mode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.ShutdownNotifier.ShutdownRequestListener;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.FileOption;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.common.io.IO;
import org.sosy_lab.common.io.PathCounterTemplate;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.java_smt.SolverContextFactory.Solvers;
import org.sosy_lab.java_smt.api.FloatingPointRoundingMode;
import org.sosy_lab.java_smt.api.InterpolatingProverEnvironment;
import org.sosy_lab.java_smt.api.OptimizationProverEnvironment;
import org.sosy_lab.java_smt.api.ProverEnvironment;
import org.sosy_lab.java_smt.basicimpl.AbstractNumeralFormulaManager.NonLinearArithmetic;
import org.sosy_lab.java_smt.basicimpl.AbstractSolverContext;

@Options(prefix = "solver.z3")
public final class Z3SolverContext extends AbstractSolverContext {

  /** Optimization settings. */
  @Option(
      secure = true,
      description = "Engine to use for the optimization",
      values = {"basic", "farkas", "symba"})
  String optimizationEngine = "basic";

  @Option(
      secure = true,
      description = "Ordering for objectives in the optimization context",
      values = {"lex", "pareto", "box"})
  String objectivePrioritizationMode = "box";

  private final ShutdownRequestListener interruptListener;
  private final ShutdownNotifier shutdownNotifier;
  private final @Nullable PathCounterTemplate logfile;
  private final long z3params;
  private final LogManager logger;
  private final Z3FormulaCreator creator;
  private final Z3FormulaManager manager;
  private boolean closed = false;

  private static final String OPT_ENGINE_CONFIG_KEY = "optsmt_engine";
  private static final String OPT_PRIORITY_CONFIG_KEY = "priority";

  @Options(prefix = "solver.z3")
  private static class ExtraOptions {

    @Option(secure = true, description = "Require proofs from SMT solver")
    boolean requireProofs = false;

    @Option(
        secure = true,
        description =
            "Activate replayable logging in Z3."
                + " The log can be given as an input to the solver and replayed.")
    @FileOption(FileOption.Type.OUTPUT_FILE)
    @Nullable Path log = null;
  }

  @SuppressWarnings("checkstyle:parameternumber")
  private Z3SolverContext(
      Z3FormulaCreator pFormulaCreator,
      Configuration config,
      long pZ3params,
      ShutdownNotifier pShutdownNotifier,
      LogManager pLogger,
      Z3FormulaManager pManager,
      @Nullable PathCounterTemplate pSolverLogFile)
      throws InvalidConfigurationException {
    super(pManager);

    creator = pFormulaCreator;
    config.inject(this);
    z3params = pZ3params;
    interruptListener = reason -> Native.interrupt(pFormulaCreator.getEnv());
    shutdownNotifier = pShutdownNotifier;
    pShutdownNotifier.register(interruptListener);
    logger = pLogger;
    manager = pManager;
    logfile = pSolverLogFile;
  }

  @SuppressWarnings("ParameterNumber")
  public static synchronized Z3SolverContext create(
      LogManager logger,
      Configuration config,
      ShutdownNotifier pShutdownNotifier,
      @Nullable PathCounterTemplate solverLogfile,
      long randomSeed,
      FloatingPointRoundingMode pFloatingPointRoundingMode,
      NonLinearArithmetic pNonLinearArithmetic,
      Consumer<String> pLoader)
      throws InvalidConfigurationException {
    ExtraOptions extraOptions = new ExtraOptions();
    config.inject(extraOptions);

    // We need to load z3 in addition to z3java, because Z3's own class only loads the latter,
    // but it will fail to find the former if not loaded previously.
    // We load both libraries here to have all the loading in one place.
    loadLibrariesWithFallback(
        pLoader, ImmutableList.of("z3", "z3java"), ImmutableList.of("libz3", "libz3java"));

    // disable Z3's own loading mechanism, see com.microsoft.z3.Native
    System.setProperty("z3.skipLibraryLoad", "true");

    if (extraOptions.log != null) {
      Path absolutePath = extraOptions.log.toAbsolutePath();
      try {
        // Z3 segfaults if it cannot write to the file, thus we write once first
        IO.writeFile(absolutePath, StandardCharsets.US_ASCII, "");
        Native.openLog(absolutePath.toString());
      } catch (IOException e) {
        logger.logUserException(Level.WARNING, e, "Cannot write Z3 log file");
      }
    }

    long cfg = Native.mkConfig();
    if (extraOptions.requireProofs) {
      Native.setParamValue(cfg, "PROOF", "true");
    }
    Native.globalParamSet("smt.random_seed", String.valueOf(randomSeed));
    Native.globalParamSet("model.compact", "false");

    final long context = Native.mkContextRc(cfg);
    Native.delConfig(cfg);

    long boolSort = Native.mkBoolSort(context);
    Native.incRef(context, Native.sortToAst(context, boolSort));
    long integerSort = Native.mkIntSort(context);
    Native.incRef(context, Native.sortToAst(context, integerSort));
    long realSort = Native.mkRealSort(context);
    Native.incRef(context, Native.sortToAst(context, realSort));
    long stringSort = Native.mkStringSort(context);
    Native.incRef(context, Native.sortToAst(context, stringSort));
    long regexSort = Native.mkReSort(context, stringSort);
    Native.incRef(context, Native.sortToAst(context, regexSort));

    // The string representations of Z3s formulas should be in SMTLib2,
    // otherwise serialization wouldn't work.
    Native.setAstPrintMode(context, Z3_ast_print_mode.Z3_PRINT_SMTLIB2_COMPLIANT.toInt());

    long z3params = Native.mkParams(context);
    Native.paramsIncRef(context, z3params);
    Native.paramsSetUint(
        context, z3params, Native.mkStringSymbol(context, ":random-seed"), (int) randomSeed);

    Z3FormulaCreator creator =
        new Z3FormulaCreator(
            context,
            boolSort,
            integerSort,
            realSort,
            stringSort,
            regexSort,
            config,
            pShutdownNotifier);

    // Create managers
    Z3UFManager functionTheory = new Z3UFManager(creator);
    Z3BooleanFormulaManager booleanTheory = new Z3BooleanFormulaManager(creator);
    Z3IntegerFormulaManager integerTheory =
        new Z3IntegerFormulaManager(creator, pNonLinearArithmetic);
    Z3RationalFormulaManager rationalTheory =
        new Z3RationalFormulaManager(creator, pNonLinearArithmetic);
    Z3BitvectorFormulaManager bitvectorTheory =
        new Z3BitvectorFormulaManager(creator, booleanTheory);
    Z3FloatingPointFormulaManager floatingPointTheory =
        new Z3FloatingPointFormulaManager(creator, pFloatingPointRoundingMode);
    Z3QuantifiedFormulaManager quantifierManager = new Z3QuantifiedFormulaManager(creator);
    Z3ArrayFormulaManager arrayManager = new Z3ArrayFormulaManager(creator);
    Z3StringFormulaManager stringTheory = new Z3StringFormulaManager(creator);

    // Set the custom error handling
    // which will throw Z3Exception
    // instead of exit(1).
    Native.setInternalErrorHandler(context);

    Z3FormulaManager manager =
        new Z3FormulaManager(
            creator,
            functionTheory,
            booleanTheory,
            integerTheory,
            rationalTheory,
            bitvectorTheory,
            floatingPointTheory,
            quantifierManager,
            arrayManager,
            stringTheory);
    return new Z3SolverContext(
        creator, config, z3params, pShutdownNotifier, logger, manager, solverLogfile);
  }

  @Override
  protected ProverEnvironment newProverEnvironment0(Set<ProverOptions> options) {
    Preconditions.checkState(!closed, "solver context is already closed");
    long z3context = creator.getEnv();
    Native.paramsSetBool(
        z3context,
        z3params,
        Native.mkStringSymbol(z3context, ":model"),
        options.contains(ProverOptions.GENERATE_MODELS)
            || options.contains(ProverOptions.GENERATE_ALL_SAT));
    Native.paramsSetBool(
        z3context,
        z3params,
        Native.mkStringSymbol(z3context, ":unsat_core"),
        options.contains(ProverOptions.GENERATE_UNSAT_CORE)
            || options.contains(ProverOptions.GENERATE_UNSAT_CORE_OVER_ASSUMPTIONS));
    return new Z3TheoremProver(creator, manager, z3params, options, logfile, shutdownNotifier);
  }

  @Override
  protected InterpolatingProverEnvironment<?> newProverEnvironmentWithInterpolation0(
      Set<ProverOptions> options) {
    throw new UnsupportedOperationException("Z3 does not support interpolation");
  }

  @Override
  public OptimizationProverEnvironment newOptimizationProverEnvironment0(
      Set<ProverOptions> options) {
    Preconditions.checkState(!closed, "solver context is already closed");
    Z3OptimizationProver out =
        new Z3OptimizationProver(
            creator, logger, z3params, manager, options, logfile, shutdownNotifier);
    out.setParam(OPT_ENGINE_CONFIG_KEY, this.optimizationEngine);
    out.setParam(OPT_PRIORITY_CONFIG_KEY, this.objectivePrioritizationMode);
    return out;
  }

  @Override
  public String getVersion() {
    Native.IntPtr major = new Native.IntPtr();
    Native.IntPtr minor = new Native.IntPtr();
    Native.IntPtr build = new Native.IntPtr();
    Native.IntPtr revision = new Native.IntPtr();
    Native.getVersion(major, minor, build, revision);
    return "Z3 " + major.value + "." + minor.value + "." + build.value + "." + revision.value;
  }

  @Override
  public Solvers getSolverName() {
    return Solvers.Z3;
  }

  @Override
  public void close() {
    if (!closed) {
      closed = true;
      long context = creator.getEnv();
      creator.forceClose();
      shutdownNotifier.unregister(interruptListener);
      Native.paramsDecRef(context, z3params);
      Native.closeLog();
      Native.delContext(context);
    }
  }

  @Override
  protected boolean supportsAssumptionSolving() {
    return true;
  }
}
