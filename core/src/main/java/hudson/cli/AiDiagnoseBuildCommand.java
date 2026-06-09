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

import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.PermalinkProjectAction.Permalink;
import hudson.model.Run;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

@Extension
public class AiDiagnoseBuildCommand extends CLICommand {

    @Override
    public String getShortDescription() {
        return Messages.AiDiagnoseBuildCommand_ShortDescription();
    }

    @Argument(metaVar = "JOB", usage = "Name of the job", required = true)
    public Job<?, ?> job;

    @Argument(metaVar = "BUILD", usage = "Build number or permalink to diagnose. Defaults to the last build", required = false, index = 1)
    public String build = "lastBuild";

    @Option(name = "-n", metaVar = "N", usage = "Number of trailing log lines to analyze (default 200)")
    public int n = 200;

    /**
     * A single heuristic: a pattern to look for, a human-readable category, and advice.
     */
    private static final class Rule {
        final Pattern pattern;
        final String category;
        final String advice;

        Rule(String regex, String category, String advice) {
            this.pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            this.category = category;
            this.advice = advice;
        }
    }

    /** Rules are evaluated in order; earlier (more specific) rules win on ties. */
    private static final List<Rule> RULES = List.of(
            new Rule("OutOfMemoryError|Java heap space|GC overhead limit",
                    "Out of memory",
                    "The JVM ran out of heap. Increase the memory available (e.g. raise -Xmx) or reduce the workload/parallelism of this build."),
            new Rule("No space left on device|disk quota exceeded",
                    "Out of disk space",
                    "The agent's disk filled up. Clean the workspace, prune old artifacts/Docker images, or move the build to an agent with more free space."),
            new Rule("cannot find symbol|incompatible types|';' expected|error: .*javac|compilation failure|COMPILATION ERROR",
                    "Compilation error",
                    "Source code failed to compile. Look at the first 'error:' line above for the file and line, fix the syntax/type error, and rebuild."),
            new Rule("Tests run:.*Failures: [1-9]|Tests run:.*Errors: [1-9]|There were failing tests|AssertionError|expected:.*but was:",
                    "Failing tests",
                    "One or more tests failed. Open the test report for the failing test name and assertion, reproduce it locally, and fix the code or the test."),
            new Rule("Could not resolve|Could not transfer|Could not find artifact|Failed to read artifact descriptor|Non-resolvable",
                    "Dependency resolution failure",
                    "A dependency could not be downloaded. Check the version/coordinates, your repository/mirror configuration, and that the artifact actually exists."),
            new Rule("UnknownHostException|ConnectException|Connection refused|Connection timed out|SocketTimeoutException|Read timed out",
                    "Network/connectivity issue",
                    "A network call failed or timed out. Verify the remote host is reachable from the agent, check proxy/DNS/firewall settings, and retry."),
            new Rule("Permission denied|AccessDeniedException|HTTP 401|HTTP 403|Authentication failed|not authorized",
                    "Permission / authentication failure",
                    "Access was denied. Check credentials, tokens, and file/registry permissions for the user running this build."),
            new Rule("fatal: |couldn't find remote ref|Merge conflict|CONFLICT \\(content\\)|did not match any file\\(s\\) known to git",
                    "Source control (git) error",
                    "A git operation failed. Verify the branch/ref exists, credentials are valid, and resolve any merge conflicts."),
            new Rule("script returned exit code|marked build as failure|Build step .* marked the build as failure|returned non-zero",
                    "Build step returned a non-zero exit code",
                    "A shell/batch step failed. Scroll up to the last command that ran and inspect its output for the underlying error.")
    );

    @Override
    protected int run() throws Exception {
        job.checkPermission(Item.READ);

        final Run<?, ?> run = resolveBuild();

        if (n <= 0) {
            n = 200;
        }
        final List<String> lines = run.getLog(n);

        stdout.println("=== AI Build Diagnosis ===");
        stdout.println("Job:    " + job.getFullDisplayName());
        stdout.println("Build:  #" + run.getNumber());
        stdout.println("Result: " + (run.getResult() == null ? "IN PROGRESS" : run.getResult().toString()));
        stdout.println("Scanned the last " + lines.size() + " log line(s).");
        stdout.println();

        final List<String> findings = new ArrayList<>();
        Rule top = null;
        String topEvidence = null;

        for (Rule rule : RULES) {
            for (String line : lines) {
                if (rule.pattern.matcher(line).find()) {
                    findings.add(rule.category);
                    if (top == null) {
                        top = rule;
                        topEvidence = line.strip();
                    }
                    break; // one hit per rule is enough
                }
            }
        }

        if (top == null) {
            stdout.println("Diagnosis: No known failure signature was detected in the analyzed log.");
            if (run.getResult() != null && run.getResult().isWorseThan(hudson.model.Result.SUCCESS)) {
                stdout.println("The build did not succeed, but the cause isn't one of the common patterns.");
                stdout.println("Suggestion: re-run with more log lines (-n) or inspect the full console output.");
            } else {
                stdout.println("This build looks healthy. 🎉");
            }
            return 0;
        }

        stdout.println("Most likely cause: " + top.category);
        stdout.println("Suggested fix:     " + top.advice);
        stdout.println();
        stdout.println("Evidence (log line):");
        stdout.println("  " + abbreviate(topEvidence, 200));

        if (findings.size() > 1) {
            stdout.println();
            stdout.println("Other signals also detected:");
            for (int i = 1; i < findings.size(); i++) {
                stdout.println("  - " + findings.get(i));
            }
        }

        return 0;
    }

    /** Resolve the {@link Run} from the build number or a permalink, mirroring {@code ConsoleCommand}. */
    private Run<?, ?> resolveBuild() {
        try {
            int number = Integer.parseInt(build);
            Run<?, ?> run = job.getBuildByNumber(number);
            if (run == null) {
                throw new IllegalArgumentException("No such build #" + number);
            }
            return run;
        } catch (NumberFormatException e) {
            Permalink p = job.getPermalinks().get(build);
            if (p != null) {
                Run<?, ?> run = p.resolve(job);
                if (run == null) {
                    throw new IllegalStateException("Permalink " + build + " produced no build", e);
                }
                return run;
            }
            Permalink nearest = job.getPermalinks().findNearest(build);
            throw new IllegalArgumentException(nearest == null
                    ? String.format("Not sure what you meant by \"%s\".", build)
                    : String.format("Not sure what you meant by \"%s\". Did you mean \"%s\"?", build, nearest.getId()), e);
        }
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
