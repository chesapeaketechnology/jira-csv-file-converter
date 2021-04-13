package us.ctic.jira;

import java.util.Set;

public class Main
{
    public static final String JIRA_CSV_FILE = "test.csv";

    public static void main(String[] args)
    {
        UserNameExtractor userNameExtractor = new UserNameExtractor();
        Set<String> userNames = userNameExtractor.extractUserNames(JIRA_CSV_FILE);
    }
}
