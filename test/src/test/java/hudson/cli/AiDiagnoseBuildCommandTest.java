/*
 * The MIT License
 *
 * Copyright (c) 2026, Yu-Tsu Chang
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package hudson.cli;

import static hudson.cli.CLICommandInvoker.Matcher.failedWith;
import static hudson.cli.CLICommandInvoker.Matcher.hasNoStandardOutput;
import static hudson.cli.CLICommandInvoker.Matcher.succeeded;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import hudson.Functions;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.tasks.BatchFile;
import hudson.tasks.Shell;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Tests for CLI command {@link AiDiagnoseBuildCommand ai-diagnose-build}.
 */
@WithJenkins
class AiDiagnoseBuildCommandTest {

    private CLICommandInvoker command;

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
        command = new CLICommandInvoker(j, "ai-diagnose-build");
    }

    @Test
    void diagnoseShouldFailWithoutJobReadPermission() throws Exception {
        j.createFreeStyleProject("aProject");

        final CLICommandInvoker.Result result = command
                .authorizedTo(Jenkins.READ)
                .invokeWithArgs("aProject");

        assertThat(result, failedWith(3));
        assertThat(result, hasNoStandardOutput());
        assertThat(result.stderr(), containsString("ERROR: No such job 'aProject'"));
    }

    @Test
    void diagnoseShouldReportHealthyBuild() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject("aProject");
        project.getBuildersList().add(Functions.isWindows() ? new BatchFile("echo hello") : new Shell("echo hello"));
        j.buildAndAssertSuccess(project);

        final CLICommandInvoker.Result result = command
                .authorizedTo(Jenkins.READ, Item.READ, Item.BUILD)
                .invokeWithArgs("aProject");

        assertThat(result, succeeded());
        assertThat(result.stdout(), containsString("Result: SUCCESS"));
        assertThat(result.stdout(), containsString("No known failure signature"));
        assertThat(result.stdout(), not(containsString("Most likely cause:")));
    }

    @Test
    void diagnoseShouldDetectOutOfMemory() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject("aProject");
        // Emit a recognizable failure signature in the log.
        String script = "echo java.lang.OutOfMemoryError: Java heap space";
        project.getBuildersList().add(Functions.isWindows() ? new BatchFile(script) : new Shell(script));
        j.buildAndAssertSuccess(project);

        final CLICommandInvoker.Result result = command
                .authorizedTo(Jenkins.READ, Item.READ, Item.BUILD)
                .invokeWithArgs("aProject");

        assertThat(result, succeeded());
        assertThat(result.stdout(), containsString("Most likely cause: Out of memory"));
        assertThat(result.stdout(), containsString("Suggested fix:"));
        assertThat(result.stdout(), containsString("Evidence (log line):"));
    }

    @Test
    void diagnoseShouldFailWhenBuildDoesNotExist() throws Exception {
        j.createFreeStyleProject("aProject");

        final CLICommandInvoker.Result result = command
                .authorizedTo(Jenkins.READ, Item.READ, Item.BUILD)
                .invokeWithArgs("aProject", "1");

        assertThat(result, failedWith(3));
        assertThat(result, hasNoStandardOutput());
        assertThat(result.stderr(), containsString("ERROR: No such build #1"));
    }
}
