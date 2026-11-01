package com.dutch.buildlogic;

import static org.gradle.testkit.runner.TaskOutcome.FAILED;
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;

class ServiceDependencyPolicyTest {

    @Test
    void acceptsIndependentServicesAndRejectsAServiceDependingOnAnotherService() {
        var repositoryRoot = new java.io.File(System.getProperty("dutch.root.dir"));

        var validBuild = GradleRunner.create()
                .withProjectDir(repositoryRoot)
                .withArguments("verifyServiceBoundaries", "--stacktrace")
                .build();

        assertEquals(SUCCESS, validBuild.task(":verifyServiceBoundaries").getOutcome());

        var invalidBuild = GradleRunner.create()
                .withProjectDir(repositoryRoot)
                .withArguments("verifyServiceBoundaries", "-PverifyForbiddenServiceDependency", "--stacktrace")
                .buildAndFail();

        assertEquals(FAILED, invalidBuild.task(":verifyServiceBoundaries").getOutcome());
    }
}
