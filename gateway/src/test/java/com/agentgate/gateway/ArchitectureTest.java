package com.agentgate.gateway;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.agentgate.gateway.approval.ApprovalStep;
import com.agentgate.gateway.guard.SchemaValidationStep;
import com.agentgate.gateway.pipeline.CallStep;
import com.agentgate.gateway.policy.PolicyStep;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * C6: architecture rules as tests. If someone breaks a design decision, the build fails.
 * Only production classes are imported (tests are excluded), so "tests may touch anything" is not a loophole.
 */
@AnalyzeClasses(packages = "com.agentgate.gateway", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ROOT = "com.agentgate.gateway";

    private static final com.tngtech.archunit.base.DescribedPredicate<JavaClass> CONTROLLERS =
            describe("controllers", c -> c.getSimpleName().endsWith("Controller"));

    private static final com.tngtech.archunit.base.DescribedPredicate<JavaClass> DATA_ACCESS =
            describe("repositories", c -> c.getSimpleName().endsWith("Repository"));

    /** 1a. Nothing calls a controller; controllers are the entry points (Spring wires them, code does not). */
    @ArchTest
    static final ArchRule nobody_depends_on_controllers =
            noClasses().that().resideInAPackage(ROOT + "..").and(not(CONTROLLERS))
                    .should().dependOnClassesThat(CONTROLLERS)
                    .because("controllers are entry points; share logic through services instead");

    /** 1b. Pipeline steps (Chain of Responsibility) must not know about the web layer. */
    @ArchTest
    static final ArchRule pipeline_steps_do_not_depend_on_controllers =
            noClasses().that().implement(CallStep.class)
                    .should().dependOnClassesThat(CONTROLLERS)
                    .because("steps run for REST and MCP calls alike, so they cannot depend on one web entry point");

    /** 1c. Controllers go through a service, never straight to a repository (and so never return raw entities from it). */
    @ArchTest
    static final ArchRule controllers_do_not_use_repositories =
            noClasses().that(CONTROLLERS)
                    .should().dependOnClassesThat(DATA_ACCESS)
                    .because("controllers must call a service; the service owns transactions and rules");

    /** 1d. Production code never depends on test code. */
    @ArchTest
    static final ArchRule no_dependency_on_test_packages =
            noClasses().that().resideInAPackage(ROOT + "..")
                    .should().dependOnClassesThat(resideInAPackage("..test.."))
                    .because("test helpers must not leak into production code");

    private static final com.tngtech.archunit.base.DescribedPredicate<JavaClass> CONCRETE_STEP =
            assignableTo(CallStep.class).and(describe("a concrete step", c -> !c.isInterface()));

    /** 2. Chain of Responsibility: steps talk only through the chain (CallChain/ToolCall/CallOutcome), never to each other. */
    @ArchTest
    static final ArchRule pipeline_steps_do_not_depend_on_each_other =
            noClasses().that().implement(CallStep.class).and().areNotAssignableTo(ApprovalStep.class)
                    .should().dependOnClassesThat(CONCRETE_STEP)
                    .because("adding a guard must mean adding a bean, not editing or coupling to another step");

    /**
     * 2b. NAMED EXCEPTION: ApprovalStep. After a human edits the arguments, the edited call must pass the SAME
     * schema check and policy as any call (FR4 + FR5 + FR3), so it re-runs exactly these two steps.
     * Any other step dependency from ApprovalStep still fails the build.
     */
    @ArchTest
    static final ArchRule approval_step_may_only_reuse_schema_and_policy_steps =
            noClasses().that().areAssignableTo(ApprovalStep.class)
                    .should().dependOnClassesThat(CONCRETE_STEP
                            .and(not(assignableTo(SchemaValidationStep.class)))
                            .and(not(assignableTo(PolicyStep.class)))
                            .and(not(assignableTo(ApprovalStep.class))))
                    .because("only the re-validation of edited arguments justifies calling other steps");

    /** 3. Strategy abstraction: only the policy package may read/write policy tables. */
    @ArchTest
    static final ArchRule only_policy_package_touches_policy_tables =
            noClasses().that().resideOutsideOfPackage(ROOT + ".policy..")
                    .should().dependOnClassesThat().haveSimpleNameStartingWith("PolicyVersionRepository")
                    .orShould().dependOnClassesThat().haveFullyQualifiedName(ROOT + ".policy.PolicyVersion")
                    .because("everyone else must use PolicyEngine / ActivePolicyCache / PolicyAdminService");

    /** 4. No cycles between top-level packages (admin, approval, audit, ...). */
    @ArchTest
    static final ArchRule no_cycles_between_top_level_packages =
            slices().matching(ROOT + ".(*)..").should().beFreeOfCycles();
}
