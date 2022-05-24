package us.ctic.jira;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.QuoteMode;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collection;
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

    private static final int splitCount = config.getInt("us.ctic.jira.splitFileMaxIssueCount");
    private static final String SOURCE = "source";
    private static final String TARGET = "target";
    private static final String NO_TARGET_MATCH = "NO_TARGET_MATCH";
    private static final String ISSUE_TYPE_CSV_KEY = "Issue Type";

    private static boolean createUserMap = false;
    private static boolean updateCsvFile = false;
    private static boolean createIssueTypeMap = false;


    /**
     * Main method for updating Jira CSV files for porting to a new instance.
     *
     * @param args Command line arguments; allowed arguments are:
     *             -m   Create a file mapping usernames found in the CSV to usernames in the target Jira instance.
     *             -u   Update the provided CSV file to replace usernames using the mapping
     */
    public static void main(String[] args)
    {
        processCommandLineArguments(args);

        String sourceCsvFileName = config.getString("us.ctic.jira.source.csvFileName");

        // First we need to find all the unique usernames in the exported CSV file
        logger.info("Extracting usernames from: {}", sourceCsvFileName);
        UsernameExtractor usernameExtractor = new UsernameExtractor(sourceCsvFileName);
        Set<String> csvUsernames = usernameExtractor.extractUserNames();

        Map<String, String> usernameMapping;
        String userMapCsvFileName = config.getString("us.ctic.jira.userMapFileName");
        if (createUserMap)
        {
            // Query the Jira instances to try to match usernames on the source instance to usernames on the target instance
            usernameMapping = createUsernameMapping(csvUsernames);

            // Write the mapping to a file so we don't have to do this again next time since it takes so long
            writeMappingToFile(usernameMapping, userMapCsvFileName, "User Mapping");
        } else
        {
            usernameMapping = populateMappingFromFile(userMapCsvFileName);
        }

        Map<String, String> issueTypeMapping;
        String issueTypeMapCsvFileName = config.getString("us.ctic.jira.issueTypeMapFileName");
        if (createIssueTypeMap)
        {
            issueTypeMapping = createIssueTypeMapping();

            writeMappingToFile(issueTypeMapping, issueTypeMapCsvFileName, "Issue Type Mapping");
        } else
        {
            issueTypeMapping = populateMappingFromFile(issueTypeMapCsvFileName);
        }

        usernameMapping = cleanMappings(usernameMapping);
        issueTypeMapping = cleanMappings(issueTypeMapping);

        List<Map<String, String>> mappingsList = new ArrayList<>();
        mappingsList.add(usernameMapping);
        mappingsList.add(issueTypeMapping);

        String targetCsvFileName = config.getString("us.ctic.jira.target.csvFileName");

        if (updateCsvFile)
        {
            updateCsvFileWithMappings(sourceCsvFileName, targetCsvFileName, mappingsList);
        }

        if (!issueTypeMapping.isEmpty())
        {
            String splitFolder = config.getString("us.ctic.jira.splitTargetFileFolder");
            orderByIssueTypeAndSplitCsvFileByCount(targetCsvFileName, splitFolder, issueTypeMapping);
        }
    }

    /**
     * Connects to a JIRA service for the given type.
     *
     * @param serviceType the type defined in the config file (source, or target)
     * @return jira service of that type
     */
    private static JiraService getJiraService(String serviceType)
    {
        String host = config.getString("us.ctic.jira." + serviceType + ".host");
        String username = config.getString("us.ctic.jira." + serviceType + ".username");
        String password = config.getString("us.ctic.jira." + serviceType + ".password");
        String token = config.getString("us.ctic.jira." + serviceType + ".token");
        String projectKey = config.getString("us.ctic.jira." + serviceType + ".projectKey");
        return new JiraService(host, username, password, token, projectKey);
    }

    /**
     * Create the mapping file for issue types from the source JIRA to the target JIRA.
     * @return map of source JIRA issue type names, to target JIRA issue type names.
     */
    private static Map<String, String> createIssueTypeMapping()
    {
        logger.info("Connecting to Jira servers...");
        JiraService sourceJiraService = getJiraService(SOURCE);
        JiraService targetJiraService = getJiraService(TARGET);

        Set<String> sourceIssueTypeNames = sourceJiraService.getIssueTypeNames();
        logger.info("Found {} source issue type names.", sourceIssueTypeNames.size());
        Set<String> targetIssueTypeNames = targetJiraService.getIssueTypeNames();
        logger.info("Found {} target issue type names.", targetIssueTypeNames.size());

        logger.info("Creating issue type mapping...");
        Map<String, String> issueTypeMapping = new LinkedHashMap<>();
        for (String sourceIssueTypeName : sourceIssueTypeNames)
        {
            String targetMatchName = targetIssueTypeNames.stream()
                    .filter(sourceIssueTypeName::contains)
                    .findFirst()
                    .orElse(NO_TARGET_MATCH);
            issueTypeMapping.put(sourceIssueTypeName, targetMatchName);
        }
        logger.info("Completed issue type mapping.");

        return issueTypeMapping;
    }

    /**
     * Replaces anything matching {@code NO_TARGET_MATCH} in the map values with the key.
     * @param mapping mapping to clean
     * @return map with all valid values
     */
    private static Map<String, String> cleanMappings(Map<String, String> mapping)
    {
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

    private static void orderByIssueTypeAndSplitCsvFileByCount(String sourceCsvFileName, String splitFolder, Map<String, String> issueTypeMap)
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
            logger.error("No jira issues found in CSV files.");
            return;
        }

        int issueTypePosition = headerRow.indexOf(ISSUE_TYPE_CSV_KEY);
        if (issueTypePosition == -1)
        {
            logger.error("Could not find an issue type in the csv file.");
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
            logger.warn("Found Issue Types in the CSV file that were not in the project issue type mapping " +
                    "file: {}", String.join(", ", unOrderedIssueTypes));
        }
        List<String> allIssueTypesInOrder = Stream.concat(orderedIssueTypes.stream(), unOrderedIssueTypes.stream())
                .collect(Collectors.toList());

        List<CSVRecord> orderedCsvRecords = allIssueTypesInOrder.stream()
                .map(csvByIssueType::get)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .collect(Collectors.toList());
        //orderedRecords.add(0, headerRow);

        logger.info("Beginning splitting csv file for {} records.", orderedCsvRecords.size());
        FileWriter writer = null;
        CSVPrinter csvPrinter = null;
        int previousPageNumber = 0;
        String[] headerRowArray = new String[headerRow.size()];
        headerRowArray = headerRow.toArray(headerRowArray);
        try
        {
            for (int index = 0; index < orderedCsvRecords.size(); index++)
            {
                int remainder = (index + 1) % splitCount == 0 ? 0 : 1;
                int pageNumber = (index + 1) / splitCount + remainder;

                if (writer == null || previousPageNumber != pageNumber)
                {
                    if (writer != null)
                    {
                        // Always close previous files before we make a new one
                        writer.close();
                    }
                    String fileName = "JiraCsvSplit" + "_" + pageNumber + ".csv";
                    writer = new FileWriter(splitFolder + fileName);
                    csvPrinter = new CSVPrinter(writer, CSVFormat.DEFAULT.withHeader(headerRowArray));
                    logger.debug("Creating csv file: {}", fileName);
                }
                csvPrinter.printRecord(orderedCsvRecords.get(index));
                previousPageNumber = pageNumber;
            }
            //Completed
            logger.info("Completed splitting csv into {} files.", previousPageNumber);
            if (writer != null)
            {
                writer.close();
            }
        } catch (IOException e)
        {
            logger.error("Error creating a split csv file for: {}", sourceCsvFileName, e);
        }
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
        logger.info("Connecting to Jira servers...");
        JiraService sourceJiraService = getJiraService(SOURCE);
        JiraService targetJiraService = getJiraService(TARGET);

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
     * Writes the provided mapping to the specified file as comma-separated values
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
     * Parses the specified file to populate a map of source usernames to target usernames. Each line of the file
     * should be in the format: {@code source-user-name,target-user-name}
     *
     * @param mapCsvFileName The name of the file to parse
     * @return A map of source keys to target keys (ie usernames, or issue types)
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
     * Replaces all occurrences of source mappings in the specified CSV file and outputs the result to the target CSV
     * file (specified in the config settings).
     *
     * @param sourceCsvFileName The file to update
     * @param targetCsvFileName The file to save the update to
     * @param mappingsList      List of mapping files
     */
    private static void updateCsvFileWithMappings(String sourceCsvFileName, String targetCsvFileName, List<Map<String, String>> mappingsList)
    {
        logger.info("Updating usernames in CSV file and writing to {}...", targetCsvFileName);

        // Replace all instances of the source usernames in the CSV file with the target usernames.
        // For any source usernames that don't exist in the target Jira instance, the default username will be used.
        try (BufferedReader bufferedReader = new BufferedReader(new FileReader(sourceCsvFileName));
             PrintWriter writer = new PrintWriter(targetCsvFileName))
        {
            String[] sourceNames = mappingsList.stream().map(Map::keySet).flatMap(Collection::stream).toArray(String[]::new);
            String[] targetNames = mappingsList.stream().map(Map::values).flatMap(Collection::stream).toArray(String[]::new);

            String line;
            while ((line = bufferedReader.readLine()) != null)
            {
                String updatedLine = StringUtils.replaceEach(line, sourceNames, targetNames);
                writer.println(updatedLine);
            }
        } catch (IOException e)
        {
            logger.error("Error writing csv from mapping files: {}", targetCsvFileName, e);
        }
    }
}
