package us.ctic.jira;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Main
{
    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    public static void main(String[] args)
    {
        Config config = ConfigFactory.load();
        String sourceHost = config.getString("us.ctic.jira.source.host");
        String sourceUsername = config.getString("us.ctic.jira.source.username");
        String sourcePassword = config.getString("us.ctic.jira.source.password");
        String sourceCsvFile = config.getString("us.ctic.jira.source.csvFile");
        boolean lastNameDisplayedFirst = config.getBoolean("us.ctic.jira.source.lastNameDisplayedFirst");

        String targetHost = config.getString("us.ctic.jira.target.host");
        String targetUsername = config.getString("us.ctic.jira.target.username");
        String targetPassword = config.getString("us.ctic.jira.target.password");
        String defaultTargetUsername = config.getString("us.ctic.jira.target.defaultUser");

        logger.info("Connecting to Jira servers");
        JiraService sourceJiraService = new JiraService(sourceHost, sourceUsername, sourcePassword);
        JiraService targetJiraService = new JiraService(targetHost, targetUsername, targetPassword);

        logger.info("Verifying default user {} exists on target server", defaultTargetUsername);
        JiraUser defaultUser = targetJiraService.findUserByUsername(defaultTargetUsername);
        if (defaultUser != null)
        {
            logger.info("Found default user: {}", defaultUser);
        } else
        {
            logger.warn("Couldn't find default user on {}: {}", targetHost, defaultTargetUsername);
        }

        // First we need to find all the unique user names in the exported CSV file from the source Jira instance.
        logger.info("Extracting user names from: {}", sourceCsvFile);
        UserNameExtractor userNameExtractor = new UserNameExtractor();
        Set<String> sourceUserNames = userNameExtractor.extractUserNames(sourceCsvFile);

        // Next, get the users that correspond to those user names from the source Jira instance.
        logger.info("Getting user info from source Jira instance: {}", sourceHost);
        Map<String, JiraUser> sourceUsersByName = new HashMap<>();
        for (String userName : sourceUserNames)
        {
            JiraUser user = sourceJiraService.findUserByUsername(userName);
            if (user != null)
            {
                logger.info("Found user: {}", user);
                sourceUsersByName.put(userName, user);
            } else
            {
                logger.warn("Couldn't find user on {}: {}", sourceHost, userName);
            }
        }

        logger.info("Found {} users on {}.", sourceUsersByName.size(), sourceHost);

        // Now we need to find the corresponding users in the target Jira instance.
        logger.info("Getting user info from target Jira instance: {}", targetHost);
        Map<String, String> sourceUserToTargetUserMap = new HashMap<>();
        for (String sourceUserName : sourceUserNames)
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
                    logger.info("Found user: {}", targetUser);
                    sourceUserToTargetUserMap.put(sourceUserName, targetUser.getName());
                } else
                {
                    logger.warn("Couldn't find user on {}: {}; using default instead: {}", targetHost, sourceUser, defaultTargetUsername);
                    sourceUserToTargetUserMap.put(sourceUserName, defaultTargetUsername);
                }
            } else
            {
                logger.warn("User no longer exists on {}: {}; using default instead: {}", sourceHost, sourceUsername, defaultTargetUsername);
                sourceUserToTargetUserMap.put(sourceUserName, defaultTargetUsername);
            }
        }

        // Finally, replace all instances of the source usernames in the CSV file with the target usernames.
        // For any source usernames that don't exist in the target Jira instance, the default username will be used.
        logger.info("Updating usernames in CSV file.");
        // TODO
    }
}
