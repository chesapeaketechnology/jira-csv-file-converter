package us.ctic.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class JiraIssueType
{
    public String self;
    public String id;
    public String description;
    public String iconUrl;
    public String name;
    public boolean subtask;

    public String getSelf()
    {
        return self;
    }

    public String getId()
    {
        return id;
    }

    public String getDescription()
    {
        return description;
    }

    public String getIconUrl()
    {
        return iconUrl;
    }

    public String getName()
    {
        return name;
    }

    public boolean isSubtask()
    {
        return subtask;
    }

    @Override
    public String toString()
    {
        return "JiraIssueType{" +
                "self='" + self + '\'' +
                ", id='" + id + '\'' +
                ", description='" + description + '\'' +
                ", iconUrl='" + iconUrl + '\'' +
                ", name='" + name + '\'' +
                ", subtask=" + subtask +
                '}';
    }
}
