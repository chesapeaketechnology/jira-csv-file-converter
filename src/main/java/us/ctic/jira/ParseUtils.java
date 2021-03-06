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
    private static final Pattern ACCOUNT_REMOVED_PATTERN = Pattern.compile("\\[X]", Pattern.CASE_INSENSITIVE);
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
        // - "First <M.> Last <Suffix> <(Admin)> <[X]>"
        // - "Last <Suffix>, First <M.>"
        List<String> remainingNames = Arrays.stream(names)
                .filter(name -> !ACCOUNT_REMOVED_PATTERN.matcher(name).matches())
                .filter(name -> !ADMIN_PATTERN.matcher(name).matches())
                .filter(name -> !MIDDLE_INITIAL_PATTERN.matcher(name).matches())
                .filter(name -> !SUFFIX_PATTERN.matcher(name).matches())
                .collect(Collectors.toList());

        // We should only have two names left...
        if (remainingNames.size() != 2)
        {
            // TODO KMB: This doesn't handle compound names well (e.g. Ann Marie Smith or Jean-Claude Van Damme). We
            //  should update it to take a guess or maybe try both options (compound first name and compound last name).
            logger.warn("Invalid number of fields left; should only have first and last. Fields: {}", remainingNames);
            return null;
        }

        if (lastNameDisplayedFirst)
        {
            Collections.reverse(remainingNames);
        }

        return remainingNames;
    }

    /**
     * Get just the name portion of a file name, excluding the path and the extension.
     *
     * @param fileName The name name to parse
     * @return The name of the file with no path or extension
     * @since 1.1
     */
    public static String getFileNameWithoutPathOrExtension(String fileName)
    {
        return getParsedFileName(fileName, true, true);
    }

    /**
     * Get just the name portion of a file name, excluding the extension.
     *
     * @param fileName The name name to parse
     * @return The name of the file with no extension
     * @since 1.1
     */
    public static String getFileNameWithoutExtension(String fileName)
    {
        return getParsedFileName(fileName, false, true);
    }

    /**
     * Get a parsed version of the file name, with the specified portions removed.
     *
     * @param fileName        The name name to parse
     * @param removePath      True if the path should be removed
     * @param removeExtension True if the extension should be removed
     * @return The name of the file with the specified portions removed
     * @since 1.1
     */
    private static String getParsedFileName(String fileName, boolean removePath, boolean removeExtension)
    {
        final int indexOfExtension = fileName.lastIndexOf('.');
        final int lastUnixPos = fileName.lastIndexOf('/');
        final int lastWindowsPos = fileName.lastIndexOf('\\');
        final int indexOfLastSeparator = Math.max(lastUnixPos, lastWindowsPos);

        if (removeExtension && indexOfExtension > indexOfLastSeparator)
        {
            return fileName.substring(removePath ? indexOfLastSeparator : 0, indexOfExtension);
        } else
        {
            return fileName.substring(removePath ? indexOfLastSeparator : 0);
        }
    }

    private ParseUtils()
    {
        // Private constructor to prevent instantiation
    }
}
