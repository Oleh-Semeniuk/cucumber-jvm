package cucumber.runner;

import cucumber.api.HookType;
import cucumber.api.StepDefinitionReporter;
import cucumber.api.TestCase;
import cucumber.api.TestStep;
import cucumber.runtime.AmbiguousStepDefinitionsMatch;
import cucumber.runtime.AmbiguousStepDefinitionsException;
import cucumber.runtime.Backend;
import cucumber.runtime.FailedStepInstantiationMatch;
import cucumber.runtime.Glue;
import cucumber.runtime.HookDefinition;
import cucumber.runtime.HookDefinitionMatch;
import cucumber.runtime.RuntimeOptions;
import cucumber.runtime.StepDefinitionMatch;
import cucumber.runtime.StopWatch;
import cucumber.runtime.UndefinedStepDefinitionMatch;
import cucumber.runtime.UnreportedStepExecutor;
import gherkin.pickles.Argument;
import gherkin.pickles.Pickle;
import gherkin.pickles.PickleLocation;
import gherkin.pickles.PickleRow;
import gherkin.pickles.PickleStep;
import gherkin.pickles.PickleString;
import gherkin.pickles.PickleTable;
import gherkin.pickles.PickleTag;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class Runner implements UnreportedStepExecutor {
    private final Glue glue;
    private final EventBus bus;
    private final Collection<? extends Backend> backends;
    private final RuntimeOptions runtimeOptions;
    private final StopWatch stopWatch;

    public Runner(Glue glue, EventBus bus, Collection<? extends Backend> backends, RuntimeOptions runtimeOptions, StopWatch stopWatch) {
        this.glue = glue;
        this.bus = bus;
        this.runtimeOptions = runtimeOptions;
        this.stopWatch = stopWatch;
        this.backends = backends;
        for (Backend backend : backends) {
            backend.loadGlue(glue, runtimeOptions.getGlue());
            backend.setUnreportedStepExecutor(this);
        }

    }

    //TODO: Maybe this should go into the cucumber step execution model and it should return the result of that execution!
    @Override
    public void runUnreportedStep(String featurePath, String language, String stepName, int line, List<PickleRow> dataTableRows, PickleString docString) throws Throwable {
        List<Argument> arguments = new ArrayList<Argument>();
        if (dataTableRows != null && !dataTableRows.isEmpty()) {
            arguments.add((Argument) new PickleTable(dataTableRows));
        } else if (docString != null) {
            arguments.add(docString);
        }
        PickleStep step = new PickleStep(stepName, arguments, Collections.<PickleLocation>emptyList());

        StepDefinitionMatch match = glue.stepDefinitionMatch(featurePath, step);
        if (match == null) {
            UndefinedStepException error = new UndefinedStepException(step);

            StackTraceElement[] originalTrace = error.getStackTrace();
            StackTraceElement[] newTrace = new StackTraceElement[originalTrace.length + 1];
            newTrace[0] = new StackTraceElement("✽", "StepDefinition", featurePath, line);
            System.arraycopy(originalTrace, 0, newTrace, 1, originalTrace.length);
            error.setStackTrace(newTrace);

            throw error;
        }
        match.runStep(language, null);
    }

    public void runPickle(Pickle pickle, String language) {
        buildBackendWorlds(); // Java8 step definitions will be added to the glue here
        TestCase testCase = createTestCaseForPickle(pickle);
        testCase.run(bus, language);
        disposeBackendWorlds();
    }

    public Glue getGlue() {
        return glue;
    }


    public void reportStepDefinitions(StepDefinitionReporter stepDefinitionReporter) {
        glue.reportStepDefinitions(stepDefinitionReporter);
    }

    private TestCase createTestCaseForPickle(Pickle pickle) {
        List<PickleTag> tags;
        try { // TODO: Fix when Gherkin provide a getter for the tags.
            Field f;
            f = pickle.getClass().getDeclaredField("tags");
            f.setAccessible(true);
            tags = (List<PickleTag>) f.get(pickle);
        } catch (Exception e) {
            tags = Collections.<PickleTag>emptyList();
        }
        List<TestStep> testSteps = new ArrayList<TestStep>();
        if (!runtimeOptions.isDryRun()) {
            addTestStepsForBeforeHooks(testSteps, tags);
        }
        addTestStepsForPickleSteps(testSteps, pickle);
        if (!runtimeOptions.isDryRun()) {
            addTestStepsForAfterHooks(testSteps, tags);
        }
        TestCase testCase = new TestCase(testSteps, pickle);
        return testCase;
    }

    private void addTestStepsForPickleSteps(List<TestStep> testSteps, Pickle pickle) {
        for (PickleStep step : pickle.getSteps()) {
            StepDefinitionMatch match;
            try {
                match = glue.stepDefinitionMatch(pickle.getLocations().get(0).getPath(), step);
                if (match == null) {
                    List<String> snippets = new ArrayList<String>();
                    for (Backend backend : backends) {
                        String snippet = backend.getSnippet(step, "**KEYWORD**", runtimeOptions.getSnippetType().getFunctionNameGenerator());
                        if (snippet != null) {
                            snippets.add(snippet);
                        }
                    }
                    match = new UndefinedStepDefinitionMatch(step, snippets);
                }
            } catch (AmbiguousStepDefinitionsException e) {
                match = new AmbiguousStepDefinitionsMatch(step, e);
            } catch (Throwable t) {
                match = new FailedStepInstantiationMatch(step, t);
            }
            testSteps.add(new PickleTestStep(step, match, stopWatch));
        }
    }

    private void addTestStepsForBeforeHooks(List<TestStep> testSteps, List<PickleTag> tags) {
        addTestStepsForHooks(testSteps, tags, glue.getBeforeHooks(), HookType.Before);
    }

    private void addTestStepsForAfterHooks(List<TestStep> testSteps, List<PickleTag> tags) {
        addTestStepsForHooks(testSteps, tags, glue.getAfterHooks(), HookType.After);
    }

    private void addTestStepsForHooks(List<TestStep> testSteps, List<PickleTag> tags,  List<HookDefinition> hooks, HookType hookType) {
        for (HookDefinition hook : hooks) {
            if (hook.matches(tags)) {
                TestStep testStep = new UnskipableStep(hookType, new HookDefinitionMatch(hook), stopWatch);
                testSteps.add(testStep);
            }
        }
    }

    private void buildBackendWorlds() {
        runtimeOptions.getPlugins(); // To make sure that the plugins are instantiated after
        // the features have been parsed but before the pickles starts to execute.
        for (Backend backend : backends) {
            backend.buildWorld();
        }
    }

    private void disposeBackendWorlds() {
        for (Backend backend : backends) {
            backend.disposeWorld();
        }
    }
}