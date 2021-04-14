package us.ctic.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class JiraUser
{
    private String name;
    private String emailAddress;
    private String displayName;

    public String getName()
    {
        return name;
    }

    public String getEmailAddress()
    {
        return emailAddress;
    }

    public String getDisplayName()
    {
        return displayName;
    }

    @Override
    public String toString()
    {
        return "JiraUser{" +
                "name='" + name + '\'' +
                ", emailAddress='" + emailAddress + '\'' +
                ", displayName='" + displayName + '\'' +
                '}';
    }
}
