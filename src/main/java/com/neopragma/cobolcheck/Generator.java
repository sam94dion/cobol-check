/*
Copyright 2020 David Nicolette

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.neopragma.cobolcheck;

import com.neopragma.cobolcheck.exceptions.CobolSourceCouldNotBeReadException;
import com.neopragma.cobolcheck.exceptions.PossibleInternalLogicErrorException;
import com.neopragma.cobolcheck.exceptions.TestSuiteCouldNotBeReadException;

import java.io.*;
import java.util.*;

/**
 * This class merges a Test Suite (a text file) with the source of the Cobol program to be tested,
 * producing a Cobol program with the unit test cases embedded in it.
 *
 * @author Dave Nicolette (neopragma)
 * @since 14
 */
public class Generator implements Constants, StringHelper {
    private final Messages messages;
    private final TokenExtractor tokenExtractor;
    private final KeywordExtractor keywordExtractor;

    private final State state = new State();

    private static final String IDENTIFICATION_DIVISION = "IDENTIFICATION DIVISION";
    private static final String ENVIRONMENT_DIVISION = "ENVIRONMENT DIVISION";
    private static final String DATA_DIVISION = "DATA DIVISION";
    private static final String PROCEDURE_DIVISION = "PROCEDURE DIVISION";
    private static final String FILE_SECTION = "FILE SECTION";
    private static final String LOCAL_STORAGE_SECTION = "LOCAL-STORAGE SECTION";
    private static final String LINKAGE_SECTION = "LINKAGE SECTION";
    private static final String WORKING_STORAGE_SECTION = "WORKING-STORAGE SECTION";

    private static final String workingStorageCopybookFilename = "ZUTZCWS.CPY";
    private static final String procedureDivisionCopybookFilename = "ZUTZCPD.CPY";

    private boolean workingStorageTestCodeHasBeenInserted = false;
    private final String workingStorageHeader = fixedLength("       WORKING-STORAGE SECTION.");
    private static String copybookDirectoryName = EMPTY_STRING;

    private Reader secondarySourceReader;
    private KeywordAction nextAction = KeywordAction.NONE;
    private String currentTestSuiteName = EMPTY_STRING;
    private String currentTestCaseName = EMPTY_STRING;
    private List<String> testSuiteTokens;
    private boolean emptyTestSuite;

    // Lines inserted into the test program
    private static final String performUTInitialize = "           PERFORM UT-INITIALIZE";

    public Generator(
            Messages messages,
            TokenExtractor tokenExtractor,
            KeywordExtractor keywordExtractor,
            Config config) {
        this.messages = messages;
        this.tokenExtractor = tokenExtractor;
        this.keywordExtractor = keywordExtractor;
        this.testSuiteTokens = new ArrayList<>();
        this.emptyTestSuite = true;
        copybookDirectoryName = setCopybookDirectoryName(config);
    }

    /**
     * Merge test code with the program under test to produce a Cobol source program
     * that can be compiled and executed to run the test suite.
     *
     * @param testSuite (Reader) Test cases
     * @param cobolSourceIn (Reader) Source of Cobol program under test
     * @param testSourceOut (Writer) Cobol source with test cases merged into program under test
     * @return (Writer) Same Writer object as passed in, populated with Cobol source lines
     */
    public Writer mergeTestSuite(
            Reader testSuite,
            Reader cobolSourceIn,
            Writer testSourceOut) {
        if (testSuite == null) {
            throw new PossibleInternalLogicErrorException(
                    messages.get("ERR001", "testSuite", "Generator.runSuite()"));
        }
        BufferedReader testSuiteReader
                = new BufferedReader(testSuite);
        if (cobolSourceIn == null) {
            throw new PossibleInternalLogicErrorException(
                    messages.get("ERR001", "cobolSourceIn", "Generator.runSuite()"));
        }
        BufferedReader cobolSourceInReader
                = new BufferedReader(cobolSourceIn);
        String sourceLine;
        boolean emptyInputStream = true;
        try {
            while ((sourceLine = cobolSourceInReader.readLine()) != null) {
                emptyInputStream = false;
                sourceLine = fixedLength(sourceLine);
                List<String> tokens = tokenExtractor.extractTokensFrom(sourceLine);
                processingBeforeEchoingTheSourceLineToTheOutput(
                        tokens, sourceLine, cobolSourceInReader, testSourceOut);
                testSourceOut.write(sourceLine);
                processingAfterEchoingTheSourceLineToTheOutput(
                        tokens, sourceLine, testSuiteReader, cobolSourceInReader, testSourceOut);
            }
            cobolSourceInReader.close();
        } catch (IOException ioEx) {
            throw new CobolSourceCouldNotBeReadException(ioEx);
        }
            catch (Exception ex) {
            throw new PossibleInternalLogicErrorException(ex);
        }
        if (emptyInputStream) {
            throw new PossibleInternalLogicErrorException(messages.get("ERR007"));
        }
        return testSourceOut;
    }

