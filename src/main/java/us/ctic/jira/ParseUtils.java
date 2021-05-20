package us.ctic.jira;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ParseUtils
{
    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final Pattern ADMIN_PATTERN = Pattern.compile("\\(admin\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MIDDLE_INITIAL_PATTERN = Pattern.compile("[a-z]", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUFFIX_PATTERN = Pattern.compile("jr|sr|i{1,3}", Pattern.CASE_INSENSITIVE);

    /**
     * Parses the provided display name to get the first and last name.
     *
     * @param displayName            The display name to parse
     * @param lastNameDisplayedFirst Indicates if the last name is listed first in the display name (e.g. Doe, John)
     * @return A list with the first name at index 0 and the last name at index 1, or null if the string couldn't be parsed.
     */
    public static List<String> getFirstAndLastName(String displayName, boolean lastNameDisplayedFirst)
    {
        String[] names = displayName.split(",|\\s|\\.");

        // This is pretty naive, but it works for users I have with the two Jira instances I am working with,
        // which use the following formats:
        // - "First <M.> Last <Suffix> <(Admin)>"
        // - "Last <Suffix>, First <M.>"
        List<String> remainingNames = Arrays.stream(names)
                .filter(name -> !ADMIN_PATTERN.matcher(name).matches())
                .filter(name -> !MIDDLE_INITIAL_PATTERN.matcher(name).matches())
                .filter(name -> !SUFFIX_PATTERN.matcher(name).matches())
                .collect(Collectors.toList());

        // We should only have two names left...
        if (remainingNames.size() != 2)
        {
            logger.warn("Invalid number of fields left; should only have first and last. Fields: {}", remainingNames);
            return null;
        }

        if (lastNameDisplayedFirst)
        {
            Collections.reverse(remainingNames);
        }

        return remainingNames;
    }

    private ParseUtils()
    {
        // Private constructor to prevent instantiation
    }
}
