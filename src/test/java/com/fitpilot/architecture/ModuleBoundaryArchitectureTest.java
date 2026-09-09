package com.fitpilot.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.Set;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "com.fitpilot", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryArchitectureTest {
    private static final Set<String> BUSINESS_MODULES = Set.of(
            "agent", "analytics", "auth", "evaluation", "exercise", "llm", "notification",
            "plan", "pr", "rag", "user", "workout");

    @ArchTest
    static final ArchRule controllers_must_not_access_mappers = noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat(resideInAnyPackage("com.fitpilot..infrastructure..")
                    .and(JavaClass.Predicates.simpleNameEndingWith("Mapper")))
            .because("controllers must enter a module through its application service");

    @ArchTest
    static final ArchRule domain_must_not_depend_on_infrastructure = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("com.fitpilot..infrastructure..")
            .because("domain code must remain independent from infrastructure adapters");

    @ArchTest
    static final ArchRule cross_module_calls_must_target_application_services = classes()
            .that().resideInAnyPackage(BUSINESS_MODULES.stream()
                    .map(module -> "com.fitpilot." + module + "..")
                    .toArray(String[]::new))
            .should(onlyCallOtherModulesThroughApplicationServices())
            .because("cross-module behavior must go through application services or domain events");

    @ArchTest
    static final ArchRule module_internals_must_not_leak_across_boundaries = classes()
            .that().resideInAnyPackage(BUSINESS_MODULES.stream()
                    .map(module -> "com.fitpilot." + module + "..")
                    .toArray(String[]::new))
            .should(notDependOnOtherModuleInternals())
            .because("repositories, infrastructure adapters and controllers are module-internal");

    @ArchTest
    static final ArchRule business_modules_must_be_free_of_cycles = slices()
            .matching("com.fitpilot.(*)..")
            .namingSlices("$1 module")
            .should().beFreeOfCycles()
            .ignoreDependency(resideInAnyPackage(BUSINESS_MODULES.stream()
                    .map(module -> "com.fitpilot." + module + "..")
                    .toArray(String[]::new)).negate(), alwaysTrue())
            .ignoreDependency(alwaysTrue(), resideInAnyPackage(BUSINESS_MODULES.stream()
                    .map(module -> "com.fitpilot." + module + "..")
                    .toArray(String[]::new)).negate())
            .because("feature modules, including Agent, RAG and Workout, must not form dependency cycles");

    private static ArchCondition<JavaClass> onlyCallOtherModulesThroughApplicationServices() {
        return new ArchCondition<>("only call another business module through its application package") {
            @Override
            public void check(JavaClass source, ConditionEvents events) {
                String sourceModule = moduleOf(source);
                for (JavaAccess<?> call : source.getAccessesFromSelf()) {
                    JavaClass target = call.getTargetOwner();
                    String targetModule = moduleOf(target);
                    if (sourceModule == null || targetModule == null || sourceModule.equals(targetModule)) {
                        continue;
                    }
                    String targetPackage = target.getPackageName();
                    boolean allowed = targetPackage.startsWith("com.fitpilot." + targetModule + ".application")
                            || targetPackage.startsWith("com.fitpilot." + targetModule + ".dto")
                            || targetPackage.startsWith("com.fitpilot." + targetModule + ".domain.event")
                            || (targetPackage.startsWith("com.fitpilot." + targetModule + ".domain")
                                && target.isRecord());
                    if (!allowed) {
                        events.add(SimpleConditionEvent.violated(call, call.getDescription()));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> notDependOnOtherModuleInternals() {
        return new ArchCondition<>("not depend on another module's internal adapters") {
            @Override
            public void check(JavaClass source, ConditionEvents events) {
                String sourceModule = moduleOf(source);
                for (Dependency dependency : source.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    String targetModule = moduleOf(target);
                    if (sourceModule == null || targetModule == null || sourceModule.equals(targetModule)) {
                        continue;
                    }
                    String targetPackage = target.getPackageName();
                    boolean internal = targetPackage.startsWith("com.fitpilot." + targetModule + ".repository")
                            || targetPackage.startsWith("com.fitpilot." + targetModule + ".infrastructure")
                            || targetPackage.startsWith("com.fitpilot." + targetModule + ".controller");
                    if (internal) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    private static String moduleOf(JavaClass type) {
        String[] segments = type.getPackageName().split("\\.");
        if (segments.length < 3 || !"com".equals(segments[0]) || !"fitpilot".equals(segments[1])) {
            return null;
        }
        return BUSINESS_MODULES.contains(segments[2]) ? segments[2] : null;
    }
}
