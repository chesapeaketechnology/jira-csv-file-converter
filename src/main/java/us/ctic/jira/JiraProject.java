package us.ctic.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.List;

@SuppressWarnings("unused") // Fields are being set by Jackson
@JsonIgnoreProperties(ignoreUnknown = true)
public class JiraProject
{
    public String self;
    public String id;
    public String key;
    public String name;
    public JiraAvatarUrls avatarUrls;
    public List<JiraIssueType> issuetypes;

    public String getSelf()
    {
        return self;
    }

    public String getId()
    {
        return id;
    }

    public String getKey()
    {
        return key;
    }

    public String getName()
    {
        return name;
    }

    public JiraAvatarUrls getAvatarUrls()
    {
        return avatarUrls;
    }

    public List<JiraIssueType> getIssueTypes()
    {
        return Collections.unmodifiableList(issuetypes);
    }

    @Override
    public String toString()
    {
        return "JiraProject{" +
               "self='" + self + '\'' +
               ", id='" + id + '\'' +
               ", key='" + key + '\'' +
               ", name='" + name + '\'' +
               ", avatarUrls=" + avatarUrls +
               ", issueTypes=" + issuetypes +
               '}';
    }
}

