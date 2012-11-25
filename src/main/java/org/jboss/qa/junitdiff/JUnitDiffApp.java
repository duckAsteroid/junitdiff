package org.jboss.qa.junitdiff;

import java.io.FileNotFoundException;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.commons.io.IOUtils;
import org.jboss.qa.junitdiff.ex.JUnitDiffException;
import org.jboss.qa.junitdiff.export.XmlExporter;
import org.jboss.qa.junitdiff.model.AggregatedData;
import org.jboss.qa.junitdiff.model.TestSuite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *  Useful comment.
 */
public class JUnitDiffApp
{

	private static final Logger log = LoggerFactory.getLogger(JUnitDiffApp.class);
	private static final String DEFAULT_OUT_FILE = "AGGREGATED_TEST.xml";

	/**
	 *  main ()
	 */
	public static void main(String[] args) {

		log.debug("Starting JUnitDiff - multiple test runs results comparison.");

		if( args.length == 0 ) {
			//log.info(" Usage: junitdiff  ( dir | TEST-foo.xml | list-of-paths.txt )+");
			System.out.println("  Aggregates multiple JUnit XML reports into one comprehensible page.");
			System.out.println("  Usage:");
			System.out.println("    java -jar JUnitDiff.jar -o ('-' | outputPath) ( dir | TEST-foo.xml | list-of-paths.txt | http://host/reports.zip )+");
			System.out.println("");
			System.out.println("    -o      Output file. '-' dumps the result to the stdout. Logging output always goes to the stderr.");
			System.out.println("");
			System.out.println("  Examples:");
			System.out.println("    java -jar JUnitDiff.jar -o - > aggregated-test-report.html");
			System.out.println("    java -jar JUnitDiff.jar -xml -o aggregated-test-report.xml");

			System.exit(1);
		}

		String outFile = null;
		boolean htmlOutput = true;
		boolean stdOut = false;

		// Process arguments...
		for( int i = 0; i < args.length; i++ ) {
			if( "-xml".equals(args[i]) ) {
				htmlOutput = false;
				args[i] = null;
			} else if( "-o".equals(args[i]) ) {
				args[i] = null;
				if( args.length > i ) {
					outFile = args[i + 1];
					args[i + 1] = null;
					i++;
				}
			}
		}

		if( "-".equals(outFile) ) {
			stdOut = true;
			outFile = null;
		}

		if( null == outFile ) {
			outFile = DEFAULT_OUT_FILE + (htmlOutput ? ".html" : "");
		}

		new JUnitDiffApp().runApp(args, outFile, htmlOutput, stdOut);

	}

	/**
	 *  runApp()
	 */
	private void runApp(final String[] paths, final String outFile, boolean htmlOutput, boolean toStdOut) {

		List<File> reportFiles = new ArrayList(paths.length);

		// Handle URLs
		InputPreparation.handleURLs(paths);

		// Check files...
		for( String path : paths ) {
			if( path == null ) {
				continue;
			}

			File reportFile = new File(path);
			if( !reportFile.exists() ) {
				throw new IllegalArgumentException("  File " + reportFile.getPath() + " does not exist.");
			}
			reportFiles.add(reportFile);
		}

		// Preprocess files - scan directories, expand .txt lists of paths.
		//reportFiles = InputPreparation.preprocessPaths( reportFiles );
		// No no. That would stack everything on a pile. Instead,


		// CONSIDER:  Hide atr under aggregatedData, or keep it separated?  TestSuite lists, + map group+name -> testsuite?
		// The top-level result structure.
		AggregatedData aggregatedData = new AggregatedData();


		// Do it arg-by-arg to keep groups.

		for( File file : reportFiles ) {

			String groupName = file.getPath();

			List<File> inList = Collections.singletonList(file);
			List<File> filesToProcess = InputPreparation.preprocessPaths(inList);
			if( filesToProcess.isEmpty() ) {
				log.warn("No report files to process from source '" + groupName + "'.");
				continue;
			}

			List<TestSuite> testSuites = processGroup(groupName, filesToProcess);
			if( testSuites.isEmpty() ) {
				log.warn("No testsuites to process from source '" + groupName + "'.");
				continue;
			}


			// Add to the top-level result structure.

			// Aggregate the results lists - "create columns".
			log.info("Aggregating test results - group: " + groupName + " - " + testSuites.size() + " test suites.");
			try {
				aggregatedData.mergeTestSuites(testSuites, groupName);
			} catch( Exception ex ) {
				log.error("Error when aggregating: " + ex.getMessage(), ex);
				System.exit(3);
			}

		}


		if( aggregatedData.getTestSuites().size() == 0 ) {
			log.error("No results to process.");
			System.exit(4);
		}


		// Export the aggregated matrix to a XML file.
		// TODO: Use a Writer.
		log.info("Exporting to " + outFile);
		if( !htmlOutput ) {
			try {
				XmlExporter.exportToXML(aggregatedData, new File(outFile));
			} catch( FileNotFoundException ex ) {
				log.error("Can't write to file '" + outFile + "': " + ex.getMessage());
				System.exit(5);
			}
		} else {
			try {
				XmlExporter.exportToHtmlFile(aggregatedData, new File(outFile));
			} catch( JUnitDiffException ex ) {
				log.error(ex.getMessage(), ex);
				System.exit(6);
			}
		}

		// Dump to stdout.
		// TODO: Rewrite whole these two parts.
		if( toStdOut ) {
			log.debug("Output goes to stdout.");
			FileReader fileReader = null;
			try {
				fileReader = new FileReader(outFile);
				IOUtils.copy(fileReader, System.out);
			} catch( FileNotFoundException ex ) {
				log.error(ex.toString());
			} catch( IOException ex ) {
				log.error(ex.toString());
			} finally {
				if( fileReader != null ) {
					try {
						fileReader.close();
					} catch( IOException ex ) {
						log.error(ex.toString());
					}
				}
			}

		}


	}// runApp

	/**
	 *
	 * @param groupName  De-facto name of the group.
	 * @param reportFiles
	 *
	 * TODO: Maybe introduce some TestRunGroup class.
	 */
	private static List<TestSuite> processGroup(String groupName, List<File> reportFiles) {

		//  Get the test result lists - one for each XML file or a ".txt" list of XML files.
		log.info("Parsing test reports group '" + groupName + "': " + reportFiles.size() + " files.");
		List<TestSuite> testSuites;
		try {
			testSuites = FileParsing.getSeparatedResultsLists(reportFiles);
		} catch( JUnitDiffException ex ) {
			log.error(ex.getMessage(), ex);
			return Collections.<TestSuite>emptyList();
		}

		for( TestSuite testSuite : testSuites ) {
			testSuite.setGroup(groupName);
		}

		return testSuites;

	}
}// class
