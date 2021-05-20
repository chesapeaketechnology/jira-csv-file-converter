package us.ctic.jira;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class Main
{
    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final Config config = ConfigFactory.load();
    private static boolean createUserMap = false;
    private static boolean updateCsvFile = false;

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
            writeUsernameMappingToFile(usernameMapping, userMapCsvFileName);
        } else
        {
            usernameMapping = populateUsernameMappingFromFile(userMapCsvFileName);
        }

        if (updateCsvFile)
        {
            updateUsernamesInCsvFile(sourceCsvFileName, usernameMapping);
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
                case "-u":
                    updateCsvFile = true;
                    break;
                default:
                    logger.warn("Unexpected argument: {}", arg);
            }
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
        String sourceHost = config.getString("us.ctic.jira.source.host");
        String sourceUsername = config.getString("us.ctic.jira.source.username");
        String sourcePassword = config.getString("us.ctic.jira.source.password");
        JiraService sourceJiraService = new JiraService(sourceHost, sourceUsername, sourcePassword);

        String targetHost = config.getString("us.ctic.jira.target.host");
        String targetUsername = config.getString("us.ctic.jira.target.username");
        String targetPassword = config.getString("us.ctic.jira.target.password");
        JiraService targetJiraService = new JiraService(targetHost, targetUsername, targetPassword);

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
     * Writes the provided username map to the specified file as comma-separated values
     *
     * @param usernameMapping    The mapping of source username to target username
     * @param userMapCsvFilename The name of the file to which to write
     */
    private static void writeUsernameMappingToFile(Map<String, String> usernameMapping, String userMapCsvFilename)
    {
        try (FileWriter fileWriter = new FileWriter(userMapCsvFilename);
             CSVPrinter csvPrinter = new CSVPrinter(fileWriter, CSVFormat.DEFAULT))
        {
            for (Map.Entry<String, String> entry : usernameMapping.entrySet())
            {
                String key = entry.getKey();
                String value = entry.getValue();
                csvPrinter.printRecord(key, value);
            }
        } catch (IOException e)
        {
            logger.error("Error writing user mapping file: {}", userMapCsvFilename, e);
        }
    }

    /**
     * Parses the specified file to populate a map of source usernames to target usernames. Each line of the file
     * should be in the format: {@code source-user-name,target-user-name}
     *
     * @param userMapCsvFileName The name of the file to parse
     * @return A map of source usernames to target usernames
     */
    private static Map<String, String> populateUsernameMappingFromFile(String userMapCsvFileName)
    {
        Map<String, String> sourceUserToTargetUserMap = new HashMap<>();

        try (Reader reader = new FileReader(userMapCsvFileName);
             CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT))
        {
            csvParser.getRecords().forEach(record -> sourceUserToTargetUserMap.put(record.get(0), record.get(1)));
        } catch (IOException e)
        {
            logger.error("Error parsing file: {}", userMapCsvFileName, e);
        }

        return sourceUserToTargetUserMap;
    }

    /**
     * Replaces all occurrences of source usernames in the specified CSV file and outputs the result to the target CSV
     * file (specified in the config settings).
     *
     * @param sourceCsvFileName The file to update
     * @param usernameMapping   The mapping of source usernames to target usernames
     */
    private static void updateUsernamesInCsvFile(String sourceCsvFileName, Map<String, String> usernameMapping)
    {
        String targetCsvFileName = config.getString("us.ctic.jira.target.csvFileName");
        logger.info("Updating usernames in CSV file and writing to {}...", targetCsvFileName);

        // Replace all instances of the source usernames in the CSV file with the target usernames.
        // For any source usernames that don't exist in the target Jira instance, the default username will be used.
        try (BufferedReader bufferedReader = new BufferedReader(new FileReader(sourceCsvFileName));
             PrintWriter writer = new PrintWriter(targetCsvFileName))
        {
            String[] sourceNames = usernameMapping.keySet().toArray(new String[0]);
            String[] targetNames = usernameMapping.values().toArray(new String[0]);

            String line;
            while ((line = bufferedReader.readLine()) != null)
            {
                String updatedLine = StringUtils.replaceEach(line, sourceNames, targetNames);
                writer.println(updatedLine);
            }
        } catch (IOException e)
        {
            logger.error("Error writing user mapping file: {}", targetCsvFileName, e);
        }
    }
}
