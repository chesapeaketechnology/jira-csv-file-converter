package us.ctic.jira;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Main
{
    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final Config config = ConfigFactory.load();

    private static final String CONFIG_PREFIX = "us.ctic.jira.";
    private static final String SOURCE = "source";
    private static final String TARGET = "target";
    private static final String NO_TARGET_MATCH = "NO_TARGET_MATCH";
    private static final String ISSUE_TYPE_CSV_KEY = "Issue Type";
    private static final int SPLIT_COUNT = config.getInt("us.ctic.jira.splitFileMaxIssueCount");

    private static boolean createUserMap = false;
    private static boolean updateCsvFile = false;
    private static boolean createIssueTypeMap = false;
    private static JiraService sourceJiraService;
    private static JiraService targetJiraService;

    /**
     * Main method for updating Jira CSV files for porting to a new instance.
     *
     * @param args Command line arguments; allowed arguments are:
     *             -m   Create a file mapping usernames found in the CSV to usernames in the target Jira instance.
     *             -i   Create issue type map file
     *             -u   Update the provided CSV file to replace usernames using the mapping
     */
    public static void main(String[] args)
    {
        // First determine which tasks will be executed this run
        processCommandLineArguments(args);

        List<String> sourceCsvFileNames = getSourceFileNames();

        Set<String> csvUsernames = new HashSet<>();
        if (createUserMap || updateCsvFile)
        {
            // Next we need to find all the unique usernames in the exported CSV file(s). If we are creating a user
            // mapping, these will be mapped and stored in a file. If we are updating the CSV file, they will be
            // replaced from the user mapping.
            logger.info("Extracting usernames from: {}", sourceCsvFileNames);
            csvUsernames.addAll(sourceCsvFileNames.stream()
                    .map(UsernameExtractor::new)
                    .map(UsernameExtractor::extractUserNames)
                    .flatMap(Collection::stream)
                    .collect(Collectors.toSet()));
        }

        // We need to connect to the servers for either mapping task
        if (createUserMap || createIssueTypeMap)
        {
            logger.info("Connecting to Jira servers...");
            sourceJiraService = getJiraService(SOURCE);
            targetJiraService = getJiraService(TARGET);
        }

        Map<String, String> usernameMapping;
        String userMapCsvFileName = config.getString("us.ctic.jira.userMapFileName");
        if (createUserMap)
        {
            // Query the Jira instances to try to match usernames on the source instance to usernames on the target instance
            usernameMapping = createUsernameMapping(csvUsernames);

            // Write the mapping to a file so we don't have to do this again next time since it takes so long
            logger.info("Writing user type mapping to file {}", userMapCsvFileName);
            writeMappingToFile(usernameMapping, userMapCsvFileName, "User Mapping");
        } else
        {
            usernameMapping = populateMappingFromFile(userMapCsvFileName);
            logger.info("Populated user type mapping from file {}", userMapCsvFileName);
        }

        Map<String, String> issueTypeMapping;
        String issueTypeMapCsvFileName = config.getString("us.ctic.jira.issueTypeMapFileName");
        if (createIssueTypeMap)
        {
            issueTypeMapping = createIssueTypeMapping();
            logger.info("Writing issue type mapping to file {}: {}", issueTypeMapCsvFileName, issueTypeMapping);
            writeMappingToFile(issueTypeMapping, issueTypeMapCsvFileName, "Issue Type Mapping");
        } else
        {
            issueTypeMapping = populateMappingFromFile(issueTypeMapCsvFileName);
            logger.info("Populated issue type mapping from file {}: {}", issueTypeMapCsvFileName, issueTypeMapping);
        }

        if (updateCsvFile)
        {
            issueTypeMapping = cleanMappings(issueTypeMapping);

            List<Map<String, String>> mappingsList = new ArrayList<>();
            mappingsList.add(usernameMapping);
            mappingsList.add(issueTypeMapping);

            String targetCsvFileName = config.getString("us.ctic.jira.target.csvFileName");
            updateCsvFileWithMappings(sourceCsvFileNames, targetCsvFileName, mappingsList);

            final EmptyColumnRemover emptyColumnRemover = new EmptyColumnRemover(targetCsvFileName);
            final String newTargetCsvFileName = emptyColumnRemover.removeEmptyColumns();

            if (!issueTypeMapping.isEmpty())
            {
                String splitFolder = config.getString("us.ctic.jira.target.csvFolderName");
                orderByIssueTypeAndSplitCsvFileByCount(newTargetCsvFileName, splitFolder, issueTypeMapping);
            }
        }
    }

    /**
     * Parse the command line arguments and set the appropriate variables.
     *
     * @param args The command line arguments passed to the main method
     */
    private static void processCommandLineArguments(String[] args)
    {
        logger.info("args: {}", (Object[]) args);

        for (String arg : args)
        {
            switch (arg)
            {
                case "-m":
                    createUserMap = true;
                    break;
                case "-i":
                    createIssueTypeMap = true;
                    break;
                case "-u":
                    updateCsvFile = true;
                    break;
                default:
                    logger.warn("Unexpected argument: {}", arg);
            }
        }
    }

    /**
     * Get the list of source files for CSV data. This could either be all files in a specified directory, or just a
     * single file if a directory wasn't specified.
     *
     * @return List of input CSV files.
     * @since 1.1
     */
    private static List<String> getSourceFileNames()
    {
        List<String> sourceCsvFileNames;

        String sourceCsvFolderPath = config.getString("us.ctic.jira.source.csvFolderName");
        if (!sourceCsvFolderPath.isEmpty())
        {
            logger.info("Selecting input CSV files from the folder at: {}, ignoring the single csv file", sourceCsvFolderPath);
            File csvFolder = new File(sourceCsvFolderPath);

            if (!csvFolder.isDirectory())
            {
                logger.error("Provided path is not a directory: {}", sourceCsvFolderPath);
                return Collections.emptyList();
            }

            final File[] csvFiles = csvFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".csv"));

            if (csvFiles == null)
            {
                logger.error("An error occurred attempting to read from directory {}", sourceCsvFolderPath);
                return Collections.emptyList();
            } else if (csvFiles.length == 0)
            {
                logger.error("No CSV files found in directory {}", sourceCsvFolderPath);
                return Collections.emptyList();
            }

            sourceCsvFileNames = Arrays.stream(csvFiles)
                    .map(File::getAbsolutePath)
                    .collect(Collectors.toList());
        } else
        {
            String sourceCsvFileName = config.getString("us.ctic.jira.source.csvFileName");
            logger.info("Using a single CSV as the input: {}, source csv folder was not set", sourceCsvFileName);
            sourceCsvFileNames = List.of(sourceCsvFileName);
        }

        return sourceCsvFileNames;
    }

    /**
     * Connects to a JIRA service for the given type.
     *
     * @param serviceType the type defined in the config file (source, or target)
     * @return jira service of that type
     */
    private static JiraService getJiraService(String serviceType)
    {
        String host = config.getString(CONFIG_PREFIX + serviceType + ".host");
        String username = config.getString(CONFIG_PREFIX + serviceType + ".username");
        String password = config.getString(CONFIG_PREFIX + serviceType + ".password");
        String token = config.getString(CONFIG_PREFIX + serviceType + ".token");
        String projectKey = config.getString(CONFIG_PREFIX + serviceType + ".projectKey");
        return new JiraService(host, username, password, token, projectKey);
    }

    /**
     * Creates a mapping of source username to target username for the set of provided names. If a user from the source
     * Jira instance cannot be found on the target instance, a default user is used instead.
     *
     * @param csvUsernames The usernames found in the Jira CSV export
     * @return A map of source usernames to target usernames
     */
    private static Map<String, String> createUsernameMapping(Set<String> csvUsernames)
    {
        String defaultTargetUsername = config.getString("us.ctic.jira.target.defaultUsername");

        logger.info("Verifying default user {} exists on target server...", defaultTargetUsername);
        JiraUser defaultUser = targetJiraService.findUserByUsername(defaultTargetUsername);
        if (defaultUser != null)
        {
            logger.info("Found default user: {}", defaultUser);
        } else
        {
            logger.warn("Couldn't find default user on {}: {}", targetJiraService, defaultTargetUsername);
        }

        // Look up the user info that corresponds to the usernames in the CSV file from the source Jira instance.
        logger.info("Getting user info from source Jira instance: {}...", sourceJiraService);
        Map<String, JiraUser> sourceUsersByName = new TreeMap<>();
        for (String username : csvUsernames)
        {
            JiraUser user = sourceJiraService.findUserByUsername(username);
            if (user != null)
            {
                logger.debug("Found user: {}", user);
                sourceUsersByName.put(username, user);
            } else
            {
                logger.warn("Couldn't find user on {}: {}", sourceJiraService, username);
            }
        }

        logger.info("Found {} users on {}.", sourceUsersByName.size(), sourceJiraService);

        // Now we need to find the corresponding users in the target Jira instance.
        logger.info("Getting user info from target Jira instance: {}...", targetJiraService);
        boolean lastNameDisplayedFirst = config.getBoolean("us.ctic.jira.source.lastNameDisplayedFirst");

        Map<String, String> sourceUserToTargetUserMap = new TreeMap<>();
        for (String sourceUserName : csvUsernames)
        {
            JiraUser sourceUser = sourceUsersByName.get(sourceUserName);
            if (sourceUser != null)
            {
                String firstName = null;
                String lastName = null;
                List<String> firstAndLastName = ParseUtils.getFirstAndLastName(sourceUser.getDisplayName(), lastNameDisplayedFirst);
                if (firstAndLastName != null)
                {
                    firstName = firstAndLastName.get(0);
                    lastName = firstAndLastName.get(1);
                }

                JiraUser targetUser = targetJiraService.findUser(null, sourceUser.getEmailAddress(), firstName, lastName);
                if (targetUser != null)
                {
                    logger.debug("Found user: {}", targetUser);
                    sourceUserToTargetUserMap.put(sourceUserName, targetUser.getName());
                } else
                {
                    logger.warn("Couldn't find user on {}: {}; using default instead: {}", targetJiraService, sourceUser, defaultTargetUsername);
                    sourceUserToTargetUserMap.put(sourceUserName, defaultTargetUsername);
                }
            } else
            {
                logger.warn("User no longer exists on {}: {}; using default instead: {}", sourceJiraService, sourceUserName, defaultTargetUsername);
                sourceUserToTargetUserMap.put(sourceUserName, defaultTargetUsername);
            }
        }

        return sourceUserToTargetUserMap;
    }

    /**
     * Writes the provided mapping to the specified file as comma-separated values.
     *
     * @param mapping            The mapping of source object to target object
     * @param csvFilename        The name of the file to which to write
     * @param mappingDisplayText The display text of the type of mapping being done
     */
    private static void writeMappingToFile(Map<String, String> mapping, String csvFilename, String mappingDisplayText)
    {
        try (FileWriter fileWriter = new FileWriter(csvFilename);
             CSVPrinter csvPrinter = new CSVPrinter(fileWriter, CSVFormat.DEFAULT))
        {
            for (Map.Entry<String, String> entry : mapping.entrySet())
            {
                String key = entry.getKey();
                String value = entry.getValue();
                csvPrinter.printRecord(key, value);
            }
        } catch (IOException e)
        {
            logger.error("Error writing {} file: {}", mappingDisplayText, csvFilename, e);
        }
    }

    /**
     * Parses the specified file to populate a map of values. Each line of the file should be in the format:
     * {@code source-value,target-value}
     *
     * @param mapCsvFileName The name of the file to parse
     * @return A map of source keys to target keys (i.e., usernames or issue types)
     */
    private static Map<String, String> populateMappingFromFile(String mapCsvFileName)
    {
        Map<String, String> sourceToTargetMap = new LinkedHashMap<>();

        try (Reader reader = new FileReader(mapCsvFileName);
             CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT))
        {
            csvParser.getRecords().forEach(record -> sourceToTargetMap.put(record.get(0), record.get(1)));
        } catch (IOException e)
        {
            logger.error("Error parsing file: {}", mapCsvFileName, e);
        }

        return sourceToTargetMap;
    }

    /**
     * Create the mapping file for issue types from the source JIRA to the target JIRA.
     *
     * @return map of source JIRA issue type names to target JIRA issue type names.
     */
    private static Map<String, String> createIssueTypeMapping()
    {
        Set<String> sourceIssueTypeNames = sourceJiraService.getIssueTypeNames();
        logger.info("Found {} source issue type names.", sourceIssueTypeNames.size());
        Set<String> targetIssueTypeNames = targetJiraService.getIssueTypeNames();
        logger.info("Found {} target issue type names.", targetIssueTypeNames.size());

        logger.info("Creating issue type mapping...");
        Map<String, String> issueTypeMapping = new LinkedHashMap<>();
        for (String sourceIssueTypeName : sourceIssueTypeNames)
        {
            // TODO KMB: Perhaps I'm missing something, but why not just do something like this? Why are we looking for
            //  a target string containing the source type instead of equality?
            //   issueTypeMapping.put(sourceIssueTypeName,
            //           targetIssueTypeNames.contains(sourceIssueTypeName) ? sourceIssueTypeName : NO_TARGET_MATCH);
            String targetMatchName = targetIssueTypeNames.stream()
                    .filter(sourceIssueTypeName::contains)
                    .findFirst()
                    .orElse(NO_TARGET_MATCH);
            issueTypeMapping.put(sourceIssueTypeName, targetMatchName);
        }

        // TODO KMB: Currently the issue types need to be sorted manually. Do we want to add something for that here so
        //  epics are first and subtasks are last? It should probably be something in the config file...

        logger.info("Completed issue type mapping: {}", issueTypeMapping);
        return issueTypeMapping;
    }

    /**
     * Replaces anything matching {@code NO_TARGET_MATCH} in the map values with the key.
     *
     * @param mapping mapping to clean
     * @return map with all valid values
     */
    private static Map<String, String> cleanMappings(Map<String, String> mapping)
    {
        // TODO KMB: Why even bother to create the mapping if we are just going to update it later to remove any without
        //  a target match?
        return mapping.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry ->
                                entry.getValue().equals(NO_TARGET_MATCH)
                                        ? entry.getKey()
                                        : entry.getValue(),
                        (s, s2) -> {
                            logger.info("Duplicate mapping entry found for {}, using first one in list.", s);
                            return s;
                        },
                        LinkedHashMap::new));
    }

    /**
     * Sorts the issues in the provided source file by issue type and splits them into separate files in the specified
     * folder with no more than the maximum number of issues.
     *
     * @param sourceCsvFileName The CSV file containing all of the issues
     * @param splitFolder       The folder to which to save the new split files
     * @param issueTypeMap      A mapping of issues types for sorting the issues
     */
    private static void orderByIssueTypeAndSplitCsvFileByCount(String sourceCsvFileName, String splitFolder,
                                                               Map<String, String> issueTypeMap)
    {
        List<String> headerRow;
        List<CSVRecord> records;
        try (Reader reader = new FileReader(sourceCsvFileName);
             CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT.withHeader()))
        {
            headerRow = new ArrayList<>(csvParser.getHeaderNames());
            records = new ArrayList<>(csvParser.getRecords());
        } catch (IOException e)
        {
            logger.error("Error reading csv for splitting: {}", sourceCsvFileName, e);
            return;
        }

        if (headerRow.isEmpty() || records.isEmpty())
        {
            logger.error("No jira issues found in CSV file(s).");
            return;
        }

        int issueTypePosition = headerRow.indexOf(ISSUE_TYPE_CSV_KEY);
        if (issueTypePosition == -1)
        {
            logger.error("Could not find an issue type column in the CSV file.");
            return;
        }

        Map<String, List<CSVRecord>> csvByIssueType = records.stream()
                .collect(Collectors.groupingBy(strings -> strings.get(issueTypePosition)));

        List<String> orderedIssueTypes = issueTypeMap.values().stream()
                .distinct()
                .collect(Collectors.toList());

        List<String> unOrderedIssueTypes = csvByIssueType.keySet().stream()
                .filter(Predicate.not(orderedIssueTypes::contains))
                .collect(Collectors.toList());

        if (!unOrderedIssueTypes.isEmpty())
        {
            logger.warn("Found Issue Types in the CSV file that were not in the project issue type mapping file: {}",
                    String.join(", ", unOrderedIssueTypes));
        }

        List<String> allIssueTypesInOrder = Stream.concat(orderedIssueTypes.stream(), unOrderedIssueTypes.stream())
                .collect(Collectors.toList());

        List<CSVRecord> orderedCsvRecords = allIssueTypesInOrder.stream()
                .map(csvByIssueType::get)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .collect(Collectors.toList());

        logger.info("Beginning splitting csv file for {} records.", orderedCsvRecords.size());
        FileWriter writer = null;
        CSVPrinter csvPrinter = null;
        int previousFileNumber = 0;
        String[] headerRowArray = new String[headerRow.size()];
        headerRowArray = headerRow.toArray(headerRowArray);

        final String fileNamePrefix = ParseUtils.getFileNameWithoutPathOrExtension(sourceCsvFileName);
        try
        {
            for (int index = 0; index < orderedCsvRecords.size(); index++)
            {
                int remainder = (index + 1) % SPLIT_COUNT == 0 ? 0 : 1;
                int fileNumber = (index + 1) / SPLIT_COUNT + remainder;

                if (writer == null || previousFileNumber != fileNumber)
                {
                    // Always close previous file before we make a new one
                    if (writer != null) writer.close();

                    String fileName = fileNamePrefix + "_Split_" + fileNumber + ".csv";
                    writer = new FileWriter(splitFolder + File.separator + fileName);
                    csvPrinter = new CSVPrinter(writer, CSVFormat.DEFAULT.withHeader(headerRowArray));
                    logger.debug("Creating csv file: {}", fileName);
                }
                csvPrinter.printRecord(orderedCsvRecords.get(index));
                previousFileNumber = fileNumber;
            }

            logger.info("Completed splitting csv into {} files.", previousFileNumber);
            if (writer != null) writer.close();
        } catch (IOException e)
        {
            logger.error("Error creating a split csv file for: {}", sourceCsvFileName, e);
        }
    }

    /**
     * Replaces all occurrences of source mappings in the specified CSV file and outputs the result to the target CSV
     * file (specified in the config settings).
     *
     * @param sourceCsvFiles    The files to update
     * @param targetCsvFileName The file to save the update to
     * @param mappingsList      List of mapping files
     */
    private static void updateCsvFileWithMappings(List<String> sourceCsvFiles, String targetCsvFileName, List<Map<String, String>> mappingsList)
    {
        logger.info("Updating usernames and issue types in CSV file(s) and writing to {}...", targetCsvFileName);

        // Combine the username and issue type mappings together so we don't have to loop over the file(s) twice
        String[] sourceNames = mappingsList.stream().map(Map::keySet).flatMap(Collection::stream).toArray(String[]::new);
        String[] targetNames = mappingsList.stream().map(Map::values).flatMap(Collection::stream).toArray(String[]::new);

        try (PrintWriter writer = new PrintWriter(targetCsvFileName))
        {
            for (String sourceCsvFileName : sourceCsvFiles)
            {
                // Replace all instances of the source values in the CSV file with the target values.
                BufferedReader bufferedReader = new BufferedReader(new FileReader(sourceCsvFileName));

                String line;
                while ((line = bufferedReader.readLine()) != null)
                {
                    String updatedLine = StringUtils.replaceEach(line, sourceNames, targetNames);
                    writer.println(updatedLine);
                }
            }
        } catch (IOException e)
        {
            logger.error("Error writing csv from mapping files: {}", targetCsvFileName, e);
        }
    }
}