    private void processingBeforeEchoingTheSourceLineToTheOutput(
            List<String> tokens,
            String sourceLine,
            Reader reader,
            Writer testSourceOut) throws IOException {

        if (sourceLineContains(tokens, DATA_DIVISION)) entering(DATA_DIVISION);

        if (sourceLineContains(tokens, PROCEDURE_DIVISION)) {
            entering(PROCEDURE_DIVISION);
            if (!workingStorageTestCodeHasBeenInserted) {
                testSourceOut.write(workingStorageHeader);
                insertWorkingStorageTestCode(testSourceOut);
            }
        }
        if (sourceLineContains(tokens, WORKING_STORAGE_SECTION)) entering(WORKING_STORAGE_SECTION);
    }

    private void processingAfterEchoingTheSourceLineToTheOutput(
            List<String> tokens,
            String sourceLine,
            BufferedReader testSuiteReader,
            Reader reader,
            Writer testSourceOut) throws IOException {

        if (sourceLineContains(tokens, WORKING_STORAGE_SECTION)) {
            insertWorkingStorageTestCode(testSourceOut);
        }

        if (sourceLineContains(tokens, PROCEDURE_DIVISION)) {
            insertProcedureDivisionTestCode(testSuiteReader, testSourceOut);
        }
    }

    private void insertWorkingStorageTestCode(Writer testSourceOut) throws IOException {
        secondarySourceReader = new FileReader(copybookFile(workingStorageCopybookFilename));
        insertSecondarySourceIntoTestSource(testSourceOut);
        workingStorageTestCodeHasBeenInserted = true;
    }

    private void insertProcedureDivisionTestCode(
            BufferedReader testSuiteReader,
            Writer testSourceOut) throws IOException {
        testSourceOut.write(fixedLength(performUTInitialize));
        secondarySourceReader = new FileReader(copybookFile(procedureDivisionCopybookFilename));
        insertSecondarySourceIntoTestSource(testSourceOut);
        parseTestSuite(testSuiteReader, testSourceOut);
    }

    private void insertSecondarySourceIntoTestSource(Writer testSourceOut) throws IOException {
        BufferedReader secondarySourceBufferedReader = new BufferedReader(secondarySourceReader);
        String secondarySourceLine = EMPTY_STRING;
        while ((secondarySourceLine = secondarySourceBufferedReader.readLine()) != null) {
            testSourceOut.write(fixedLength(secondarySourceLine));
        }
        secondarySourceBufferedReader.close();
    }

    void parseTestSuite(BufferedReader testSuiteReader, Writer testSourceOut) {
        String testSuiteToken = getNextTokenFromTestSuite(testSuiteReader);
        while (testSuiteToken != null) {
            Keyword keyword = Keywords.getKeywordFor(testSuiteToken);

            switch (nextAction) {
                case TESTSUITE_NAME:
                    currentTestSuiteName = testSuiteToken;
                    nextAction = KeywordAction.NONE;
                    break;
                case TESTCASE_NAME:
                    currentTestCaseName = testSuiteToken;
                    nextAction = KeywordAction.NONE;
                    break;
            }

            switch (keyword.keywordAction()) {
                case TESTSUITE_NAME:
                case TESTCASE_NAME:
                    nextAction = keyword.keywordAction();
            }

            testSuiteToken = getNextTokenFromTestSuite(testSuiteReader);
        }
    }

    private String getNextTokenFromTestSuite(BufferedReader testSuiteReader) {
        while (testSuiteTokens.isEmpty()) {
            String testSuiteLine = readNextLineFromTestSuite(testSuiteReader);
            if (testSuiteLine == null) {
                return null;
            }
            testSuiteTokens = keywordExtractor.extractTokensFrom(testSuiteLine);
        }
        String testSuiteToken = testSuiteTokens.get(0);
        testSuiteTokens.remove(0);
        return testSuiteToken;
    }

    private String readNextLineFromTestSuite(BufferedReader testSuiteReader) {
        String testSuiteLine = EMPTY_STRING;
        try {
            testSuiteLine = testSuiteReader.readLine();
            if (testSuiteLine == null) {
                if (emptyTestSuite) {
                    throw new PossibleInternalLogicErrorException(messages.get("ERR010"));
                }
                return null;
            }
            emptyTestSuite = false;
            return testSuiteLine;
        } catch (IOException ioEx) {
            throw new TestSuiteCouldNotBeReadException(ioEx);
        }
        catch (Exception ex) {
            throw new PossibleInternalLogicErrorException(ex);
        }
    }

    private boolean sourceLineContains(List<String> tokens, String tokenValue) {
        return tokens.size() > 0 && tokens.contains(tokenValue);
    }


