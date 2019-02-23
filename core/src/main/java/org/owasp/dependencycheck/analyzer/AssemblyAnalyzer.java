/*
 * This file is part of dependency-check-core.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright (c) 2012 Jeremy Long. All Rights Reserved.
 */
package org.owasp.dependencycheck.analyzer;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.NullOutputStream;
import org.owasp.dependencycheck.Engine;
import org.owasp.dependencycheck.analyzer.exception.AnalysisException;
import org.owasp.dependencycheck.dependency.Confidence;
import org.owasp.dependencycheck.dependency.Dependency;
import org.owasp.dependencycheck.utils.FileFilterBuilder;
import org.owasp.dependencycheck.utils.FileUtils;
import org.owasp.dependencycheck.utils.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.concurrent.ThreadSafe;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.commons.lang3.StringUtils;
import org.owasp.dependencycheck.exception.InitializationException;
import org.owasp.dependencycheck.dependency.EvidenceType;
import org.owasp.dependencycheck.dependency.naming.GenericIdentifier;
import org.owasp.dependencycheck.utils.ExtractionException;
import org.owasp.dependencycheck.utils.ExtractionUtil;
import org.owasp.dependencycheck.utils.DependencyVersionUtil;
import org.owasp.dependencycheck.utils.XmlUtils;
import org.owasp.dependencycheck.xml.assembly.AssemblyData;
import org.owasp.dependencycheck.xml.assembly.GrokParser;

/**
 * Analyzer for getting company, product, and version information from a .NET
 * assembly.
 *
 * @author colezlaw
 *
 */
@ThreadSafe
public class AssemblyAnalyzer extends AbstractFileTypeAnalyzer {

    /**
     * Logger
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(AssemblyAnalyzer.class);
    /**
     * The analyzer name
     */
    private static final String ANALYZER_NAME = "Assembly Analyzer";
    /**
     * The analysis phase
     */
    private static final AnalysisPhase ANALYSIS_PHASE = AnalysisPhase.INFORMATION_COLLECTION;
    /**
     * The list of supported extensions
     */
    private static final String[] SUPPORTED_EXTENSIONS = {"dll", "exe"};
    /**
     * The File Filter used to filter supported extensions.
     */
    private static final FileFilter FILTER = FileFilterBuilder.newInstance().addExtensions(
            SUPPORTED_EXTENSIONS).build();
    /**
     * The file path to `GrokAssembly.dll`.
     */
    private File grokAssembly = null;

    /**
     * The base argument list to call GrokAssembly.
     */
    private List<String> baseArgumentList = null;

    /**
     * Builds the beginnings of a List for ProcessBuilder
     *
     * @return the list of arguments to begin populating the ProcessBuilder
     */
    protected List<String> buildArgumentList() {
        // Use file.separator as a wild guess as to whether this is Windows
        final List<String> args = new ArrayList<>();
        if (!StringUtils.isEmpty(getSettings().getString(Settings.KEYS.ANALYZER_ASSEMBLY_DOTNET_PATH))) {
            args.add(getSettings().getString(Settings.KEYS.ANALYZER_ASSEMBLY_DOTNET_PATH));
        } else if (isDotnetPath()) {
            args.add("dotnet");
        } else {
            return null;
        }
        args.add(grokAssembly.getPath());
        return args;
    }

