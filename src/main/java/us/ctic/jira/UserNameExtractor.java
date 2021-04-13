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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Extracts the set of user names from a CSV file exported from Jira. This is necessary so the user names can be swapped
 * out for the correct user names on the destination Jira server.
 */
public class UserNameExtractor
{
    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static final String COMMENT_COLUMN_NAME = "Comment";
    private static final String WORK_LOG_COLUMN_NAME = "Log Work";

    // The names of columns that contain just a user name
    private static final List<String> USER_COLUMN_NAMES = Arrays.asList("Assignee", "Reporter", "Creator", "Watchers");

    // The regex for finding user tags in comments, which are formatted like "[~user.name]".
    private static final Pattern USER_TAG_PATTERN = Pattern.compile("\\[~([\\w.@-]+?)]");

    private final Map<String, List<Integer>> columnNameToIndexMap = new HashMap<>();
    private final Set<String> uniqueUserNames = new HashSet<>();

    public Set<String> extractUserNames(String jiraCsvFileName)
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

                // First the easy part: get the user names from the columns that only have a username
                searchUserNameColumns(record);

                // Next look through all the work logs, which we have to parse to get just the user name
                parseColumns(record, WORK_LOG_COLUMN_NAME, this::parseWorkLog);

                // And finally the hard part: for comments, we need the name of the commenter and any tags of others
                parseColumns(record, COMMENT_COLUMN_NAME, this::parseComment);
            }
        } catch (IOException e)
        {
            logger.error("Error parsing file: {}", jiraCsvFileName, e);
        }

        // If a column was empty, it will result in an empty string. Instead of checking for that every time, we just
        // remove it at the end.
        uniqueUserNames.remove("");

        logger.info("Found {} unique user names.", uniqueUserNames.size());

        return Collections.unmodifiableSet(uniqueUserNames);
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
     * Search the user name columns for user names to populate the set of unique user names.
     *
     * @param csvRecord The current CSV row
     */
    private void searchUserNameColumns(CSVRecord csvRecord)
    {
        for (String userNameColumn : USER_COLUMN_NAMES)
        {
            parseColumns(csvRecord, userNameColumn, uniqueUserNames::add);
        }
    }

    private void parseColumns(CSVRecord csvRecord, String columnName, Consumer<String> columnParser)
    {
        List<Integer> columnIndices = columnNameToIndexMap.get(columnName);

        for (Integer columnIndex : columnIndices)
        {
            String columnText = csvRecord.get(columnIndex);
            columnParser.accept(columnText);
        }
    }

    private void parseWorkLog(String workLog)
    {
        // A work log is formatted as <comment>;<date>;<user>;<time_minutes>
        String[] workLogFields = workLog.split(";");

        if (workLogFields.length >= 3)
        {
            String userName = workLogFields[2];
            uniqueUserNames.add(userName);
        }
    }

    private void parseComment(String comment)
    {
        // A comment is formatted as <date>;<user>;<text>
        String[] commentFields = comment.split(";", 3);

        if (commentFields.length == 3)
        {
            String userName = commentFields[1];
            uniqueUserNames.add(userName);

            USER_TAG_PATTERN.matcher(commentFields[2])
                    .results()
                    .forEach(matchResult -> uniqueUserNames.add(matchResult.group(1)));
        }
    }
}
