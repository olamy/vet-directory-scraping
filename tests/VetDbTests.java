///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//SOURCES ../VetDb.java
//SOURCES vetdb/*.java
//SOURCES vetdb/**/*.java
//DEPS org.junit.jupiter:junit-jupiter:5.11.4
//DEPS org.junit.platform:junit-platform-launcher:1.11.4

import java.io.PrintWriter;

import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;
import static org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request;

/** Runs every JUnit test under the vetdb package. Run it from the project root: jbang tests/VetDbTests.java */
public class VetDbTests {

    public static void main(String[] args) {
        LauncherDiscoveryRequest discovery = request().selectors(selectPackage("vetdb")).build();
        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.execute(discovery, listener);
        TestExecutionSummary summary = listener.getSummary();
        PrintWriter out = new PrintWriter(System.out);
        summary.printFailuresTo(out, 15);
        summary.printTo(out);
        out.flush();
        System.exit(summary.getTotalFailureCount() == 0 && summary.getTestsFoundCount() > 0 ? 0 : 1);
    }
}
