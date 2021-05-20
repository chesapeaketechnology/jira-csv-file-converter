package us.ctic.jira;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Extracts the set of usernames from a CSV file exported from Jira. This is necessary so the usernames can be swapped
 * out for the correct usernames on the destination Jira server.
 */
public class UsernameExtractor
{
    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static final String COMMENT_COLUMN_NAME = "Comment";
    private static final String WORK_LOG_COLUMN_NAME = "Log Work";

    // The names of columns that contain just a username
    private static final List<String> USER_COLUMN_NAMES = Arrays.asList("Assignee", "Reporter", "Creator", "Watchers");

    // The regex for finding user tags in comments, which are formatted like "[~username]".
    private static final Pattern USER_TAG_PATTERN = Pattern.compile("\\[~([\\w.@-]+?)]");

    // Map of column name to all the column indices with that name (since Jira reuses column names for things like Comment)
    private final Map<String, List<Integer>> columnNameToIndexMap = new HashMap<>();
    private final Set<String> uniqueUsernames = new TreeSet<>();
    private final String jiraCsvFileName;

    /**
     * Constructor
     *
     * @param jiraCsvFileName The name of the file from which to extract usernames
     */
    public UsernameExtractor(String jiraCsvFileName)
    {
        this.jiraCsvFileName = jiraCsvFileName;
    }

    /**
     * Searches all the relevant columns in the CSV file for usernames and returns the set of unique names.
     *
     * @return The set of unique usernames found in the CSV file.
     */
    public Set<String> extractUserNames()
    {
        try (Reader reader = new FileReader(jiraCsvFileName);
             CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT))
        {
            List<CSVRecord> records = csvParser.getRecords();

            if (!records.isEmpty())
            {
                // Apache CSV doesn't like duplicate column names, so we manage the column names manually
                parseColumnHeaders(records.get(0));
            }

            for (int i = 1; i < records.size(); i++)
            {
                CSVRecord record = records.get(i);

                // First the easy part: get the usernames from the columns that only have a username
                for (String usernameColumn : USER_COLUMN_NAMES)
                {
                    applyHandlerToColumns(record, usernameColumn, uniqueUsernames::add);
                }

                // Next look through all the work logs, which we have to parse to get just the username
                applyHandlerToColumns(record, WORK_LOG_COLUMN_NAME, this::findUsernameInWorkLog);

                // And finally the hard part: for comments, we need the username of the commenter and any tags of others
                applyHandlerToColumns(record, COMMENT_COLUMN_NAME, this::findUsernamesInComment);
            }
        } catch (IOException e)
        {
            logger.error("Error parsing file: {}", jiraCsvFileName, e);
        }

        // If a column was empty, it will result in an empty string. Instead of checking for that every time, we just
        // remove it at the end.
        uniqueUsernames.remove("");

        logger.info("Found {} unique usernames.", uniqueUsernames.size());

        return Collections.unmodifiableSet(uniqueUsernames);
    }

    /**
     * Parse the header row to construct a map of column name to the indices with that name.
     *
     * @param headerRecord The header row
     */
    private void parseColumnHeaders(CSVRecord headerRecord)
    {
        for (int i = 0; i < headerRecord.size(); i++)
        {
            String columnName = headerRecord.get(i);
            List<Integer> columnIndices = columnNameToIndexMap.computeIfAbsent(columnName, k -> new ArrayList<>());
            columnIndices.add(i);
        }
    }

    /**
     * Applies the provided column handler to each column with the specified name in the provided row.
     *
     * @param csvRecord     The current CSV row
     * @param columnName    The name of the column to handle
     * @param columnHandler The handler to perform the desired action on the columns
     */
    private void applyHandlerToColumns(CSVRecord csvRecord, String columnName, Consumer<String> columnHandler)
    {
        List<Integer> columnIndices = columnNameToIndexMap.get(columnName);

        for (Integer columnIndex : columnIndices)
        {
            String columnText = csvRecord.get(columnIndex);
            columnHandler.accept(columnText);
        }
    }

    /**
     * Searches the provided work log for the username and adds it to the username set.
     *
     * @param workLog The work log to search
     */
    private void findUsernameInWorkLog(String workLog)
    {
        // A work log is formatted as <comment>;<date>;<user>;<time_minutes>
        String[] workLogFields = workLog.split(";");

        if (workLogFields.length >= 3)
        {
            String username = workLogFields[2];
            uniqueUsernames.add(username);
        }
    }

    /**
     * Searches the provided comment for any usernames and adds them to the username set.
     *
     * @param comment The comment to search
     */
    private void findUsernamesInComment(String comment)
    {
        // A comment is formatted as <date>;<user>;<text>
        String[] commentFields = comment.split(";", 3);

        if (commentFields.length == 3)
        {
            String username = commentFields[1];
            uniqueUsernames.add(username);

            // In addition to the user that wrote the comment, there could be users tagged in the comment
            USER_TAG_PATTERN.matcher(commentFields[2])
                    .results()
                    .forEach(matchResult -> uniqueUsernames.add(matchResult.group(1)));
        }
    }
}