    /**
     * Performs the analysis on a single Dependency.
     *
     * @param dependency the dependency to analyze
     * @param engine the engine to perform the analysis under
     * @throws AnalysisException if anything goes sideways
     */
    @Override
    public void analyzeDependency(Dependency dependency, Engine engine) throws AnalysisException {
        final File test = new File(dependency.getActualFilePath());
        if (!test.isFile()) {
            throw new AnalysisException(String.format("%s does not exist and cannot be analyzed by dependency-check",
                    dependency.getActualFilePath()));
        }
        if (grokAssembly == null) {
            LOGGER.warn("GrokAssembly didn't get deployed");
            return;
        }
        if (baseArgumentList == null) {
            LOGGER.warn("Assembly Analyzer was unable to execute");
            return;
        }
        final List<String> args = new ArrayList<>(baseArgumentList);
        args.add(dependency.getActualFilePath());
        final ProcessBuilder pb = new ProcessBuilder(args);
        final Document doc;
        try {
            final Process proc = pb.start();
            GrokParser parser = new GrokParser();
            AssemblyData data = parser.parse(proc.getInputStream());

            // Try evacuating the error stream
            final String errorStream = IOUtils.toString(proc.getErrorStream(), StandardCharsets.UTF_8);
            if (null != errorStream && !errorStream.isEmpty()) {
                LOGGER.warn("Error from GrokAssembly: {}", errorStream);
            }

            final int rc;
            try {
                rc = proc.waitFor();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            if (rc == 3) {
                LOGGER.debug("{} is not a .NET assembly or executable and as such cannot be analyzed by dependency-check",
                        dependency.getActualFilePath());
                return;
            } else if (rc != 0) {
                LOGGER.debug("Return code {} from GrokAssembly; dependency-check is unable to analyze the library: {}",
                        rc, dependency.getActualFilePath());
                return;
            }

            // First, see if there was an error
            final String error = data.getError();
            if (error != null && !error.isEmpty()) {
                throw new AnalysisException(error);
            }
            if (data.getWarning() != null) {
                LOGGER.debug(data.getWarning());
            }
            if (!StringUtils.isEmpty(data.getProductVersion())) {
                dependency.addEvidence(EvidenceType.VERSION, "grokassembly", "ProductVersion", data.getProductVersion(), Confidence.HIGHEST);
            }

            if (!StringUtils.isEmpty(data.getFileVersion())) {
                dependency.addEvidence(EvidenceType.VERSION, "grokassembly", "FileVersion", data.getFileVersion(), Confidence.HIGHEST);
                if ((data.getFileVersion()).equals(data.getProductVersion()) || (data.getFileVersion()).startsWith(data.getProductVersion())) {
                    dependency.setVersion(data.getFileVersion());
                }
            }
            if (!StringUtils.isEmpty(data.getCompanyName())) {
                dependency.addEvidence(EvidenceType.VENDOR, "grokassembly", "CompanyName", data.getCompanyName(), Confidence.HIGHEST);
            }

            if (!StringUtils.isEmpty(data.getProductName())) {
                dependency.addEvidence(EvidenceType.PRODUCT, "grokassembly", "ProductName", data.getProductName(), Confidence.HIGHEST);
            }

            if (!StringUtils.isEmpty(data.getFileDescription())) {
                dependency.addEvidence(EvidenceType.PRODUCT, "grokassembly", "FileDescription", data.getFileDescription(), Confidence.HIGH);
            }

            final String internalName = data.getInternalName();
            if (!StringUtils.isEmpty(internalName)) {
                dependency.addEvidence(EvidenceType.PRODUCT, "grokassembly", "InternalName", internalName, Confidence.MEDIUM);
                addMatchingValues(data.getNamespaces(), internalName, dependency, EvidenceType.PRODUCT);
                addMatchingValues(data.getNamespaces(), internalName, dependency, EvidenceType.VENDOR);
                if (dependency.getName() == null && StringUtils.containsIgnoreCase(dependency.getActualFile().getName(), internalName)) {
                    final String ext = FileUtils.getFileExtension(internalName);
                    if (ext != null) {
                        dependency.setName(internalName.substring(0, internalName.length() - ext.length() - 1));
                    } else {
                        dependency.setName(internalName);
                    }
                }
            }

            final String originalFilename = data.getOriginalFilename();
            if (!StringUtils.isEmpty(originalFilename)) {
                dependency.addEvidence(EvidenceType.PRODUCT, "grokassembly", "OriginalFilename", originalFilename, Confidence.MEDIUM);
                addMatchingValues(data.getNamespaces(), originalFilename, dependency, EvidenceType.PRODUCT);
                if (dependency.getName() == null && StringUtils.containsIgnoreCase(dependency.getActualFile().getName(), originalFilename)) {
                    final String ext = FileUtils.getFileExtension(originalFilename);
                    if (ext != null) {
                        dependency.setName(originalFilename.substring(0, originalFilename.length() - ext.length() - 1));
                    } else {
                        dependency.setName(originalFilename);
                    }
                }
            }
            if (dependency.getName() != null && dependency.getVersion() != null) {
                dependency.addSoftwareIdentifier(new GenericIdentifier(
                        String.format("%s@%s", dependency.getName(), dependency.getVersion()),
                        Confidence.MEDIUM));
            }
        } catch (IOException ioe) {
            throw new AnalysisException(ioe);
        } catch (SAXException saxe) {
            LOGGER.error("----------------------------------------------------");
            LOGGER.error("Failed to read the Assembly Analyzer results.");
            LOGGER.error("----------------------------------------------------");
            throw new AnalysisException("Couldn't parse Assembly Analyzer results (GrokAssembly)", saxe);
        }
    }

    /**
     * Initialize the analyzer. In this case, extract GrokAssembly.dll to a
     * temporary location.
     *
     * @param engine a reference to the dependency-check engine
     * @throws InitializationException thrown if anything goes wrong
     */
    @Override
    public void prepareFileTypeAnalyzer(Engine engine) throws InitializationException {
        final File location;
        try {
            location = FileUtils.createTempDirectory(getSettings().getTempDirectory());
            ExtractionUtil.extractFiles(FileUtils.getResourceAsStream("GrokAssembly.zip"), location);
        } catch (ExtractionException ex) {
            throw new InitializationException("Unable to extract GrokAssembly.dll", ex);
        } catch (IOException ex) {
            throw new InitializationException("Unable to create temp directory for GrokAssembly", ex);
        }

        grokAssembly = new File(location, "GrokAssembly.dll");

        // Now, need to see if GrokAssembly actually runs from this location.
        baseArgumentList = buildArgumentList();
        //TODO this creates an "unreported" error - if someone doesn't look
        // at the command output this could easily be missed (especially in an
        // Ant or Maven build.
        //
        // We need to create a non-fatal warning error type that will
        // get added to the report.
        //TODO this idea needs to get replicated to the bundle audit analyzer.
        if (baseArgumentList == null) {
            setEnabled(false);
            LOGGER.error("----------------------------------------------------");
            LOGGER.error(".NET Assembly Analyzer could not be initialized and at least one "
                    + "'exe' or 'dll' was scanned. The 'dotnet' executable could not be found on "
                    + "the path; either disable the Assembly Analyzer or configure the path dotnet core.");
            LOGGER.error("----------------------------------------------------");
            return;
        }
        try {
            final ProcessBuilder pb = new ProcessBuilder(baseArgumentList);
            final Process p = pb.start();
            // Try evacuating the error stream
            IOUtils.copy(p.getErrorStream(), NullOutputStream.NULL_OUTPUT_STREAM);

            final DocumentBuilder builder = XmlUtils.buildSecureDocumentBuilder();
            final Document doc = builder.parse(p.getInputStream());
            final XPath xpath = XPathFactory.newInstance().newXPath();
            final String error = xpath.evaluate("/assembly/error", doc);
            if (p.waitFor() != 1 || error == null || error.isEmpty()) {
                LOGGER.warn("An error occurred with the .NET AssemblyAnalyzer, please see the log for more details.");
                LOGGER.debug("GrokAssembly.dll is not working properly");
                grokAssembly = null;
                setEnabled(false);
                throw new InitializationException("Could not execute .NET AssemblyAnalyzer");
            }
        } catch (InitializationException e) {
            setEnabled(false);
            throw e;
        } catch (IOException | ParserConfigurationException | SAXException | XPathExpressionException | InterruptedException e) {
            LOGGER.warn("An error occurred with the .NET AssemblyAnalyzer;\n"
                    + "this can be ignored unless you are scanning .NET DLLs. Please see the log for more details.");
            LOGGER.debug("Could not execute GrokAssembly {}", e.getMessage());
            setEnabled(false);
            throw new InitializationException("An error occurred with the .NET AssemblyAnalyzer", e);
        }
    }

    /**
     * Removes resources used from the local file system.
     *
     * @throws Exception thrown if there is a problem closing the analyzer
     */
    @Override
    public void closeAnalyzer() throws Exception {
        FileUtils.delete(grokAssembly.getParentFile());
    }

    @Override
    protected FileFilter getFileFilter() {
        return FILTER;
    }

    /**
     * Gets this analyzer's name.
     *
     * @return the analyzer name
     */
    @Override
    public String getName() {
        return ANALYZER_NAME;
    }

    /**
     * Returns the phase this analyzer runs under.
     *
     * @return the phase this runs under
     */
    @Override
    public AnalysisPhase getAnalysisPhase() {
        return ANALYSIS_PHASE;
    }

    /**
     * Returns the key used in the properties file to reference the analyzer's
     * enabled property.
     *
     * @return the analyzer's enabled property setting key
     */
    @Override
    protected String getAnalyzerEnabledSettingKey() {
        return Settings.KEYS.ANALYZER_ASSEMBLY_ENABLED;
    }

    /**
     * Tests to see if a file is in the system path. <b>Note</b> - the current
     * implementation only works on non-windows platforms. For purposes of the
     * AssemblyAnalyzer this is okay as this is only needed on Mac/*nix.
     *
     * @param file the executable to look for
     * @return <code>true</code> if the file exists; otherwise
     * <code>false</code>
     */
    private boolean isDotnetPath() {
        String[] args = new String[2];
        args[0] = "dotnet";
        args[1] = "--version";
        final ProcessBuilder pb = new ProcessBuilder(args);
        try {
            Map<String, String> envs = pb.environment();
            LOGGER.error("Path: " + envs.get("Path"));
            LOGGER.error("PATH: " + envs.get("PATH"));
            LOGGER.error("SYSE: " + System.getProperty("PATH"));
            System.getProperties().entrySet().forEach(System.out::println);
            final Process proc = pb.start();
            int retCode = proc.waitFor();
            if (retCode == 0) {
                return true;
            }
            byte[] version = new byte[50];
            proc.getInputStream().read(version);
            String v = new String(version);
            if (v.length() > 0) {
                return true;
            }
        } catch (IOException | InterruptedException ex) {
            LOGGER.debug("Path search failed for dotnet", ex);
        }
        return false;
    }

    /**
     * Cycles through the collection of class name information to see if parts
     * of the package names are contained in the provided value. If found, it
     * will be added as the HIGHEST confidence evidence because we have more
     * then one source corroborating the value.
     *
     * @param packages a collection of class name information
     * @param value the value to check to see if it contains a package name
     * @param dep the dependency to add new entries too
     * @param type the type of evidence (vendor, product, or version)
     */
    protected static void addMatchingValues(List<String> packages, String value, Dependency dep, EvidenceType type) {
        if (value == null || value.isEmpty() || packages == null || packages.isEmpty()) {
            return;
        }
        for (String key : packages) {
            final int pos = StringUtils.indexOfIgnoreCase(value, key);
            if ((pos == 0 && (key.length() == value.length() || key.length() < value.length()
                    && !Character.isLetterOrDigit(value.charAt(key.length()))))
                    || (pos > 0 && !Character.isLetterOrDigit(value.charAt(pos - 1))
                    && (pos + key.length() == value.length() || key.length() < value.length()
                    && !Character.isLetterOrDigit(value.charAt(pos + key.length()))))) {
                dep.addEvidence(type, "dll", "namespace", key, Confidence.HIGHEST);
            }

        }
    }

    /**
     * Used in testing only - this simply returns the path to the extracted
     * GrokAssembly.dll.
     *
     * @return the path to the extracted GrokAssembly.dll
     */
    File getGrokAssemblyPath() {
        return grokAssembly;
    }
}
