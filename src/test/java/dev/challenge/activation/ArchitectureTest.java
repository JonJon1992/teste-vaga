package dev.challenge.activation;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

@AnalyzeClasses(packages = "dev.challenge.activation", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String API = "dev.challenge.activation.api..";
    private static final String APPLICATION = "dev.challenge.activation.application..";
    private static final String DOMAIN = "dev.challenge.activation.domain..";
    private static final String INFRASTRUCTURE = "dev.challenge.activation.infrastructure..";

    @ArchTest
    static final ArchRule layersRespectTheDependencyDirection = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Api").definedBy(API)
            .layer("Application").definedBy(APPLICATION)
            .layer("Domain").definedBy(DOMAIN)
            .layer("Infrastructure").definedBy(INFRASTRUCTURE)
            .whereLayer("Api").mayNotBeAccessedByAnyLayer()
            .whereLayer("Infrastructure").mayNotBeAccessedByAnyLayer()
            .whereLayer("Application").mayOnlyBeAccessedByLayers("Api", "Infrastructure")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Api", "Infrastructure");

    @ArchTest
    static final ArchRule domainDoesNotDependOnFrameworks = noClasses()
            .that().resideInAPackage(DOMAIN)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "com.fasterxml..", "io.micrometer..", "org.slf4j..", "org.hibernate..")
            .because("o domínio deve ser testável e evoluir sem depender de frameworks");

    @ArchTest
    static final ArchRule applicationDoesNotDependOnWebOrPersistenceDetails = noClasses()
            .that().resideInAPackage(APPLICATION)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.web..", "org.springframework.data.jpa..", "jakarta.persistence..",
                    "jakarta.servlet..", "org.hibernate..");
}
