package com.fitpilot.quality;

import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Fails Maven verify when a required test suite is missing or reports skipped tests. */
public final class ZeroSkippedTestsGate {
    private ZeroSkippedTestsGate() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("at least one report directory is required");
        int totalTests = 0;
        int totalSkipped = 0;
        for (String argument : args) {
            Path directory = Path.of(argument);
            List<Path> reports;
            if (!Files.isDirectory(directory)) {
                throw new IllegalStateException("required test report directory is missing: " + directory);
            }
            try (var paths = Files.list(directory)) {
                reports = paths.filter(path -> path.getFileName().toString().startsWith("TEST-")
                                && path.getFileName().toString().endsWith(".xml"))
                        .sorted(Comparator.comparing(Path::toString)).toList();
            }
            if (reports.isEmpty()) {
                throw new IllegalStateException("no test reports found in required directory: " + directory);
            }
            int suiteTests = 0;
            int suiteSkipped = 0;
            for (Path report : reports) {
                Element root = secureFactory().newDocumentBuilder().parse(report.toFile()).getDocumentElement();
                suiteTests += integerAttribute(root, "tests");
                suiteSkipped += integerAttribute(root, "skipped");
            }
            if (suiteTests == 0) {
                throw new IllegalStateException("required test suite executed zero tests: " + directory);
            }
            totalTests += suiteTests;
            totalSkipped += suiteSkipped;
        }
        if (totalSkipped != 0) {
            throw new IllegalStateException("quality gate requires zero skipped tests, found " + totalSkipped);
        }
        System.out.printf("Zero-skipped gate passed: tests=%d skipped=%d%n", totalTests, totalSkipped);
    }

    private static DocumentBuilderFactory secureFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    private static int integerAttribute(Element element, String name) {
        String value = element.getAttribute(name);
        return value.isBlank() ? 0 : Integer.parseInt(value);
    }
}