    private File copybookFile(String fileName) {
        return new File(copybookDirectoryName + fileName);
    }


    private void entering(String partOfProgram) {
        state.flags.get(partOfProgram).set();
    }

    private String setCopybookDirectoryName(Config config) {
        return config.getString("resources.directory")
                + Constants.FILE_SEPARATOR
                + this.getClass().getPackageName().replace(".", "/")
                + Constants.FILE_SEPARATOR
                + config.getString("cobolcheck.copybook.directory")
                + Constants.FILE_SEPARATOR;
    }

    String getCurrentTestSuiteName() {
        return currentTestSuiteName;
    }

    String getCurrentTestCaseName() {
        return currentTestCaseName;
    }

    class State {
        private final Map<String, Flag> flags;

        public State() {
            flags = new HashMap<>();
            flags.put(FILE_SECTION, new Flag());
            flags.put(LINKAGE_SECTION, new Flag());
            flags.put(LOCAL_STORAGE_SECTION, new Flag());
            flags.put(WORKING_STORAGE_SECTION, new Flag());
            mutuallyExclusiveFlagsFor(FILE_SECTION,
                    LINKAGE_SECTION, LOCAL_STORAGE_SECTION, WORKING_STORAGE_SECTION);
            mutuallyExclusiveFlagsFor(LINKAGE_SECTION,
                    FILE_SECTION, LOCAL_STORAGE_SECTION, WORKING_STORAGE_SECTION);
            mutuallyExclusiveFlagsFor(LOCAL_STORAGE_SECTION,
                    LINKAGE_SECTION, FILE_SECTION, WORKING_STORAGE_SECTION);
            mutuallyExclusiveFlagsFor(WORKING_STORAGE_SECTION,
                    LINKAGE_SECTION, LOCAL_STORAGE_SECTION, FILE_SECTION);

            flags.put(IDENTIFICATION_DIVISION, new Flag());

            flags.put(ENVIRONMENT_DIVISION, new Flag());

            flags.put(DATA_DIVISION, new Flag());
            dependentFlagsFor(DATA_DIVISION,
                    FILE_SECTION, LINKAGE_SECTION, LOCAL_STORAGE_SECTION, WORKING_STORAGE_SECTION);

            flags.put(PROCEDURE_DIVISION, new Flag());

            mutuallyExclusiveFlagsFor(IDENTIFICATION_DIVISION,
                    DATA_DIVISION, ENVIRONMENT_DIVISION, PROCEDURE_DIVISION);
            mutuallyExclusiveFlagsFor(ENVIRONMENT_DIVISION,
                    IDENTIFICATION_DIVISION, DATA_DIVISION, PROCEDURE_DIVISION);
            mutuallyExclusiveFlagsFor(DATA_DIVISION,
                    IDENTIFICATION_DIVISION, ENVIRONMENT_DIVISION, PROCEDURE_DIVISION);
            mutuallyExclusiveFlagsFor(PROCEDURE_DIVISION,
                    IDENTIFICATION_DIVISION, ENVIRONMENT_DIVISION, DATA_DIVISION);


        }

        private void mutuallyExclusiveFlagsFor(String token, String... mutuallyExclusiveTokens) {
            List<Flag> mutuallyExclusiveFlags = new ArrayList<>();
            for (String mutuallyExclusiveToken : mutuallyExclusiveTokens) {
                mutuallyExclusiveFlags.add(flags.get(mutuallyExclusiveToken));
            }
            flags.get(token).setMutuallyExclusiveFlags(mutuallyExclusiveFlags);
        }
        private void dependentFlagsFor(String token, String... dependentTokens) {
            List<Flag> dependentFlags = new ArrayList<>();
            for (String dependentToken : dependentTokens) {
                dependentFlags.add(flags.get(dependentToken));
            }
            flags.get(token).setDependentFlags(dependentFlags);
        }
    }

    static class Flag {
        private boolean state = false;
        private List<Flag> mutuallyExclusiveFlags;
        private List<Flag> dependentFlags;
        public Flag() {
            this.mutuallyExclusiveFlags = new ArrayList<>();
            this.dependentFlags = new ArrayList<>();
        }
        public void setMutuallyExclusiveFlags(List<Flag> mutuallyExclusiveFlags) {
            this.mutuallyExclusiveFlags = mutuallyExclusiveFlags;
        }
        public void setDependentFlags(List<Flag> dependentFlags) {
            this.dependentFlags = dependentFlags;
        }
        public boolean isSet() {
            return state;
        }
        public void set() {
            state = true;
            for (Flag flag : mutuallyExclusiveFlags) {
                flag.unset();
            }
        }
        public void unset() {
            state = false ;
            for (Flag flag : dependentFlags) {
                flag.unset();
            }
        }
    }

}
